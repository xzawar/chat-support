/**
 * The widget's three calls: handshake, identify, and send.
 *
 * Handshake is where every trust decision is made — API key, Origin, plan gate — and it is the
 * only place they are made. Afterwards the widget carries a signed token that already encodes
 * tenantId, websiteId and conversationId, so no later route has to re-derive who is calling from
 * anything the caller can edit.
 *
 * The visitor also gets an RTDB custom token with the same conversationId claim. That token can
 * only ever read one thread, and it cannot write anything: RTDB rules deny writes globally and
 * messages arrive through POST /v1/widget/messages instead.
 */

const express = require("express")
const { firestore, rtdb, auth, FieldValue } = require("../firebase")
const {
  asyncHandler,
  requiredString,
  optionalString,
  badRequest,
  unauthorized,
  paymentRequired,
  forbidden,
  notFound,
} = require("../http")
const { signWidgetToken, requireWidget } = require("../middleware/widget")
const { hasFeature } = require("../lib/entitlements")
const {
  sha256,
  normalizeEmail,
  leadId,
  randomId,
  originHost,
  domainMatches,
} = require("../lib/crypto")
const {
  RETENTION_MILLIS,
  FEATURE_CHAT,
  WIDGET_TOKEN_TTL_SECONDS,
  publicDatabaseUrl,
  widgetAuthEmulatorUrl,
} = require("../config")

const router = express.Router()

/** Public Firebase config the widget needs to open an RTDB socket. Not a secret. */
function publicFirebaseConfig() {
  return {
    apiKey: process.env.FIREBASE_WEB_API_KEY || null,
    authDomain: process.env.FIREBASE_AUTH_DOMAIN || null,
    projectId: process.env.GCLOUD_PROJECT || process.env.GCP_PROJECT || null,
    databaseURL: publicDatabaseUrl(),
    // Null in production. Present only when the widget must sign in against an Auth emulator.
    authEmulatorUrl: widgetAuthEmulatorUrl(),
  }
}

/**
 * The API key, from the JSON body or from an Authorization: Bearer header.
 *
 * Both are accepted because the WordPress proxy sends the header while the app and the tests send
 * the body, and a handshake that fails on the transport rather than the credential is a very
 * expensive hour to debug. The key is treated identically either way - hashed, never logged.
 */
function readApiKey(req) {
  const header = req.get("Authorization") || ""
  const bearer = header.startsWith("Bearer ") ? header.slice(7).trim() : ""

  if (bearer) {
    if (bearer.length > 128) {
      throw badRequest("apiKey is too long.", "field_too_long")
    }
    return bearer
  }

  return requiredString(req.body, "apiKey", 128)
}

function conversationPath(tenantId, conversationId) {
  return `chats/${tenantId}/conversations/${conversationId}`
}

function messagePath(tenantId, conversationId, clientMessageId) {
  return `chats/${tenantId}/messages/${conversationId}/${clientMessageId}`
}

/**
 * POST /v1/widget/handshake { apiKey, conversationId? }
 *
 * Returns a widgetToken, an RTDB custom token, the database URL and the public config.
 */
