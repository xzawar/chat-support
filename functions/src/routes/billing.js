/**
 * Billing: plans, coupons, and a payment gateway that is not Stripe.
 *
 * There is exactly one function that changes a subscription — buildActivation() — and all three
 * entry points (100%-off coupon, sandbox checkout, gateway callback) go through it. That is the
 * whole design. A plan activated by a coupon and a plan activated by a payment must leave the
 * tenant document in byte-identical shape, or the feature gate starts behaving differently
 * depending on how somebody paid.
 *
 * No card data touches this process, and nothing here logs an email or a name.
 */

const express = require("express")
const { firestore, FieldValue } = require("../firebase")
const {
  asyncHandler,
  requiredString,
  optionalString,
  badRequest,
  notFound,
  forbidden,
  conflict,
} = require("../http")
const { requireAuth, requireTenant, attachTenant, requireOwner } = require("../middleware/auth")
const { createGateway } = require("../lib/billingGateway")
const { safeEqual, randomId } = require("../lib/crypto")
const { toMillis } = require("../lib/entitlements")
const { PERIOD_MILLIS, billingProvider, billingCallbackSecret } = require("../config")

const router = express.Router()

/**
 * The single subscription mutation, expressed as data rather than performed here so it can be
 * applied inside a transaction, a batch, or a plain set.
 */
function buildActivation(plan, now) {
  return {
    plan: plan.id,
    features: plan.features || [],
    status: "active",
    currentPeriodEnd: now + PERIOD_MILLIS,
    updatedAt: FieldValue.serverTimestamp(),
  }
}

function priceAfterDiscount(priceCents, percentOff) {
  const discounted = Math.round(priceCents * (1 - percentOff / 100))
  return Math.max(0, discounted)
}

/**
 * Coupon validity, in one place so apply-coupon and checkout cannot disagree.
 * Throws the message the app shows the user verbatim.
 */
function assertCouponUsable(coupon, tenantId, now) {
  if (!coupon || coupon.active !== true) {
    throw badRequest("That coupon is not valid.", "coupon_invalid")
  }
  const expiresAt = toMillis(coupon.expiresAt)
  if (expiresAt !== null && expiresAt <= now) {
    throw badRequest("That coupon has expired.", "coupon_expired")
  }
  if (
    coupon.maxRedemptions !== null &&
    coupon.maxRedemptions !== undefined &&
    (coupon.redeemedCount || 0) >= coupon.maxRedemptions
  ) {
    throw badRequest("That coupon has been fully redeemed.", "coupon_exhausted")
  }
}

// ---------------------------------------------------------------------------
// Owner routes
// ---------------------------------------------------------------------------

const ownerRoutes = express.Router()
ownerRoutes.use(requireAuth, requireTenant, attachTenant, requireOwner)

/** GET /v1/billing/plans — the catalogue the subscription screen renders one card per. */
ownerRoutes.get(
  "/plans",
  asyncHandler(async (req, res) => {
    const snap = await firestore().collection("plans").where("active", "==", true).get()

    const plans = snap.docs
      .map((doc) => ({ id: doc.id, ...doc.data() }))
      .sort((a, b) => (a.tier || 0) - (b.tier || 0))
      .map((plan) => ({
        id: plan.id,
        name: plan.name,
        tier: plan.tier,
        features: plan.features || [],
        priceCents: plan.priceCents || 0,
        currency: plan.currency || "USD",
        description: plan.description || null,
        isCurrent: plan.id === req.tenant.plan,
      }))

    res.json({ plans, currentPlan: req.tenant.plan || null, status: req.tenant.status || null })
  }),
)

/**
 * POST /v1/billing/apply-coupon { code, planId }
 *
 * One transaction covers validate, activate, record the redemption and increment the counter, so
 * two taps on Apply cannot both succeed: the second transaction re-reads the redemption document
 * this one wrote and bails. That is what makes redeemedCount increment exactly once.
 */
ownerRoutes.post(
  "/apply-coupon",
  asyncHandler(async (req, res) => {
    const code = requiredString(req.body, "code", 64).toUpperCase()
    const planId = requiredString(req.body, "planId", 64)
    const tenantId = req.auth.tenantId
    const db = firestore()

    const couponRef = db.collection("coupons").doc(code)
    const planRef = db.collection("plans").doc(planId)
    const tenantRef = db.collection("tenants").doc(tenantId)
    const redemptionRef = couponRef.collection("redemptions").doc(tenantId)

    const result = await db.runTransaction(async (tx) => {
      const [couponSnap, planSnap, redemptionSnap] = await Promise.all([
        tx.get(couponRef),
        tx.get(planRef),
        tx.get(redemptionRef),
      ])

      if (!planSnap.exists || planSnap.data().active !== true) {
        throw notFound("That plan is not available.", "plan_not_found")
      }
      if (!couponSnap.exists) {
        throw badRequest("That coupon is not valid.", "coupon_invalid")
      }

      const now = Date.now()
      const coupon = couponSnap.data()
      assertCouponUsable(coupon, tenantId, now)

      if (redemptionSnap.exists) {
        throw conflict(
          "This workspace has already redeemed that coupon.",
          "coupon_already_redeemed",
        )
      }

      const plan = { id: planSnap.id, ...planSnap.data() }
      const percentOff = Number(coupon.percentOff || 0)

      // Below 100% this endpoint only quotes a price. Activation is the payment flow's job, and
      // pre-activating a discounted plan would hand out paid tiers for a 50%-off code.
      if (percentOff < 100) {
        return {
          activated: false,
          planId: plan.id,
          percentOff,
          originalPriceCents: plan.priceCents || 0,
          discountedPriceCents: priceAfterDiscount(plan.priceCents || 0, percentOff),
          currency: plan.currency || "USD",
        }
      }

      tx.set(tenantRef, buildActivation(plan, now), { merge: true })
      tx.set(redemptionRef, {
        tenantId,
        code,
        planId: plan.id,
        percentOff,
        redeemedAt: FieldValue.serverTimestamp(),
      })
      tx.update(couponRef, { redeemedCount: FieldValue.increment(1) })

      return {
        activated: true,
        planId: plan.id,
        percentOff,
        currentPeriodEnd: now + PERIOD_MILLIS,
        features: plan.features || [],
      }
    })

    res.json(result)
  }),
)

