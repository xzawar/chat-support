package com.codexce.supportchat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codexce.supportchat.data.MESSAGE_PAGE_SIZE
import com.codexce.supportchat.data.SupportRepository
import com.codexce.supportchat.data.model.Async
import com.codexce.supportchat.data.model.ChatMessage
import com.codexce.supportchat.data.model.Conversation
import com.codexce.supportchat.data.model.ConversationStatus
import com.codexce.supportchat.data.model.valueOrNull
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConversationUiState(
    val conversation: Conversation? = null,
    val messages: Async<List<ChatMessage>> = Async.Loading,
    val draft: String = "",
    val error: String? = null,
    /** True while a wider page is being fetched, so the thread can show a top spinner. */
    val loadingOlder: Boolean = false,
    /** False once the server returns fewer messages than the window asked for. */
    val canLoadOlder: Boolean = false,
    val working: Boolean = false,
    /** Messages written locally that the server has not accepted yet. */
    val queued: Int = 0,
) {
    val loading: Boolean get() = messages is Async.Loading

    /** A pending conversation has to be started before anything can be typed into it. */
    val pending: Boolean get() = conversation?.isPending == true

    val canSend: Boolean
        get() = draft.isNotBlank() && conversation?.isClosed == false && !pending
}

/**
 * Reads one conversation row and its thread straight from Room. The Room query filters in SQL, so
 * the screen recomposes only when this conversation actually changes.
 *
 * The thread is paginated: the newest [MESSAGE_PAGE_SIZE] messages load first and the window
 * widens as the user scrolls up. Widening re-attaches the listener with a larger limit rather
 * than fetching a separate page, which keeps a single source of truth and means a message edited
 * or deleted anywhere in the loaded range still arrives live.
 */
class ConversationViewModel(
    private val repository: SupportRepository,
    private val conversationId: String,
    private val agentUid: String,
) : ViewModel() {

    private val draft = MutableStateFlow("")
    private val error = MutableStateFlow<String?>(null)
    private val pageSize = MutableStateFlow(MESSAGE_PAGE_SIZE)
    private val working = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val messages = pageSize.flatMapLatest { limit ->
        repository.messages(conversationId, limit)
    }

    val state: StateFlow<ConversationUiState> = combine(
        repository.conversation(conversationId),
        messages,
        draft,
        error,
        combine(pageSize, working, repository.queuedCount(conversationId)) { size, busy, queued ->
            Triple(size, busy, queued)
        },
    ) { conversation, thread, currentDraft, currentError, (limit, busy, queued) ->
        val loaded = thread.valueOrNull()?.size ?: 0
        ConversationUiState(
            conversation = conversation,
            messages = thread,
            draft = currentDraft,
            error = currentError ?: (thread as? Async.Failed)?.message,
            // A full window means the server had at least that many, so older ones may exist.
            loadingOlder = loaded < limit && limit > MESSAGE_PAGE_SIZE && thread is Async.Loading,
            canLoadOlder = loaded >= limit,
            working = busy,
            queued = queued,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

    init {
        viewModelScope.launch {
            repository.clearAgentUnread(conversationId)
            repository.markVisitorMessagesRead(conversationId)
        }
        // Anything typed while offline is still sitting in the outbox. Opening the thread is the
        // most likely moment for the network to be back, so try it once here rather than waiting
        // for the next send to drag the backlog along behind it.
        flushQueue()
    }

    /**
     * Drains the outbox. Safe to call repeatedly: each row is keyed by its clientMessageId, so a
     * retry of a message the server already accepted overwrites the same child instead of
     * creating a second copy of it.
     */
    fun flushQueue() {
        viewModelScope.launch { runCatching { repository.flushOutbox() } }
    }

    /** Called when the thread scrolls near its oldest loaded message. */
    fun loadOlder() {
        if (!state.value.canLoadOlder) return
        pageSize.value += MESSAGE_PAGE_SIZE
    }

    fun setDraft(value: String) {
        if (value.length <= 4000) draft.value = value
    }

    fun dismissError() { error.value = null }

    fun send() {
        val conversation = state.value.conversation ?: return
        val text = draft.value.trim()
        if (text.isEmpty() || conversation.isClosed || conversation.isPending) return
        /*
         * Clear the field before the write, not after it.
         *
         * The field used to stay full until the network round trip came back, which on a slow
         * connection is a visible second or more of the text sitting there looking unsent. The
         * message is already on its way at this point, so the field has no reason to hold it.
         *
         * If the write fails the text is put back exactly as typed, so nothing is ever lost -
         * and it is only restored when the field is still empty, so a reply typed in the
         * meantime is not overwritten by a late failure.
         */
        draft.value = ""
        viewModelScope.launch {
            val failure = repository.sendMessage(conversation, agentUid, text)
            if (failure != null) {
                error.value = failure
                if (draft.value.isEmpty()) draft.value = text
            }
        }
    }

    /**
     * Start Chat. This is the only way a conversation becomes active, and it is also the only
     * thing that assigns it — opening a pending chat to read it does not connect the visitor.
     */
    fun startChat() {
        val conversation = state.value.conversation ?: return
        if (!conversation.isPending || working.value) return
        val name = FirebaseAuth.getInstance().currentUser?.displayName
            ?: FirebaseAuth.getInstance().currentUser?.email?.substringBefore('@')
            ?: "our support team"
        working.value = true
        viewModelScope.launch {
            error.value = repository.startChat(conversation, agentUid, name)
            working.value = false
        }
    }

    /** Exempts the thread from the 24-hour purge. Surfaced on the visitor profile. */
    fun setKeepChat(keep: Boolean) {
        val conversation = state.value.conversation ?: return
        viewModelScope.launch { error.value = repository.setKeepChat(conversation.id, keep) }
    }

    /**
     * Close / Reopen. Still here, but no longer reachable from the conversation header: the
     * visitor profile page is the one place conversation status is managed now.
     */
    fun toggleClosed() {
        val conversation = state.value.conversation ?: return
        viewModelScope.launch {
            error.value = repository.setStatus(
                conversation.id,
                if (conversation.isClosed) ConversationStatus.OPEN else ConversationStatus.CLOSED,
            )
        }
    }
}
