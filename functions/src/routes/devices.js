/**
 * FCM device registration.
 *
 * This exists because clients can no longer write any database. The old app wrote its token
 * straight into owners/{uid}/devices; that path is gone, so the token now arrives here and the
 * backend stores it at tenants/{tenantId}/devices/{deviceId} — exactly where notifications.js
 * reads it from.
 *
 * There is one user per tenant, so devices hang off the tenant rather than off a membership
 * document. The uid still comes from the verified token and is recorded on the device row for
 * debugging, never taken from the body.
 */

const express = require("express")
const { firestore, FieldValue } = require("../firebase")
const { asyncHandler, requiredString } = require("../http")
const { requireAuth, requireTenant, attachTenant, requireOwner } = require("../middleware/auth")

const router = express.Router()

router.use(requireAuth, requireTenant, attachTenant, requireOwner)

function deviceRef(tenantId, deviceId) {
  return firestore()
    .collection("tenants")
    .doc(tenantId)
    .collection("devices")
    .doc(deviceId)
}

/**
 * Upsert. The app calls this on every sign-in, not only when the token rotates, because
 * onNewToken does not fire for a device that already holds a token.
 */
router.post(
  "/",
  asyncHandler(async (req, res) => {
    const deviceId = requiredString(req.body, "deviceId", 200)
    const token = requiredString(req.body, "token", 4096)
    const { tenantId, uid } = req.auth

    await deviceRef(tenantId, deviceId).set(
      {
        token,
        uid,
        platform: "android",
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true },
    )

    res.json({ registered: true, deviceId })
  }),
)

/**
 * Called before sign-out. Deleting a token that is already gone is not an error — sign-out must
 * never fail because of cleanup.
 */
router.delete(
  "/:deviceId",
  asyncHandler(async (req, res) => {
    const { tenantId } = req.auth
    await deviceRef(tenantId, req.params.deviceId).delete()
    res.json({ unregistered: true, deviceId: req.params.deviceId })
  }),
)

module.exports = router