/**
 * POST /v1/billing/checkout { planId, couponCode? }
 *
 * Goes through the BillingGateway port. The sandbox adapter approves synchronously, at which
 * point this runs the same activation write as the coupon path and records the payment so the
 * callback for it is a no-op.
 */
ownerRoutes.post(
  "/checkout",
  asyncHandler(async (req, res) => {
    const planId = requiredString(req.body, "planId", 64)
    const couponCode = optionalString(req.body, "couponCode", 64)
    const tenantId = req.auth.tenantId
    const db = firestore()
    const now = Date.now()

    const planSnap = await db.collection("plans").doc(planId).get()
    if (!planSnap.exists || planSnap.data().active !== true) {
      throw notFound("That plan is not available.", "plan_not_found")
    }
    const plan = { id: planSnap.id, ...planSnap.data() }

    let amountCents = plan.priceCents || 0
    let percentOff = 0
    if (couponCode) {
      const couponSnap = await db.collection("coupons").doc(couponCode.toUpperCase()).get()
      if (!couponSnap.exists) throw badRequest("That coupon is not valid.", "coupon_invalid")
      assertCouponUsable(couponSnap.data(), tenantId, now)
      percentOff = Number(couponSnap.data().percentOff || 0)
      amountCents = priceAfterDiscount(amountCents, percentOff)
    }

    const gateway = createGateway(billingProvider())
    const reference = randomId("ref")

    // Deliberately no email, no name, no address. The gateway gets an opaque tenant handle.
    const checkout = await gateway.createCheckout({
      tenantId,
      planId: plan.id,
      amountCents,
      currency: plan.currency || "USD",
      reference,
    })

    const paymentRef = db.collection("payments").doc(checkout.paymentId)

    await db.runTransaction(async (tx) => {
      const existing = await tx.get(paymentRef)
      if (existing.exists && existing.data().applied === true) return

      tx.set(paymentRef, {
        paymentId: checkout.paymentId,
        reference,
        tenantId,
        planId: plan.id,
        amountCents,
        currency: plan.currency || "USD",
        percentOff,
        provider: gateway.name,
        status: checkout.status,
        applied: checkout.status === "approved",
        createdAt: FieldValue.serverTimestamp(),
      })

      if (checkout.status === "approved") {
        tx.set(db.collection("tenants").doc(tenantId), buildActivation(plan, now), { merge: true })
      }
    })

    res.json({
      paymentId: checkout.paymentId,
      reference,
      provider: gateway.name,
      status: checkout.status,
      redirectUrl: checkout.redirectUrl || null,
      activated: checkout.status === "approved",
      amountCents,
      currency: plan.currency || "USD",
      planId: plan.id,
    })
  }),
)

router.use("/", ownerRoutes)

// ---------------------------------------------------------------------------
// Gateway callback — not a user route
// ---------------------------------------------------------------------------

/**
 * POST /v1/billing/callback
 *
 * Authenticated by a shared secret header, not a user token, because the caller is a machine.
 * Idempotent on paymentId: a provider that retries five times must activate one period, so the
 * `applied` flag is read and written inside the same transaction as the tenant update.
 */
router.post(
  "/callback",
  asyncHandler(async (req, res) => {
    const presented = req.get("X-Billing-Secret") || ""
    if (!safeEqual(presented, billingCallbackSecret())) {
      throw forbidden("Invalid callback signature.", "callback_unauthorized")
    }

    const gateway = createGateway(billingProvider())
    if (!gateway.verifyCallback(req.body, req.headers)) {
      throw forbidden("Invalid callback signature.", "callback_unauthorized")
    }

    const payload = gateway.parseCallback(req.body)
    if (!payload.paymentId) throw badRequest("paymentId is required.", "missing_field")

    const db = firestore()
    const paymentRef = db.collection("payments").doc(payload.paymentId)

    const outcome = await db.runTransaction(async (tx) => {
      const paymentSnap = await tx.get(paymentRef)
      if (!paymentSnap.exists) {
        throw notFound("Unknown payment.", "payment_not_found")
      }
      const payment = paymentSnap.data()

      if (payment.applied === true) {
        return { applied: false, reason: "already_applied", tenantId: payment.tenantId }
      }
      if (payload.status !== "approved") {
        tx.update(paymentRef, { status: payload.status, updatedAt: FieldValue.serverTimestamp() })
        return { applied: false, reason: payload.status, tenantId: payment.tenantId }
      }

      const planSnap = await tx.get(db.collection("plans").doc(payment.planId))
      if (!planSnap.exists) throw notFound("Plan no longer exists.", "plan_not_found")
      const plan = { id: planSnap.id, ...planSnap.data() }

      tx.set(
        db.collection("tenants").doc(payment.tenantId),
        buildActivation(plan, Date.now()),
        { merge: true },
      )
      tx.update(paymentRef, {
        status: "approved",
        applied: true,
        appliedAt: FieldValue.serverTimestamp(),
      })

      return { applied: true, reason: null, tenantId: payment.tenantId }
    })

    // Always 200 for a well-formed callback — a provider that sees an error retries forever.
    res.json({ ok: true, applied: outcome.applied, reason: outcome.reason })
  }),
)

module.exports = router