router.post(
  "/handshake",
  asyncHandler(async (req, res) => {
    const apiKey = readApiKey(req)
    const requestedConversationId = optionalString(req.body, "conversationId", 128)

    // 1. API key — matched by hash, never by the stored raw value, because there is no raw value.
    const websiteSnap = await firestore()
      .collectionGroup("websites")
      .where("apiKeyHash", "==", sha256(apiKey))
      .limit(1)
      .get()

    if (websiteSnap.empty) {
      throw unauthorized("This chat widget is not configured correctly.", "invalid_api_key")
    }

    const websiteDoc = websiteSnap.docs[0]
    const website = websiteDoc.data()
    const tenantId = website.tenantId || websiteDoc.ref.parent.parent.id

    if (website.active === false) {
      throw forbidden("Chat is turned off for this website.", "website_inactive")
    }

    // 2. Origin allowlist. A missing Origin is treated as a failure, not as a pass — browsers
    //    always send one on a cross-origin POST, so its absence means this is not the widget.
    const host = originHost(req.get("Origin") || req.get("Referer"))
    if (!domainMatches(website.domain, host)) {
      throw forbidden("This chat widget is not allowed on this domain.", "origin_not_allowed")
    }

    // 3. Plan gate. 402, distinct from the 403 the app's feature gates return, because the cause
    //    is billing rather than tier.
    const tenantSnap = await firestore().collection("tenants").doc(tenantId).get()
    if (!tenantSnap.exists) throw notFound("Workspace not found.", "tenant_not_found")
    const tenant = { id: tenantSnap.id, ...tenantSnap.data() }

    if (!hasFeature(tenant, FEATURE_CHAT)) {
      throw paymentRequired(
        "Chat is unavailable right now. Please contact us another way.",
        "subscription_inactive",
      )
    }

    // A returning visitor keeps their thread; a new one gets a conversation created server-side.
    const now = Date.now()
    let conversationId = requestedConversationId
    let created = false

    if (conversationId) {
      const existing = await rtdb().ref(conversationPath(tenantId, conversationId)).get()
      if (!existing.exists()) {
        conversationId = null
      }
    }

    if (!conversationId) {
      conversationId = randomId("conv")
      created = true
      await rtdb()
        .ref(conversationPath(tenantId, conversationId))
        .set({
          id: conversationId,
          tenantId,
          websiteId: websiteDoc.id,
          websiteDomain: website.domain,
          status: "pending",
          createdAt: now,
          expiresAt: now + RETENTION_MILLIS,
          unread: 0,
          visitor: { name: null, email: null },
          lastMessage: null,
        })
    }

    const widgetToken = signWidgetToken({
      tenantId,
      websiteId: websiteDoc.id,
      conversationId,
    })

    // Same conversationId claim as the JWT, so the RTDB rules and the API agree on scope.
    const rtdbToken = await auth().createCustomToken(`widget_${conversationId}`, {
      tenantId,
      conversationId,
      widget: true,
    })

    res.json({
      conversationId,
      created,
      widgetToken,
      rtdbToken,
      expiresIn: WIDGET_TOKEN_TTL_SECONDS,
      databaseURL: publicDatabaseUrl(),
      firebaseConfig: publicFirebaseConfig(),
      paths: {
        conversation: conversationPath(tenantId, conversationId),
        messages: `chats/${tenantId}/messages/${conversationId}`,
      },
    })
  }),
)

/**
 * POST /v1/widget/identify { googleIdToken? , email?, name?, marketingConsent? }
 *
 * Upserts a lead keyed on websiteId + sha256(lowercase email), so the same person identifying
 * three times is one lead with a moving lastSeenAt rather than three rows.
 *
 * emailVerified is only ever true when it came from a verified Google token. A typed-in address
 * is unverified by definition, and recording otherwise would poison the email list later.
 */
