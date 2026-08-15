package com.codexce.supportchat.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.codexce.supportchat.data.model.ChatMessage
import com.codexce.supportchat.data.model.Conversation

/**
 * Room mirrors the Realtime Database shape one-to-one. No renaming, no invented fields: the
 * remote schema stays the contract, Room is only a local cache of it.
 *
 * Version 3 changes:
 * - `pending` on messages, so a message the agent typed while offline can be drawn immediately
 *   and then quietly settle once the server echoes it back over RTDB.
 * - `outbox`, the durable send queue. The client can no longer write RTDB, so every send goes
 *   through POST /v1/conversations/{id}/messages. The row survives process death and the
 *   clientMessageId is the idempotency key, which is why a retry can never duplicate.
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val visitorName: String,
    val visitorEmail: String,
    val pageUrl: String,
    val status: String,
    val assignedAgentUid: String?,
    val lastText: String,
    val lastAt: Long,
    val unreadForAgents: Long,
    val unreadForVisitor: Long,
    val keepChat: Boolean,
    val createdAt: Long,
    val startedAt: Long,
    val userAgent: String,
    val country: String,
)

@Entity(
    tableName = "messages",
    indices = [Index("conversationId"), Index("createdAt")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val sender: String,
    val text: String,
    val createdAt: Long,
    val readAt: Long?,
    /** True while the message is still sitting in the outbox waiting on the API. */
    val pending: Boolean = false,
)

/**
 * The offline send queue.
 *
 * `clientMessageId` is the primary key here and the RTDB child key server-side, so replaying
 * the same row is a no-op rather than a second message.
 */
@Entity(
    tableName = "outbox",
    indices = [Index("conversationId"), Index("createdAt")],
)
data class OutboxEntity(
    @PrimaryKey val clientMessageId: String,
    val conversationId: String,
    val text: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
)

fun ConversationEntity.toDomain(): Conversation = Conversation(
    id = id,
    visitorName = visitorName,
    visitorEmail = visitorEmail,
    pageUrl = pageUrl,
    status = status,
    assignedAgentUid = assignedAgentUid,
    lastText = lastText,
    lastAt = lastAt,
    unreadForAgents = unreadForAgents,
    unreadForVisitor = unreadForVisitor,
    keepChat = keepChat,
    createdAt = createdAt,
    startedAt = startedAt,
    userAgent = userAgent,
    country = country,
)

fun Conversation.toEntity(): ConversationEntity = ConversationEntity(
    id = id,
    visitorName = visitorName,
    visitorEmail = visitorEmail,
    pageUrl = pageUrl,
    status = status,
    assignedAgentUid = assignedAgentUid,
    lastText = lastText,
    lastAt = lastAt,
    unreadForAgents = unreadForAgents,
    unreadForVisitor = unreadForVisitor,
    keepChat = keepChat,
    createdAt = createdAt,
    startedAt = startedAt,
    userAgent = userAgent,
    country = country,
)

fun MessageEntity.toDomain(): ChatMessage = ChatMessage(
    id = id,
    sender = sender,
    text = text,
    createdAt = createdAt,
    readAt = readAt,
    pending = pending,
)

fun ChatMessage.toEntity(conversationId: String): MessageEntity = MessageEntity(
    id = id,
    conversationId = conversationId,
    sender = sender,
    text = text,
    createdAt = createdAt,
    readAt = readAt,
    pending = pending,
)

/** The optimistic row drawn the moment the agent hits send. */
fun OutboxEntity.toPendingMessage(): MessageEntity = MessageEntity(
    id = clientMessageId,
    conversationId = conversationId,
    // Wire value for "written from the app", unchanged: existing history and the deployed
    // widget both read it. Authorship, not a role.
    sender = "agent",
    text = text,
    createdAt = createdAt,
    readAt = null,
    pending = true,
)
