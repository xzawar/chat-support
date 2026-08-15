/**
 * Owner-side chat writes.
 *
 * The Android app used to call setValue() on RTDB directly. It no longer can: rules deny every
 * client write, so owner replies, assignment, and closing all arrive here and the Admin SDK is
 * the only thing that touches the database.
 *
 * Both write routes use the same multi-path update as the widget, for the same reason — the
 * message and the conversation row have to move in one commit or the inbox renders a thread whose
 * preview does not match its last message.
 */

const express = require("express")
const { rtdb } = require("../firebase")
const {
  asyncHandler,
  requiredString,
  optionalString,
  badRequest,
  notFound,
  forbidden,
} = require("../http")
const {
  requireAuth,
  requireTenant,
  attachTenant,
  requireOwner,
  requireActiveChat,
} = require("../middleware/auth")
const { RETENTION_MILLIS } = require("../config")

const router = express.Router()

// Chat is gated on the "chat" feature, so a lapsed subscription freezes the inbox rather than
// silently dropping replies the owner thinks they sent.
router.use(requireAuth, requireTenant, attachTenant, requireOwner, requireActiveChat())

const conversationPath = (tenantId, id) => `chats/${tenantId}/conversations/${id}`
const messagePath = (tenantId, id, messageId) => `chats/${tenantId}/messages/${id}/${messageId}`

async function loadConversation(tenantId, conversationId) {
  const snap = await rtdb().ref(conversationPath(tenantId, conversationId)).get()
  if (!snap.exists()) throw notFound("Conversation not found.", "conversation_not_found")
  return snap.val() || {}
}

/**
 * POST /v1/conversations/:id/messages { clientMessageId, text }
 *
 * clientMessageId comes from the app's offline queue. It is the idempotency key: replaying the
 * queue after a reconnect overwrites the same node rather than duplicating the message.
 */
router.post(
  "/:conversationId/messages",
  asyncHandler(async (req, res) => {
    const tenantId = req.auth.tenantId
    const conversationId = req.params.conversationId
    const clientMessageId = requiredString(req.body, "clientMessageId", 128)
    const text = requiredString(req.body, "text", 4000)

    if (!/^[A-Za-z0-9_-]+$/.test(clientMessageId)) {
      throw badRequest(
        "clientMessageId may only contain letters, numbers, hyphens and underscores.",
        "invalid_client_message_id",
      )
    }

    const conversation = await loadConversation(tenantId, conversationId)
    if (conversation.status === "closed") {
      throw forbidden("This conversation is closed. Reopen it to reply.", "conversation_closed")
    }

    const now = Date.now()
    const updates = {}
    updates[messagePath(tenantId, conversationId, clientMessageId)] = {
      id: clientMessageId,
      sender: "agent",
      senderUid: req.auth.uid,
      text,
      createdAt: now,
    }
    updates[`${conversationPath(tenantId, conversationId)}/lastMessage`] = {
      text,
      at: now,
      sender: "agent",
    }
    updates[`${conversationPath(tenantId, conversationId)}/expiresAt`] = now + RETENTION_MILLIS
    // An agent reply is the agent reading the thread, so the unread badge clears in the same commit.
    updates[`${conversationPath(tenantId, conversationId)}/unread`] = 0

    await rtdb().ref().update(updates)

    res.status(201).json({ ok: true, messageId: clientMessageId, createdAt: now })
  }),
)

/**
 * PATCH /v1/conversations/:id { status?, assignedAgentUid?, keepChat?, unread? }
 *
 * The status vocabulary is fixed at pending | open | closed. It is validated here as well as in
 * the RTDB rules because the rules only see what reaches them, and after this change nothing
 * reaches them except this process.
 */
router.patch(
  "/:conversationId",
  asyncHandler(async (req, res) => {
    const tenantId = req.auth.tenantId
    const conversationId = req.params.conversationId
    await loadConversation(tenantId, conversationId)

    const updates = {}
    const now = Date.now()

    const status = optionalString(req.body, "status", 16)
    if (status) {
      if (!["pending", "open", "closed"].includes(status)) {
        throw badRequest("Status must be pending, open or closed.", "invalid_status")
      }
      updates.status = status
      if (status === "open") {
        updates.startedAt = now
        // Taking a chat assigns it, otherwise a second agent opens the same thread seconds later.
        updates.assignedAgentUid = req.auth.uid
      }
      if (status === "closed") updates.closedAt = now
    }

    if (Object.prototype.hasOwnProperty.call(req.body || {}, "assignedAgentUid")) {
      const assignee = req.body.assignedAgentUid
      updates.assignedAgentUid = assignee === null ? null : String(assignee)
    }

    if (typeof (req.body || {}).keepChat === "boolean") {
      updates.keepChat = req.body.keepChat
      // Keeping a chat has to move expiresAt, or the retention job deletes it anyway in an hour.
      updates.expiresAt = req.body.keepChat ? null : now + RETENTION_MILLIS
    }

    if (typeof (req.body || {}).unread === "number") {
      updates.unread = Math.max(0, Math.floor(req.body.unread))
    }

    if (Object.keys(updates).length === 0) {
      throw badRequest("Nothing to update.", "empty_update")
    }

    await rtdb().ref(conversationPath(tenantId, conversationId)).update(updates)
    res.json({ ok: true, conversationId, updated: Object.keys(updates) })
  }),
)

/** DELETE /v1/conversations/:id — removes the thread and its messages in one commit. */
router.delete(
  "/:conversationId",
  asyncHandler(async (req, res) => {
    const tenantId = req.auth.tenantId
    const conversationId = req.params.conversationId
    await loadConversation(tenantId, conversationId)

    const updates = {}
    updates[conversationPath(tenantId, conversationId)] = null
    updates[`chats/${tenantId}/messages/${conversationId}`] = null
    await rtdb().ref().update(updates)

    res.json({ ok: true, conversationId, deleted: true })
  }),
)

module.exports = router
