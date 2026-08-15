/**
 * GET /v1/tenants/me — the document the Android app caches at login.
 *
 * This is the only place the client learns its plan, status and features. The app uses it to hide
 * menu items; the backend re-checks every gate on every call regardless, because a hidden menu
 * item is a courtesy and not a permission.
 *
 * There is one kind of caller: the tenant's owner. The response still carries a role field so the
 * app's account screen has something to show, but it is a constant, not a decision — reaching this
 * handler at all already proves ownership.
 */

const express = require("express")
const { firestore } = require("../firebase")
const { asyncHandler } = require("../http")
const { requireAuth, requireTenant, attachTenant, requireOwner } = require("../middleware/auth")
const { subscriptionActive, toMillis } = require("../lib/entitlements")

const router = express.Router()

router.use(requireAuth, requireTenant, attachTenant, requireOwner)

router.get(
  "/me",
  asyncHandler(async (req, res) => {
    const tenant = req.tenant

    // The plan doc is fetched only for display (name, tier, price). Entitlement still comes from
    // the copied features[] on the tenant, so a missing plan doc cannot lock anyone out.
    let planDoc = null
    if (tenant.plan) {
      const snap = await firestore().collection("plans").doc(tenant.plan).get()
      if (snap.exists) planDoc = { id: snap.id, ...snap.data() }
    }

    res.json({
      tenantId: tenant.id,
      name: tenant.name || null,
      role: "owner",
      isOwner: true,
      plan: {
        id: tenant.plan || null,
        name: planDoc ? planDoc.name : null,
        tier: planDoc ? planDoc.tier : null,
        priceCents: planDoc ? planDoc.priceCents : null,
        currency: planDoc ? planDoc.currency : null,
      },
      status: tenant.status || null,
      // Blank entries are dropped on the way out too, so a bad seed cannot show up in the app as a
      // nameless unlocked feature.
      features: Array.isArray(tenant.features)
        ? tenant.features.filter((entry) => typeof entry === "string" && entry.trim().length > 0)
        : [],
      currentPeriodEnd: toMillis(tenant.currentPeriodEnd),
      subscriptionActive: subscriptionActive(tenant),
    })
  }),
)

module.exports = router
