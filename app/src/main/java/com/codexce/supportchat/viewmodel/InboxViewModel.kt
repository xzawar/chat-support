package com.codexce.supportchat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codexce.supportchat.data.SupportRepository
import com.codexce.supportchat.data.model.Async
import com.codexce.supportchat.data.model.Conversation
import com.codexce.supportchat.data.model.InboxFilter
import com.codexce.supportchat.data.model.valueOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * How long a deleted chat can be brought back.
 *
 * Tied to the toast rather than chosen independently: AppToast holds a message for 3000ms and
 * then spends 260ms animating it out. The delete must not reach the server while the Undo button
 * is still on screen and still tappable, so this is that total plus a margin for the frame the
 * exit animation lands on. Shortening AppToast is harmless; lengthening it past this would leave
 * Undo on screen after the row is already gone from the server.
 */
private const val UNDO_MILLIS = 3_400L

/**
 * Deliberately not viewModelScope.
 *
 * The point of deferring the delete is that it happens later, and "later" easily falls after the
 * agent has changed tabs and taken this ViewModel down with them. A delete parented to
 * viewModelScope would be cancelled at exactly that moment and the chat would quietly return on
 * the next launch.
 */
private val deferredDeletes = CoroutineScope(SupervisorJob() + Dispatchers.Default)

data class InboxUiState(
    val source: Async<List<Conversation>> = Async.Loading,
    val visible: List<Conversation> = emptyList(),
    val counts: Map<InboxFilter, Int> = emptyMap(),
    val filter: InboxFilter = InboxFilter.All,
    val query: String = "",
    val actionError: String? = null,
) {
    val loading: Boolean get() = source is Async.Loading
    val loadError: String? get() = (source as? Async.Failed)?.message
}

class InboxViewModel(
    private val repository: SupportRepository,
    private val agentUid: String,
) : ViewModel() {

    private val filter = MutableStateFlow(InboxFilter.All)
    private val query = MutableStateFlow("")
    private val actionError = MutableStateFlow<String?>(null)

    /**
     * Chats swiped away but not yet deleted on the server.
     *
     * This is the optimistic-hide layer. The row has to leave the list the instant the swipe
     * lands, or the Undo toast would be offering to undo something visibly still there, but the
     * record itself must survive until the window closes so Undo has something to restore.
     * Holding ids here rather than pruning Room keeps the repository the single source of truth:
     * if the delete later fails, dropping the id is the entire rollback.
     */
    private val pendingDeletes = MutableStateFlow<Set<String>>(emptySet())

    /** One outstanding timer per pending id, so Undo cancels exactly the right one. */
    private val deleteTimers = mutableMapOf<String, Job>()

    val state: StateFlow<InboxUiState> = combine(
        repository.conversations(),
        filter,
        query,
        actionError,
        pendingDeletes,
    ) { source, activeFilter, search, error, pending ->
        // Pending deletes are filtered out before the counts are taken as well as before the
        // list, so a swiped chat does not keep inflating the badge on its filter tab.
        val all = (source.valueOrNull() ?: emptyList()).filter { it.id !in pending }
        InboxUiState(
            source = source,
            visible = all.filter { matches(it, activeFilter, search) },
            counts = InboxFilter.entries.associateWith { candidate ->
                all.count { matches(it, candidate, search) }
            },
            filter = activeFilter,
            query = search,
            actionError = error,
        )
    /*
     * Filtering, case-insensitive search and the five count passes all scale with the number of
     * conversations. viewModelScope defaults to Main, so doing this here used to compete with
     * Compose for a frame whenever Firebase supplied a large list or the search text changed.
     * Keep state delivery on Main, but run the upstream calculation on Default's worker pool.
     */
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InboxUiState())

    /** Filters map onto the fields the backend already has - no new status field. */
    private fun matches(
        conversation: Conversation,
        activeFilter: InboxFilter,
        search: String,
    ): Boolean {
        val matchesSearch = search.isBlank() || listOf(
            conversation.visitorName,
            conversation.visitorEmail,
            conversation.lastText,
        ).any { it.contains(search, ignoreCase = true) }

        val matchesFilter = when (activeFilter) {
            InboxFilter.All -> true
            // The queue that needs acting on: a visitor has asked for support and nobody has
            // hit Start Chat yet.
            InboxFilter.Pending -> conversation.isPending
            InboxFilter.Assigned -> conversation.assignedAgentUid == agentUid
            InboxFilter.Unassigned ->
                conversation.assignedAgentUid == null && !conversation.isClosed
            InboxFilter.Closed -> conversation.isClosed
        }
        return matchesSearch && matchesFilter
    }

    fun setFilter(value: InboxFilter) { filter.value = value }

    fun setQuery(value: String) { query.value = value }

    fun dismissError() { actionError.value = null }

    /**
     * Swipe-to-delete, deferred so it can be undone.
     *
     * The row disappears immediately and the server is not told for [UNDO_MILLIS]. Previously the
     * delete went out the moment the swipe landed, which is why an Undo button would have had
     * nothing left to undo.
     *
     * Swiping the same chat twice restarts its timer rather than queueing a second delete.
     *
     * Assign / Close / Reopen used to live here too. Assignment is automatic now (Start Chat
     * does it) and status is managed on the visitor profile, so the row has no actions left
     * apart from this one.
     */
    fun delete(conversation: Conversation) {
        val id = conversation.id
        deleteTimers.remove(id)?.cancel()
        pendingDeletes.value = pendingDeletes.value + id
        deleteTimers[id] = deferredDeletes.launch {
            delay(UNDO_MILLIS)
            commitDelete(id)
        }
    }

    /** Cancels the pending delete and puts the row back. */
    fun undoDelete(conversationId: String) {
        deleteTimers.remove(conversationId)?.cancel()
        pendingDeletes.value = pendingDeletes.value - conversationId
    }

    /**
     * The repository deletes remotely first and only then prunes Room, so a rejected delete
     * leaves the row in place with an error instead of vanishing and reappearing on the next
     * launch. Clearing the pending id is therefore both the cleanup and the rollback: on success
     * the row has already left the repository, and on failure it comes straight back.
     */
    private suspend fun commitDelete(conversationId: String) {
        val failure = repository.deleteConversation(conversationId)
        deleteTimers.remove(conversationId)
        pendingDeletes.value = pendingDeletes.value - conversationId
        if (failure != null) actionError.value = failure
    }

    /**
     * Leaving the inbox commits any outstanding delete rather than abandoning it.
     *
     * Letting the timer die with the ViewModel would mean a chat the agent swiped away returns if
     * they change tabs inside the undo window. Committing early costs them the rest of the
     * window, but they have already navigated away from the Undo button, so there was no way
     * left to use it.
     *
     * A process death inside the window is the one case that still loses the delete: nothing has
     * been written anywhere yet, so the chat is simply still there on next launch. That is the
     * safe direction to fail in - a chat that survives is recoverable, one deleted early is not.
     */
    override fun onCleared() {
        super.onCleared()
        val outstanding = pendingDeletes.value
        deleteTimers.values.forEach { it.cancel() }
        deleteTimers.clear()
        outstanding.forEach { id ->
            deferredDeletes.launch { repository.deleteConversation(id) }
        }
    }

    /**
     * Reading a pending chat must not connect it, so this clears the badge only - it does not
     * touch status or assignment.
     */
    fun markOpened(conversation: Conversation) = viewModelScope.launch {
        if (conversation.unreadForAgents > 0) {
            actionError.value = repository.clearAgentUnread(conversation.id)
        }
    }
}
