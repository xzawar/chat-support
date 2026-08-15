package com.codexce.supportchat.data

import android.content.Context
import com.codexce.supportchat.data.api.ApiException
import com.codexce.supportchat.data.api.SupportApi
import com.codexce.supportchat.data.local.OutboxEntity
import com.codexce.supportchat.data.local.SupportDao
import com.codexce.supportchat.data.local.SupportDatabase
import com.codexce.supportchat.data.local.toDomain
import com.codexce.supportchat.data.local.toEntity
import com.codexce.supportchat.data.model.Async
import com.codexce.supportchat.data.model.ChatMessage
import com.codexce.supportchat.data.model.Conversation
import com.codexce.supportchat.data.model.ConversationStatus
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The database lives in asia-southeast1, so the URL must be passed explicitly to
 * FirebaseDatabase.getInstance().
 */
const val DATABASE_URL =
    "https://chat-support-1-default-rtdb.asia-southeast1.firebasedatabase.app"

/** How many messages the thread pulls on open, and how many more each scroll-up page adds. */
const val MESSAGE_PAGE_SIZE = 40

/**
 * How many expired conversations one retention sweep deletes.
 *
 * A cap rather than one giant write: every entry removes a conversation and its whole message
 * thread, and RTDB rejects an update that grows past its request limit. A backlog that exceeds
 * this is cleared over consecutive sweeps instead of failing as a single oversized write.
 */
private const val SWEEP_BATCH = 200

/**
 * Chat data layer, offline-first, read-only against Firebase.
 *
 * Shape: RTDB listener -> Room -> Flow -> ViewModel -> UI, exactly as before. What changed in
 * 4.2 is the direction of writes and the shape of the tree:
 *
 * - Reads live under chats/{tenantId}/... and are scoped by the tenantId custom claim. The
 *   database rules are listen-only, so a stray client write cannot even be attempted.
 * - Every write is an authenticated call to the backend. The server owns the multi-path update
 *   (message + lastMessage + unread) so a message and its conversation summary can never drift.
 * - Sends go through a durable Room outbox first. clientMessageId is both the local primary key
 *   and the RTDB child key server-side, which makes a retry an overwrite rather than a
 *   duplicate. That is what keeps the offline queue safe now that the client cannot write.
 *
 * The public surface below is unchanged, so the inbox, thread and profile screens did not have
 * to learn anything about any of this.
 */
