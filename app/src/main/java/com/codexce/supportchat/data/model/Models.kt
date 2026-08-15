package com.codexce.supportchat.data.model

import androidx.compose.runtime.Immutable

/**
 * The three states a conversation can be in.
 *
 * PENDING is new in this pass: a visitor who has asked for support but whom no agent has picked
 * up yet. It is what triggers the initial notification. OPEN and CLOSED are unchanged and keep
 * the exact strings the widget and the old data already use, so nothing that exists breaks.
 */
object ConversationStatus {
    const val PENDING = "pending"
    const val OPEN = "open"
    const val CLOSED = "closed"
}

/**
 * Backend shape: /owners/{ownerUid}/conversations/{id}
 * with `status`, `assignedAgentUid`, `lastMessage/{text,sender,at}`, `unreadForAgents`,
 * `unreadForVisitor` and a nested `visitor` object. Do not rename these fields; the web
 * widget and the database rules both depend on them.
 *
 * Added in this pass, all optional so existing rows keep parsing: `keepChat`, `createdAt`,
 * `startedAt`, and the extra `visitor` details the profile screen shows.
 */
@Immutable
data class Conversation(
    val id: String,
    val visitorName: String = "Visitor",
    val visitorEmail: String = "",
    val pageUrl: String = "",
    val status: String = ConversationStatus.OPEN,
    val assignedAgentUid: String? = null,
    val lastText: String = "",
    val lastAt: Long = 0,
    val unreadForAgents: Long = 0,
    val unreadForVisitor: Long = 0,
    /** Exempts this conversation from the 24-hour purge. Absent means false. */
    val keepChat: Boolean = false,
    val createdAt: Long = 0,
    /** When an agent hit Start Chat. Zero while still pending. */
    val startedAt: Long = 0,
    val userAgent: String = "",
    val country: String = "",
) {
    val isClosed: Boolean get() = status == ConversationStatus.CLOSED

    /** Requested support, nobody has taken it yet. */
    val isPending: Boolean get() = status == ConversationStatus.PENDING

    /** An agent has started it and it is not closed. */
    val isActive: Boolean get() = status == ConversationStatus.OPEN

    /** Last activity, falling back to creation for a thread with no messages yet. */
    val lastActivityAt: Long get() = if (lastAt > 0) lastAt else createdAt

    /**
     * When this conversation is due to be purged. Computed rather than read from the database:
     * the scheduled function works off last activity, so deriving it here keeps the countdown
     * the agent sees and the deletion the server performs based on the same number.
     */
    val expiresAt: Long
        get() = if (keepChat || lastActivityAt <= 0) 0 else lastActivityAt + RETENTION_MILLIS

    // `initials` used to live here. It is gone: every visitor is called "Website Visitor", so it
    // returned "WV" for all of them. PersonAvatar now derives a single letter from the name, or
    // the email when there is no name, and seeds the disc colour from the conversation id so two
    // visitors showing the same letter are still told apart.

    companion object {
        const val RETENTION_MILLIS = 24L * 60 * 60 * 1000
    }
}

@Immutable
data class ChatMessage(
    val id: String,
    val sender: String,
    val text: String,
    val createdAt: Long,
    val readAt: Long? = null,
    /**
     * Queued locally, not yet acknowledged by the API. The bubble is drawn immediately and
     * dimmed until the server echoes it back over RTDB under the same id.
     */
    val pending: Boolean = false,
) {
    // "agent" is the wire value for "written from the app", read by the deployed widget and by
    // existing chat history. It is message authorship, not a role.
    val fromOwner: Boolean get() = sender == "agent"

    /** Connection confirmations and the like: centred, not a bubble on either side. */
    val isSystem: Boolean get() = sender == "system"
}

/**
 * The tenant document: tenants/{tenantId} in Firestore, one to one with these fields.
 *
 * A tenant has exactly one owner, so ownerUid is the whole authorisation story. There is no role
 * to carry and no membership record to check: rules compare ownerUid against auth.uid directly.
 */
@Immutable
data class TenantProfile(
    val tenantId: String = "",
    val ownerUid: String = "",
    val companyName: String = "",
    val ownerName: String = "",
    val email: String = "",
    val phone: String = "",
    val logoUrl: String? = null,
    val website: String? = null,
    val plan: String = "free",
    val active: Boolean = true,
)

/**
 * Filter chips over the inbox. Values map onto the existing status/assignedAgentUid fields.
 *
 * Pending is new and sits first: it is the queue the owner actually has to act on.
 */
enum class InboxFilter(val label: String) {
    All("All"),
    Pending("Pending"),
    Assigned("Assigned"),
    Unassigned("Unassigned"),
    Closed("Closed"),
}

/** Async wrapper so every screen can render a skeleton while data is in flight. */
sealed interface Async<out T> {
    data object Loading : Async<Nothing>
    data class Ready<T>(val value: T) : Async<T>
    data class Failed(val message: String) : Async<Nothing>
}

fun <T> Async<T>.valueOrNull(): T? = (this as? Async.Ready<T>)?.value
