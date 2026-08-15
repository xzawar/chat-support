/**
 * POST /v1/bootstrap — the one-time escape from the chicken-and-egg problem.
 *
 * Every other route in this API reads tenantId from a verified custom claim. Nothing sets that
 * claim. Without this endpoint the very first owner can authenticate and still be locked out of
 * their own installation forever, and the usual workaround — letting one route accept tenantId
 * from the request body — punches a permanent hole in the model to solve a problem that occurs
 * exactly once.
 *
 * So the privilege is concentrated here and then destroyed:
 *
 *   - Eligibility. When BOOTSTRAP_ADMIN is set, only that uid or email may call this. When it is
 *     unset the first authenticated caller wins, which is safe only because of the lock below.
 *   - The lock. system/bootstrap is created inside the same transaction that creates the tenant.
 *     Two callers racing means one transaction retries, reads the lock, and loses.
 *   - Idempotent for the original owner. Calling again re-applies the claims and returns the same
 *     tenant, because the realistic failure is "claims were set but the client never refreshed
 *     its token", and the fix for that must not be a 409.
 *   - 409 for anybody else, permanently. There is no reset route; clearing system/bootstrap by
 *     hand in the console is the only way back, which is the correct amount of friction.
 *
 * One owner per tenant, and nobody else, ever. There is no membership collection, no invite flow
 * and no role: the owner *is* tenants/{tenantId}.ownerUid, which is what every route checks.
 */

const express = require("express")
const { firestore, auth, FieldValue } = require("../firebase")
const { asyncHandler, forbidden, conflict, optionalString } = require("../http")
const { requireAuth } = require("../middleware/auth")
const { randomId } = require("../lib/crypto")
const { seedCatalog, seedEmailTemplates } = require("../seed")
const { PERIOD_MILLIS, bootstrapAdmin } = require("../config")

const router = express.Router()

const LOCK_PATH = ["system", "bootstrap"]

/** BOOTSTRAP_ADMIN may be a uid or an email; unset means "first caller wins". */
function assertEligible(caller) {
  const configured = bootstrapAdmin()
  if (!configured) return
  const matches =
    caller.uid === configured ||
    (caller.email && caller.email.toLowerCase() === String(configured).toLowerCase())
  if (!matches) {
    throw forbidden("This account is not allowed to run setup.", "bootstrap_not_eligible")
  }
}

/**
 * GET /v1/bootstrap/status
 *
 * Lets the app decide between "show setup" and "show sign-in" without provoking a 409. Returns
 * only whether the installation is initialised and whether this caller owns it — never the
 * tenant id of somebody else's workspace.
 */
router.get(
  "/status",
  requireAuth,
  asyncHandler(async (req, res) => {
    const lock = await firestore().collection(LOCK_PATH[0]).doc(LOCK_PATH[1]).get()
    const data = lock.exists ? lock.data() : null
    const isOwner = Boolean(data && data.ownerUid === req.auth.uid)

    res.json({
      initialized: lock.exists,
      isBootstrapOwner: isOwner,
      tenantId: isOwner ? data.tenantId : null,
      claimsPresent: Boolean(req.auth.tenantId),
      // Told plainly because "I ran setup and nothing works" is almost always a stale ID token.
      refreshTokenRequired: isOwner && !req.auth.tenantId,
    })
  }),
)

/**
 * POST /v1/bootstrap
 * Body: { workspaceName?: string }
 */
router.post(
  "/",
  requireAuth,
  asyncHandler(async (req, res) => {
    assertEligible(req.auth)

    const db = firestore()
    const lockRef = db.collection(LOCK_PATH[0]).doc(LOCK_PATH[1])
    const workspaceName =
      optionalString(req.body, "workspaceName", 80) ||
      (req.auth.email ? req.auth.email.split("@")[0] : "My workspace")

    // The catalogue is global and idempotent, so seeding it before the transaction is safe and
    // keeps the transaction body to pure reads-then-writes on documents it actually locks.
    await seedCatalog()

    const planSnap = await db.collection("plans").doc("plan_1").get()
    if (!planSnap.exists) {
      throw conflict("Plan catalogue is missing. Run the seed script.", "plans_missing")
    }
    const plan = planSnap.data()

    const outcome = await db.runTransaction(async (tx) => {
      const lock = await tx.get(lockRef)

      if (lock.exists) {
        const data = lock.data()
        if (data.ownerUid !== req.auth.uid) {
          throw conflict(
            "This installation has already been set up by another account.",
            "already_initialized",
          )
        }
        // Same owner calling twice: no writes, just report the existing tenant so the caller can
        // refresh its token and carry on.
        return { tenantId: data.tenantId, created: false }
      }

      const tenantId = randomId("tnt")
      const now = Date.now()
      const tenantRef = db.collection("tenants").doc(tenantId)

      // ownerUid is the whole authorisation model. requireOwner compares the verified uid against
      // this field on every request, so there is no membership document to keep in step with it
      // and no role string that can disagree with it.
      tx.set(tenantRef, {
        name: workspaceName,
        ownerUid: req.auth.uid,
        ownerEmail: req.auth.email || null,
        plan: "plan_1",
        // Copied from the plan doc, not joined at read time — a gate check must never depend on a
        // second document being fetchable.
        features: plan.features || [],
        status: "trialing",
        currentPeriodEnd: now + PERIOD_MILLIS,
        createdAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      })

      tx.set(lockRef, {
        tenantId,
        ownerUid: req.auth.uid,
        ownerEmail: req.auth.email || null,
        initializedAt: FieldValue.serverTimestamp(),
        locked: true,
      })

      return { tenantId, created: true }
    })

    // Claims are set outside the transaction because Auth is a different system and cannot be
    // rolled back with Firestore. Setting them last means the worst case is a tenant that exists
    // with claims missing — which a second call to this endpoint repairs.
    //
    // tenantId is the only claim. Authorisation is not carried in the token at all, which is why a
    // stale token can no longer imply stale permissions.
    await auth().setCustomUserClaims(req.auth.uid, {
      tenantId: outcome.tenantId,
    })

    if (outcome.created) {
      await seedEmailTemplates(outcome.tenantId)
    }

    res.status(outcome.created ? 201 : 200).json({
      ok: true,
      created: outcome.created,
      tenantId: outcome.tenantId,
      plan: "plan_1",
      status: "trialing",
      // The single most common support question, answered in the response body.
      nextStep:
        "Refresh the ID token (getIdToken(true)) or sign out and back in. The tenantId claim is " +
        "only present in tokens minted after this call.",
    })
  }),
)

module.exports = router