class SupportRepository(
    private val root: DatabaseReference,
    private val tenantId: String,
    private val dao: SupportDao,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val chats get() = root.child("chats").child(tenantId)
    private fun conversationsRef() = chats.child("conversations")
    private fun messagesRef(conversationId: String) = chats.child("messages").child(conversationId)

    /**
     * Conversations already handed to a delete, so a sweep is never issued twice for the same row.
     *
     * Necessary because the sweep is driven by snapshots, and a snapshot arrives again the moment
     * the delete lands. Without this, a slow round-trip means the next snapshot still contains the
     * expired row and fires a second, redundant delete for it.
     *
     * Ids are kept after a successful delete rather than removed. They cannot come back - RTDB push
     * ids are never reused - and holding them is what stops the echo re-queueing work. Ids are
     * dropped again only when the delete FAILED, so a sweep blocked by a dropped connection is
     * retried on the next snapshot instead of being abandoned for the lifetime of the process.
     */
    private val sweeping = ConcurrentHashMap.newKeySet<String>()

    /**
     * Deletes expired conversations and their threads.
     *
     * This is the retention sweep. It runs on the client because the scheduled Cloud Function that
     * should do it needs the Blaze plan; on the free plan the owner's app is the only party that is
     * both authorised to delete and certain to run. database.rules.json already grants the owner
     * write access to both nodes, so no rules change is required.
     *
     * Conversation and thread go in ONE update, exactly as SupportApi.deleteConversation does, so a
     * failure cannot leave a thread of orphaned messages behind under a conversation that is gone.
     *
     * Room is only pruned after the server accepts the delete. Pruning first would make a rejected
     * delete look successful until the next launch, which is the "it comes back after reopening"
     * failure this repository already avoids in deleteConversation.
     */
    private suspend fun sweepExpired(conversationIds: List<String>) {
        val fresh = conversationIds.filter { sweeping.add(it) }
        if (fresh.isEmpty()) return

        for (batch in fresh.chunked(SWEEP_BATCH)) {
            try {
                val updates = HashMap<String, Any?>(batch.size * 2)
                for (id in batch) {
                    updates["conversations/$id"] = null
                    updates["messages/$id"] = null
                }
                chats.updateChildren(updates).await()
            } catch (cancellation: CancellationException) {
                // The inbox was closed mid-sweep. Not a failure, but the work did not finish, so
                // let the next sweep pick these up.
                sweeping.removeAll(batch.toSet())
                throw cancellation
            } catch (error: Exception) {
                // Offline, or rules refused. Retention is hygiene, not something worth an error in
                // front of the user, so it stays silent and is retried on a later snapshot.
                sweeping.removeAll(batch.toSet())
                return
            }

            withContext(io) {
                for (id in batch) {
                    // Outbox first: a queued message for a conversation that no longer exists can
                    // never be delivered, and leaving it would retry forever against a dead id.
                    dao.clearOutboxFor(id)
                    dao.purgeMessages(id)
                    dao.deleteConversation(id)
                }
            }
        }
    }

    // ---- remote listeners: their only job is to feed Room ----

    private fun remoteConversations(): Flow<Async<List<Conversation>>> = callbackFlow {
        val ref = conversationsRef()
        // Paired with disk persistence in SupportChatApplication: the node stays cached
        // between listener attachments, so reopening the inbox is a local read plus a delta
        // rather than a full re-download.
        ref.keepSynced(true)
        // Ordered server-side by the indexed lastMessage/at, which is what .indexOn in
        // database.rules.json exists for.
        val query = ref.orderByChild("lastMessage/at")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Firebase's default Android callback executor is Main. Snapshot mapping can be
                // expensive on a busy inbox, so return from the callback immediately and parse
                // on the repository's IO dispatcher instead of blocking the next frame.
                launch(io) {
                    /*
                     * Expired rows are filtered out here and deleted behind the scenes.
                     *
                     * Filtering is what the user actually sees, and it is deliberately not
                     * conditional on the delete succeeding: an expired chat disappears from the
                     * inbox immediately even when offline or when the write is refused, so the app
                     * never shows a conversation it has promised to have removed. The delete then
                     * makes it true on the server. database.rules.json has always documented this
                     * behaviour ("the app filters expired rows out of the inbox and prunes them");
                     * until now nothing implemented it.
                     *
                     * The clock is read once per snapshot rather than per row, so every row in one
                     * pass is judged against the same instant.
                     */
                    val now = System.currentTimeMillis()
                    val live = ArrayList<Conversation>()
                    val expired = ArrayList<String>()

                    for (child in snapshot.children) {
                        val conversation = conversationFrom(child) ?: continue
                        val hasExpired = isRetentionExpired(
                            keepChat = conversation.keepChat,
                            // Read from the raw snapshot, NOT from Conversation.expiresAt: that
                            // property is derived from last activity, so it invents a deadline for
                            // rows that have none and would delete pinned chats. See Retention.kt.
                            storedExpiresAt = child.child("expiresAt").getValue(Long::class.java),
                            now = now,
                        )
                        if (hasExpired) expired += conversation.id else live += conversation
                    }

                    trySend(Async.Ready(live.sortedByDescending { it.lastActivityAt }))

                    // Launched separately so a slow or failing delete cannot hold up the emission
                    // above; the inbox is already correct without waiting for it.
                    if (expired.isNotEmpty()) launch(io) { sweepExpired(expired) }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                trySend(Async.Failed(error.message))
            }
        }
        query.addValueEventListener(listener)
        awaitClose { query.removeEventListener(listener) }
    }

    /**
     * Newest [limit] messages only, ordered by the indexed createdAt field rather than by key —
     * message keys are client message ids, so key order is not chronological and limitToLast on
     * the raw node would return an arbitrary slice.
     */
    private fun remoteMessages(
        conversationId: String,
        limit: Int,
    ): Flow<Async<List<ChatMessage>>> = callbackFlow {
        val query = messagesRef(conversationId)
            .orderByChild("createdAt")
            .limitToLast(limit)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // As with conversations, decode a message snapshot off the Firebase main callback
                // executor. This is especially important while a transition is in flight.
                launch(io) {
                    val list = snapshot.children.mapNotNull(::messageFrom)
                        .sortedBy { it.createdAt }
                    trySend(Async.Ready(list))
                }
            }

            override fun onCancelled(error: DatabaseError) {
                trySend(Async.Failed(error.message))
            }
        }
        query.addValueEventListener(listener)
        awaitClose { query.removeEventListener(listener) }
    }

    // ---- public reads: Room is the single source of truth ----

    /**
     * Loading is reported only until the first sync of this session completes AND the cache is
     * empty. A returning user with a populated cache goes straight to content, so the skeleton
     * does not flash on every launch.
     */
    fun conversations(): Flow<Async<List<Conversation>>> {
        // tenantId is unavailable for a moment after a returning user launches the app. Do not
        // turn that transient state into a real `/chats/unprovisioned` listener: rules reject it,
        // it produces noisy permission errors, and it needlessly starts Firebase work in the
        // critical first-render window.
        if (tenantId.isBlank()) return flowOf(Async.Loading)

        return channelFlow {
        val synced = MutableStateFlow(false)
        val remoteError = MutableStateFlow<String?>(null)

        launch {
            remoteConversations().collect { remote ->
                when (remote) {
                    is Async.Ready -> {
                        dao.replaceConversations(remote.value.map { it.toEntity() })
                        remoteError.value = null
                        synced.value = true
                    }

                    is Async.Failed -> {
                        remoteError.value = remote.message
                        synced.value = true
                    }

                    Async.Loading -> Unit
                }
            }
        }

        launch {
            combine(
                dao.observeConversations(),
                synced,
                remoteError,
            ) { rows, hasSynced, error ->
                when {
                    rows.isNotEmpty() -> Async.Ready(rows.map { it.toDomain() })
                    !hasSynced -> Async.Loading
                    error != null -> Async.Failed(error)
                    else -> Async.Ready(emptyList())
                }
            }.collect { send(it) }
        }
        }.flowOn(io)
    }

    /**
     * [limit] is raised by the conversation screen as the user scrolls up. Room keeps every page
     * that has been fetched, so widening the window never blanks the thread — the new listener
     * just tops the cache up. Queued (pending) rows are never pruned by a snapshot, so an
     * unsent message stays visible while the socket is down.
     */
    fun messages(
        conversationId: String,
        limit: Int = MESSAGE_PAGE_SIZE,
    ): Flow<Async<List<ChatMessage>>> {
        if (tenantId.isBlank()) return flowOf(Async.Loading)

        return channelFlow {
        val synced = MutableStateFlow(false)
        val remoteError = MutableStateFlow<String?>(null)

        launch {
            remoteMessages(conversationId, limit).collect { remote ->
                when (remote) {
                    is Async.Ready -> {
                        dao.replaceMessagePage(
                            conversationId = conversationId,
                            rows = remote.value.map { it.toEntity(conversationId) },
                            // Fewer rows than asked for means this is the entire thread, so a
                            // full prune is safe. A full page means older ones exist.
                            complete = remote.value.size < limit,
                        )
                        remoteError.value = null
                        synced.value = true
                    }

                    is Async.Failed -> {
                        remoteError.value = remote.message
                        synced.value = true
                    }

                    Async.Loading -> Unit
                }
            }
        }

        launch {
            combine(
                dao.observeMessages(conversationId),
                synced,
                remoteError,
            ) { rows, hasSynced, error ->
                when {
                    rows.isNotEmpty() -> Async.Ready(rows.map { it.toDomain() })
                    !hasSynced -> Async.Loading
                    error != null -> Async.Failed(error)
                    else -> Async.Ready(emptyList())
                }
            }.collect { send(it) }
        }
        }.flowOn(io)
    }

    /** One row, filtered in SQL, so the conversation screen ignores changes to other rows. */
    /**
     * One conversation, kept live.
     *
     * This used to read Room and nothing else. The only listener feeding conversation rows into
     * Room lives in [conversations], which is collected by the inbox — so while the thread was
     * on screen nothing was refreshing this row. Start Chat flipped the status on the server and
     * the open thread never heard about it, which is why the composer only appeared after going
     * back to the inbox and opening the chat again.
     *
     * Now the thread carries its own listener on its own node. Room stays the source of truth
     * the UI reads from; this just makes sure somebody is still writing to it.
     */
    fun conversation(conversationId: String): Flow<Conversation?> = channelFlow {
        if (tenantId.isNotBlank()) {
            launch {
                remoteConversation(conversationId).collect { live ->
                    if (live != null) {
                        withContext(io) { dao.upsertConversations(listOf(live.toEntity())) }
                    }
                }
            }
        }

        dao.observeConversation(conversationId)
            .map { it?.toDomain() }
            .flowOn(io)
            .collect { send(it) }
    }

    /** A value listener on a single conversation node. Feeds Room, like every other listener. */
    private fun remoteConversation(conversationId: String): Flow<Conversation?> = callbackFlow {
        val ref = conversationsRef().child(conversationId)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                launch(io) {
                    trySend(if (snapshot.exists()) conversationFrom(snapshot) else null)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                // The inbox listener surfaces read failures already; do not double-report here.
            }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    /** How many sends are still queued for this thread. Drives the "Sending…" hint. */
    fun queuedCount(conversationId: String): Flow<Int> =
        dao.observeOutboxCount(conversationId).flowOn(io)

    // ---- writes: all suspend, all report failure, all go through the backend ----

    /**
     * Start Chat. The server flips the status, stamps startedAt and assigns the caller in one
     * write, so assignment is still automatic and still atomic — it just happens behind the API
     * now instead of in a client multi-path update.
     *
     * [agentUid] and [agentName] are kept in the signature because the screens pass them, but
     * the server derives the agent from the verified token and ignores anything sent from here.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun startChat(
        conversation: Conversation,
        agentUid: String,
        agentName: String,
    ): String? = apiCall {
        SupportApi.setConversationStatus(conversation.id, ConversationStatus.OPEN)
    }

    /**
     * Keep Chat. The retention job skips any conversation with this set, which is the only
     * thing standing between a kept thread and the 24-hour purge.
     */
    suspend fun setKeepChat(conversationId: String, keep: Boolean): String? = apiCall {
        SupportApi.setKeepChat(conversationId, keep)
    }

    suspend fun setStatus(conversationId: String, status: String): String? = apiCall {
        SupportApi.setConversationStatus(conversationId, status)
    }

    suspend fun clearAgentUnread(conversationId: String): String? = apiCall {
        SupportApi.clearUnread(conversationId)
    }

    /**
     * Shared read receipt for phone and web. The browser writes the same RTDB fields, so opening
     * either surface clears the agent unread count and stamps unseen visitor messages as read.
     */
    suspend fun markVisitorMessagesRead(conversationId: String) = withContext(io) {
        if (tenantId.isBlank()) return@withContext
        runCatching {
            val messages = messagesRef(conversationId).get().await()
            val updates = mutableMapOf<String, Any>()
            val now = System.currentTimeMillis()
            for (row in messages.children) {
                if (row.child("sender").getValue(String::class.java) == "visitor" &&
                    !row.child("readAt").exists()
                ) {
                    updates["${row.key}/readAt"] = now
                }
            }
            if (updates.isNotEmpty()) messagesRef(conversationId).updateChildren(updates).await()
        }
    }

    /**
     * Deletes remotely first and only prunes Room once the server has accepted it. Removing the
     * local row first would make a rejected delete look successful until the next launch — the
     * exact failure mode reported as "it comes back after reopening".
     */
    suspend fun deleteConversation(conversationId: String): String? {
        val failure = apiCall { SupportApi.deleteConversation(conversationId) }
        if (failure == null) {
            withContext(io) {
                dao.clearOutboxFor(conversationId)
                dao.purgeMessages(conversationId)
                dao.deleteConversation(conversationId)
            }
        }
        return failure
    }

    /**
     * Queues the message, paints it immediately, then tries to flush.
     *
     * A network failure is not an error the agent needs to see: the row stays in the outbox and
     * goes out on the next flush. Only a refusal the server will keep refusing (a closed chat, a
     * lapsed subscription, a message over the length limit) is surfaced and dropped from the
     * queue, because retrying it forever would be dishonest.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun sendMessage(
        conversation: Conversation,
        agentUid: String,
        text: String,
    ): String? {
        val body = text.trim()
        if (body.isEmpty()) return null

        val clientMessageId = newClientMessageId()
        withContext(io) {
            dao.enqueueOutbox(
                OutboxEntity(
                    clientMessageId = clientMessageId,
                    conversationId = conversation.id,
                    text = body,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        return flushOne(clientMessageId, conversation.id, body)
    }

    /**
     * Drains the queue. Called when the thread opens, when the app comes back to the foreground
     * and after every successful send, so a message typed on a dead connection leaves as soon as
     * there is one.
     */
    suspend fun flushOutbox(): Int = withContext(io) {
        var sent = 0
        for (row in dao.pendingOutbox()) {
            val failure = flushOne(row.clientMessageId, row.conversationId, row.text)
            if (failure == null) sent += 1
        }
        sent
    }

    private suspend fun flushOne(
        clientMessageId: String,
        conversationId: String,
        text: String,
    ): String? = withContext(io) {
        try {
            SupportApi.sendMessage(conversationId, clientMessageId, text)
            dao.settleOutbox(clientMessageId)
            null
        } catch (error: ApiException) {
            if (error.isPermanent()) {
                dao.discardOutbox(clientMessageId)
                error.message
            } else {
                dao.markOutboxFailure(clientMessageId, error.message)
                null
            }
        } catch (error: Throwable) {
            dao.markOutboxFailure(clientMessageId, error.localizedMessage)
            null
        }
    }

    /** Called on sign-out so the next account never inherits this one's cached rows. */
    suspend fun clearCache() = dao.clearAll()

    private suspend fun apiCall(block: suspend () -> Unit): String? = withContext(io) {
        try {
            block()
            null
        } catch (error: ApiException) {
            error.message
        } catch (error: Throwable) {
            error.localizedMessage ?: "Request failed"
        }
    }

    companion object {
        /**
         * [tenantId] comes from the verified workspace session, never from user input. Keep an
         * absent value blank: conversations() and messages() recognise it as a loading state and
         * do not create an RTDB child/listener until a real workspace id arrives.
         */
        fun create(context: Context, tenantId: String): SupportRepository = SupportRepository(
            root = FirebaseDatabase.getInstance(DATABASE_URL).reference,
            tenantId = tenantId,
            dao = SupportDatabase.get(context).supportDao(),
        )
    }
}

