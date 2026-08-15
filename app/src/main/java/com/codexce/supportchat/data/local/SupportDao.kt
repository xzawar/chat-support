package com.codexce.supportchat.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Declared as an abstract class rather than an interface so the @Transaction methods can carry a
 * real body: a remote snapshot has to be applied as one atomic replace, otherwise the UI briefly
 * observes a half-written list.
 *
 * 4.2 adds the outbox. Because the client no longer writes RTDB, a sent message lives in two
 * places for a moment: an `outbox` row (the durable intent) and a `messages` row flagged
 * `pending` (what the thread draws). Every prune below now skips pending rows, otherwise the
 * next remote snapshot would erase the message the agent can still see typing on screen.
 */
@Dao
abstract class SupportDao {

    @Query("SELECT * FROM conversations ORDER BY lastAt DESC")
    abstract fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    abstract fun observeConversation(conversationId: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    abstract fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Upsert
    abstract suspend fun upsertConversations(rows: List<ConversationEntity>)

    @Upsert
    abstract suspend fun upsertMessages(rows: List<MessageEntity>)

    @Query("DELETE FROM conversations")
    abstract suspend fun clearConversations()

    @Query("DELETE FROM conversations WHERE id NOT IN (:keepIds)")
    abstract suspend fun pruneConversations(keepIds: List<String>)

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    abstract suspend fun deleteConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND pending = 0")
    abstract suspend fun clearMessages(conversationId: String)

    @Query(
        "DELETE FROM messages WHERE conversationId = :conversationId " +
            "AND pending = 0 AND id NOT IN (:keepIds)",
    )
    abstract suspend fun pruneMessages(conversationId: String, keepIds: List<String>)

    /**
     * Prunes only inside the window the remote page actually covers. Paginated loading means a
     * snapshot is the newest N messages, not the whole thread, so pruning everything absent from
     * it would delete the older pages the user just scrolled up to fetch.
     */
    @Query(
        "DELETE FROM messages WHERE conversationId = :conversationId " +
            "AND pending = 0 AND createdAt >= :fromCreatedAt AND id NOT IN (:keepIds)",
    )
    abstract suspend fun pruneMessagesFrom(
        conversationId: String,
        fromCreatedAt: Long,
        keepIds: List<String>,
    )

    @Query("DELETE FROM messages")
    abstract suspend fun clearAllMessages()

    /** Wipes a thread outright, pending rows included. Used when the conversation is deleted. */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    abstract suspend fun purgeMessages(conversationId: String)

    // ------------------------------------------------------------------ outbox

    @Query("SELECT * FROM outbox ORDER BY createdAt ASC")
    abstract suspend fun pendingOutbox(): List<OutboxEntity>

    @Query("SELECT COUNT(*) FROM outbox WHERE conversationId = :conversationId")
    abstract fun observeOutboxCount(conversationId: String): Flow<Int>

    @Upsert
    abstract suspend fun upsertOutbox(row: OutboxEntity)

    @Query("DELETE FROM outbox WHERE clientMessageId = :clientMessageId")
    abstract suspend fun deleteOutbox(clientMessageId: String)

    @Query("DELETE FROM outbox WHERE conversationId = :conversationId")
    abstract suspend fun clearOutboxFor(conversationId: String)

    @Query("DELETE FROM outbox")
    abstract suspend fun clearOutbox()

    @Query(
        "UPDATE outbox SET attempts = attempts + 1, lastError = :error " +
            "WHERE clientMessageId = :clientMessageId",
    )
    abstract suspend fun markOutboxFailure(clientMessageId: String, error: String?)

    @Query("UPDATE messages SET pending = 0 WHERE id = :messageId")
    abstract suspend fun markMessageSettled(messageId: String)

    /** Queues a send and paints it optimistically in the same transaction. */
    @Transaction
    open suspend fun enqueueOutbox(row: OutboxEntity) {
        upsertOutbox(row)
        upsertMessages(listOf(row.toPendingMessage()))
    }

    /**
     * The API accepted the send. The row leaves the queue and stops being pending; the RTDB
     * listener will shortly overwrite it with the authoritative server copy under the same id.
     */
    @Transaction
    open suspend fun settleOutbox(clientMessageId: String) {
        deleteOutbox(clientMessageId)
        markMessageSettled(clientMessageId)
    }

    /** Drops a send the agent gave up on, together with its optimistic bubble. */
    @Transaction
    open suspend fun discardOutbox(clientMessageId: String) {
        deleteOutbox(clientMessageId)
        deleteMessage(clientMessageId)
    }

    @Query("DELETE FROM messages WHERE id = :messageId")
    abstract suspend fun deleteMessage(messageId: String)

    // ------------------------------------------------------------- transactions

    /** Applies a full remote snapshot: upsert what is there, drop what is not. */
    @Transaction
    open suspend fun replaceConversations(rows: List<ConversationEntity>) {
        if (rows.isEmpty()) {
            clearConversations()
        } else {
            upsertConversations(rows)
            pruneConversations(rows.map { it.id })
        }
    }

    /**
     * Applies one page of the thread: upsert what arrived, and drop only rows inside the same
     * time window that the server no longer has (a deleted message). Anything older than the
     * page is left alone.
     *
     * [complete] is true when the page is smaller than the requested limit, meaning the server
     * returned the whole thread and a full prune is safe.
     */
    @Transaction
    open suspend fun replaceMessagePage(
        conversationId: String,
        rows: List<MessageEntity>,
        complete: Boolean,
    ) {
        if (rows.isEmpty()) {
            if (complete) clearMessages(conversationId)
            return
        }
        upsertMessages(rows)
        val ids = rows.map { it.id }
        if (complete) {
            pruneMessages(conversationId, ids)
        } else {
            pruneMessagesFrom(conversationId, rows.minOf { it.createdAt }, ids)
        }
    }

    /** Used on sign-out so the next account never sees the previous one's cache. */
    @Transaction
    open suspend fun clearAll() {
        clearOutbox()
        clearAllMessages()
        clearConversations()
    }
}
