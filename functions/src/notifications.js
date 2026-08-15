/**
 * Push delivery, pointed at chats/{tenantId}/ and the tenant's own device list.
 *
 * The Phase 8.3 behaviour is unchanged and deliberate: the support *request* alerts the owner, not
 * every message. A visitor typing four lines before anyone looks at the app is one request for
 * help, and should be one notification.
 *
 * What changed in this pass is who the recipients are. There is no membership collection any more
 * — a tenant has exactly one user, its owner — so device tokens live at
 * tenants/{tenantId}/devices/{deviceId} and every device of that tenant is notified. There is no
 * assignment-based routing left to do, because there is nobody else to route to.
 */

const { admin, firestore, rtdb } = require("./firebase")

/** Every registered device of this tenant, with enough context to prune dead tokens. */
async function collectTokens(tenantId) {
  const devicesSnap = await firestore()
    .collection("tenants")
    .doc(tenantId)
    .collection("devices")
    .get()

  const devices = []
  devicesSnap.forEach((deviceDoc) => {
    const token = deviceDoc.data().token
    if (token) devices.push({ deviceId: deviceDoc.id, token })
  })

  return devices
}

/**
 * One data-only multicast, then prune whatever the device has invalidated.
 *
 * Data-only is deliberate: the app builds the notification itself in every app state, so a tap
 * always carries conversationId and deep-links into the right thread. A `notification` payload
 * would be drawn by the system while backgrounded and lose that.
 */
async function pushToDevices(tenantId, devices, data) {
  const tokens = Array.from(new Set(devices.map((entry) => entry.token)))
  if (tokens.length === 0) {
    console.log("No device tokens to push to", data)
    return null
  }

  const response = await admin.messaging().sendEachForMulticast({
    tokens,
    data,
    android: { priority: "high" },
  })

  const stale = []
  response.responses.forEach((result, index) => {
    const code = result.error && result.error.code
    if (
      code === "messaging/registration-token-not-registered" ||
      code === "messaging/invalid-registration-token"
    ) {
      stale.push(tokens[index])
    }
  })

  if (stale.length > 0) {
    const batch = firestore().batch()
    devices.forEach(({ deviceId, token }) => {
      if (stale.includes(token)) {
        batch.delete(
          firestore()
            .collection("tenants")
            .doc(tenantId)
            .collection("devices")
            .doc(deviceId),
        )
      }
    })
    await batch.commit()
  }

  console.log(`Sent ${response.successCount}/${tokens.length} pushes`, data)
  return null
}

async function loadConversation(tenantId, conversationId) {
  const snap = await rtdb().ref(`chats/${tenantId}/conversations/${conversationId}`).get()
  return snap.val() || {}
}

module.exports = { collectTokens, pushToDevices, loadConversation }