/**
 * The backend validates clientMessageId against /^[A-Za-z0-9_-]+$/ because it becomes an RTDB
 * key, so the UUID's dashes are fine but nothing else is left to chance.
 */
private fun newClientMessageId(): String =
    UUID.randomUUID().toString().replace(Regex("[^A-Za-z0-9_-]"), "")

/**
 * 4xx answers other than auth, timeout and rate limiting will not change on a retry, so the
 * queued message is dropped rather than replayed forever.
 */
private fun ApiException.isPermanent(): Boolean = when {
    status == 0 -> false
    status == 401 -> false
    status == 408 -> false
    status == 429 -> false
    status in 400..499 -> true
    else -> false
}

/**
 * Snapshot mapping. The status default stays "open", not "pending": a conversation the widget
 * wrote before this field existed has no explicit status, and defaulting those to pending would
 * dump the whole inbox into the Start Chat queue.
 *
 * `unread` is the field the backend writes now; `unreadForAgents` is read as a fallback so a
 * tree written by the previous version still renders correctly.
 */
private fun conversationFrom(snapshot: DataSnapshot): Conversation? {
    val id = snapshot.key ?: return null
    val unread = snapshot.child("unread").getValue(Long::class.java)
        ?: snapshot.child("unreadForAgents").getValue(Long::class.java)
        ?: 0
    return Conversation(
        id = id,
        visitorName = snapshot.child("visitor/name").getValue(String::class.java) ?: "Visitor",
        visitorEmail = snapshot.child("visitor/email").getValue(String::class.java) ?: "",
        pageUrl = snapshot.child("visitor/pageUrl").getValue(String::class.java)
            ?: snapshot.child("websiteDomain").getValue(String::class.java) ?: "",
        status = snapshot.child("status").getValue(String::class.java)
            ?: ConversationStatus.OPEN,
        assignedAgentUid = snapshot.child("assignedAgentUid").getValue(String::class.java),
        lastText = snapshot.child("lastMessage/text").getValue(String::class.java) ?: "",
        lastAt = snapshot.child("lastMessage/at").getValue(Long::class.java) ?: 0,
        unreadForAgents = unread,
        unreadForVisitor = snapshot.child("unreadForVisitor").getValue(Long::class.java) ?: 0,
        keepChat = snapshot.child("keepChat").getValue(Boolean::class.java) ?: false,
        createdAt = snapshot.child("createdAt").getValue(Long::class.java) ?: 0,
        startedAt = snapshot.child("startedAt").getValue(Long::class.java) ?: 0,
        userAgent = snapshot.child("visitor/userAgent").getValue(String::class.java) ?: "",
        country = snapshot.child("visitor/country").getValue(String::class.java) ?: "",
    )
}

private fun messageFrom(snapshot: DataSnapshot): ChatMessage? {
    val id = snapshot.key ?: return null
    val text = snapshot.child("text").getValue(String::class.java) ?: return null
    return ChatMessage(
        id = id,
        sender = snapshot.child("sender").getValue(String::class.java) ?: "visitor",
        text = text,
        createdAt = snapshot.child("createdAt").getValue(Long::class.java) ?: 0,
        readAt = snapshot.child("readAt").getValue(Long::class.java),
        pending = false,
    )
}