router.post(
  "/identify",
  requireWidget,
  asyncHandler(async (req, res) => {
    const { tenantId, websiteId, conversationId } = req.widget
    const googleIdToken = optionalString(req.body, "googleIdToken", 4096)

    let email = null
    let name = optionalString(req.body, "name", 120)
    let source = "manual"
    let emailVerified = false

    if (googleIdToken) {
      let decoded
      try {
        decoded = await auth().verifyIdToken(googleIdToken)
      } catch (err) {
        throw unauthorized("Google sign-in could not be verified.", "google_token_invalid")
      }
      email = normalizeEmail(decoded.email)
      name = decoded.name || name
      source = "google"
      emailVerified = decoded.email_verified === true
    } else {
      email = normalizeEmail(requiredString(req.body, "email", 254))
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
        throw badRequest("That email address does not look right.", "invalid_email")
      }
    }

    if (!email) throw badRequest("An email address is required.", "missing_field")

    const db = firestore()
    const websiteSnap = await db
      .collection("tenants")
      .doc(tenantId)
      .collection("websites")
      .doc(websiteId)
      .get()
    const websiteDomain = websiteSnap.exists ? websiteSnap.data().domain : null

    const id = leadId(websiteId, email)
    const leadRef = db.collection("tenants").doc(tenantId).collection("leads").doc(id)

    // conversationCount increments only when this conversation has not been counted before, so a
    // visitor who reloads the page five times in one chat is one conversation, not five.
    await db.runTransaction(async (tx) => {
      const snap = await tx.get(leadRef)
      const existing = snap.exists ? snap.data() : null
      const seenConversations = (existing && existing.conversationIds) || []
      const isNewConversation = !seenConversations.includes(conversationId)

      const update = {
        websiteId,
        websiteDomain,
        email,
        name: name || (existing ? existing.name : null),
        source,
        emailVerified: emailVerified || Boolean(existing && existing.emailVerified),
        marketingConsent:
          req.body && typeof req.body.marketingConsent === "boolean"
            ? req.body.marketingConsent
            : Boolean(existing && existing.marketingConsent),
        lastSeenAt: FieldValue.serverTimestamp(),
      }

      if (!existing) {
        update.firstSeenAt = FieldValue.serverTimestamp()
        update.conversationCount = 1
        update.conversationIds = [conversationId]
      } else if (isNewConversation) {
        update.conversationCount = FieldValue.increment(1)
        update.conversationIds = FieldValue.arrayUnion(conversationId)
      }

      tx.set(leadRef, update, { merge: true })
    })

    // The agent app reads the visitor block off the conversation, so it has to be mirrored into
    // RTDB. Only name and email cross over; consent and verification stay in Firestore.
    await rtdb()
      .ref(`${conversationPath(tenantId, conversationId)}/visitor`)
      .update({ name: name || null, email })

    res.json({ ok: true, leadId: id, email, name: name || null, source, emailVerified })
  }),
)

/**
 * POST /v1/widget/messages { clientMessageId, text }
 *
 * One multi-path update writes the message and the conversation's lastMessage, expiresAt and
 * unread counter together. Two separate writes would let a message exist for a moment with a
 * stale conversation row, which is exactly the window the inbox list would render wrong.
 *
 * clientMessageId is the idempotency key: a retry after a dropped response overwrites the same
 * node instead of posting the message twice.
 */
router.post(
  "/messages",
  requireWidget,
  asyncHandler(async (req, res) => {
    const { tenantId, conversationId } = req.widget
    const clientMessageId = requiredString(req.body, "clientMessageId", 128)
    const text = requiredString(req.body, "text", 4000)

    if (!/^[A-Za-z0-9_-]+$/.test(clientMessageId)) {
      throw badRequest(
        "clientMessageId may only contain letters, numbers, hyphens and underscores.",
        "invalid_client_message_id",
      )
    }

    const conversationSnap = await rtdb().ref(conversationPath(tenantId, conversationId)).get()
    if (!conversationSnap.exists()) {
      throw notFound("This conversation is no longer available.", "conversation_not_found")
    }
    const conversation = conversationSnap.val() || {}
    if (conversation.status === "closed") {
      throw forbidden("This conversation has been closed.", "conversation_closed")
    }

    const now = Date.now()
    const message = {
      id: clientMessageId,
      sender: "visitor",
      text,
      createdAt: now,
    }

    const updates = {}
    updates[messagePath(tenantId, conversationId, clientMessageId)] = message
    updates[`${conversationPath(tenantId, conversationId)}/lastMessage`] = {
      text,
      at: now,
      sender: "visitor",
    }
    updates[`${conversationPath(tenantId, conversationId)}/expiresAt`] = now + RETENTION_MILLIS
    updates[`${conversationPath(tenantId, conversationId)}/unread`] =
      (conversation.unread || 0) + 1

    await rtdb().ref().update(updates)

    res.status(201).json({ ok: true, messageId: clientMessageId, createdAt: now })
  }),
)

module.exports = router
