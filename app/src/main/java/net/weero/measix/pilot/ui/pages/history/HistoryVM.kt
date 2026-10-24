package net.weero.measix.pilot.ui.pages.history

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import net.weero.measix.pilot.data.datastore.ConversationSortOrder
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.ConversationApplicationService
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.service.ConversationSummary
import net.weero.measix.pilot.service.AssistantCatalogReadState
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.utils.userVisibleDiagnostic

private const val TAG = "HistoryVM"

class HistoryVM internal constructor(
    private val conversationQueryService: ConversationQueryService,
    configurationQueryService: ConfigurationQueryService,
    private val conversationApplicationService: ConversationApplicationService,
) : ViewModel() {
    internal val assistantCatalog = configurationQueryService.observeAssistantCatalog()
        .stateIn(viewModelScope, SharingStarted.Eagerly, AssistantCatalogReadState.Loading)
    private val _readFailure = MutableStateFlow<String?>(null)
    internal val readFailure = _readFailure.asStateFlow()
    private val refresh = MutableStateFlow(0)
    fun retry() { refresh.value += 1 }
    private val sortRead = refresh.flatMapLatest {
        conversationQueryService.observeSortOrder().map<ConversationSortOrder, Result<ConversationSortOrder>?> { Result.success(it) }
            .onStart { emit(null) }
            .catch { error ->
                if (error is CancellationException) throw error
                Log.e(TAG, "Conversation sort preference read failed", error)
                emit(Result.failure(error))
            }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    internal val sortOrder = sortRead.map { it?.getOrNull() }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val conversations = combine(conversationQueryService.observeCurrentSelection(), sortRead, refresh) { selection, sort, _ ->
        selection to sort
    }.flatMapLatest { (selection, sort) ->
        (when {
            sort?.isFailure == true -> flowOf(Result.failure(requireNotNull(sort.exceptionOrNull())))
            selection != null && sort != null -> conversationQueryService.conversationsInRealm(selection, sort.getOrThrow())
            else -> flowOf(Result.success(emptyList()))
        })
            .catch { error ->
                if (error is CancellationException) throw error
                Log.e(TAG, "Conversation history query failed", error)
                emit(Result.failure(error))
            }
    }.map { result ->
        _readFailure.value = result.exceptionOrNull()?.userVisibleDiagnostic()
        result.getOrDefault(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _running = MutableStateFlow(false)
    private val commandMutex = Mutex()
    private var pendingCommands = 0
    internal val running = _running.asStateFlow()
    private data class CommandFailure(val selection: RealmSelection, val detail: String)
    private val _commandFailure = MutableStateFlow<CommandFailure?>(null)
    internal val commandFailure = combine(_commandFailure, conversationQueryService.observeCurrentSelection()) { failure, selection ->
        failure?.takeIf { it.selection == selection }?.detail
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _continuationUnavailable = MutableStateFlow<RealmSelection?>(null)
    internal val continuationUnavailable = combine(_continuationUnavailable, conversationQueryService.observeCurrentSelection()) { original, selection ->
        original != null && original == selection
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    internal data class DeletedNavigation(
        val receipt: net.weero.measix.pilot.service.ConversationDeletionReceipt,
        val continuation: net.weero.measix.pilot.service.ConversationContinuation,
    )
    private val _deletedNavigation = MutableStateFlow<List<DeletedNavigation>>(emptyList())
    internal val deletedNavigation = _deletedNavigation.asStateFlow()
    private val _undo = MutableStateFlow<ConversationApplicationService.RestoreToken?>(null)
    internal val undo = _undo.asStateFlow()
    internal data class PendingDeletion(
        val conversations: List<ConversationSummary>,
        val targets: List<net.weero.measix.pilot.service.ConversationCommandTarget>,
        val preview: net.weero.measix.pilot.service.ConversationDeletionPreview,
        val allowUndo: Boolean,
    )
    private val _pendingDeletion = MutableStateFlow<PendingDeletion?>(null)
    internal val pendingDeletion = _pendingDeletion.asStateFlow()
    init {
        viewModelScope.launch {
            conversationQueryService.observeCurrentSelection().collect { selection ->
                _undo.value?.takeIf { it.selection != selection }?.let(::discardRestoreToken)
                _pendingDeletion.value?.takeIf { it.conversations.first().commandTarget.selection != selection }
                    ?.let { _pendingDeletion.compareAndSet(it, null) }
            }
        }
    }

    private fun command(selection: RealmSelection, waitIfBusy: Boolean = false, operation: suspend () -> Unit): Job? {
        if (_running.value && !waitIfBusy) return null
        pendingCommands++
        _running.value = true
        return viewModelScope.launch {
            try {
                commandMutex.withLock {
                    _commandFailure.value = null
                    _continuationUnavailable.value = null
                    operation()
                }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                net.weero.measix.pilot.utils.logDiagnosticFailure(TAG, "Conversation history command failed", error)
                _commandFailure.value = CommandFailure(selection, error.userVisibleDiagnostic())
            } finally {
                pendingCommands--
                _running.value = pendingCommands > 0
            }
        }
    }

    private suspend fun deleted(receipt: net.weero.measix.pilot.service.ConversationDeletionReceipt) {
        if (conversationQueryService.observeCurrentSelection().first() != receipt.selection) return
        receipt.maintenanceFailure?.let { _commandFailure.value = CommandFailure(receipt.selection, it.userVisibleDiagnostic()) }
        val continuation = try { conversationApplicationService.deletionContinuation(receipt) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            net.weero.measix.pilot.utils.logDiagnosticFailure(TAG, "Deleted conversation continuation failed", error)
            _commandFailure.value = CommandFailure(receipt.selection, error.userVisibleDiagnostic())
            net.weero.measix.pilot.service.ConversationContinuation(null, "conversation_assistant_unavailable")
        }
        if (continuation.unavailableReason != null) _continuationUnavailable.value = receipt.selection
        _deletedNavigation.value += DeletedNavigation(receipt, continuation)
    }

    internal fun applyDeletedNavigation(expected: DeletedNavigation, apply: () -> Unit) {
        viewModelScope.launch {
            if (expected !in _deletedNavigation.value) return@launch
            if (conversationQueryService.observeCurrentSelection().first() == expected.receipt.selection) apply()
            _deletedNavigation.value -= expected
        }
    }

    fun deleteForUndo(conversation: ConversationSummary) = requestDeletion(listOf(conversation), allowUndo = true)

    private suspend fun deleteForUndoConfirmed(target: net.weero.measix.pilot.service.ConversationCommandTarget,
        preview: net.weero.measix.pilot.service.ConversationDeletionPreview) {
        val token = conversationApplicationService.deleteForUndo(target, preview)
        try {
            deleted(token.deletion)
            if (conversationQueryService.observeCurrentSelection().first() == token.selection) {
                _undo.value?.close()
                _undo.value = token
            } else token.close()
        } catch (error: Throwable) { token.close(); throw error }
    }

    fun deleteConversations(conversations: List<ConversationSummary>) { requestDeletion(conversations, allowUndo = false) }

    private fun requestDeletion(conversations: List<ConversationSummary>, allowUndo: Boolean): Job? {
        val captured = conversations.toList()
        val selection = captured.firstOrNull()?.commandTarget?.selection ?: return null
        if (_pendingDeletion.value != null) return null
        val targets = captured.map { it.commandTarget }
        return command(selection) {
            val preview = conversationApplicationService.prepareDeletion(targets)
            if (conversationQueryService.observeCurrentSelection().first() != selection) return@command
            val pending = PendingDeletion(captured, targets, preview, allowUndo)
            if (preview.pendingInputCount > 0) _pendingDeletion.value = pending
            else deleteConfirmed(pending)
        }
    }

    internal fun dismissDeletion(expected: PendingDeletion) { _pendingDeletion.compareAndSet(expected, null) }

    internal fun confirmDeletion(expected: PendingDeletion) {
        if (_running.value) return
        if (!_pendingDeletion.compareAndSet(expected, null)) return
        command(expected.conversations.first().commandTarget.selection) { deleteConfirmed(expected) }
    }

    private suspend fun deleteConfirmed(pending: PendingDeletion) {
        if (pending.allowUndo) deleteForUndoConfirmed(pending.targets.single(), pending.preview)
        else conversationApplicationService.deleteConversations(pending.targets, pending.preview, ::deleted)
    }

    fun togglePinStatus(conversation: ConversationSummary) = command(conversation.commandTarget.selection) {
        conversationApplicationService.togglePin(conversation.commandTarget)
    }

    fun restoreConversation(token: ConversationApplicationService.RestoreToken) {
        if (!_undo.compareAndSet(token, null)) return
        // Accept the action before waiting, so a later deletion cannot close this token.
        command(token.selection, waitIfBusy = true) {
            if (conversationQueryService.observeCurrentSelection().first() == token.selection) {
                conversationApplicationService.restore(token)
            }
        }!!.invokeOnCompletion { token.close() }
    }

    fun discardRestoreToken(token: ConversationApplicationService.RestoreToken) {
        if (_undo.compareAndSet(token, null)) token.close()
    }

    override fun onCleared() {
        _undo.value?.close()
        super.onCleared()
    }
}
