package net.weero.measix.pilot.service.runtime
import net.weero.measix.pilot.data.ai.mcp.McpExecutionLease

import net.weero.measix.pilot.service.turn.TurnContext
import net.weero.measix.pilot.service.turn.TurnFinalizer

import me.rerere.common.configuration.ConfigurationReference
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.enterprise.RealmAccess
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

private const val TAG = "ConversationRuntime"
private const val IDLE_TIMEOUT_MS = 5_000L

internal data class InstalledTurnWorker(
    val turnId: Uuid,
    val previousTurnId: Uuid?,
    val previousWorker: Job?,
)

internal data class CapturedTurnWorker(
    val turnId: Uuid,
    val worker: Job?,
    val pending: CapturedTurnWorker? = null,
)

internal data class ActiveTurnPresentationFacts(
    val turnId: Uuid?,
    val phase: TurnLivePhase?,
    val handle: TurnHandle?,
    val processingText: String?,
)

/**
 * Resident conversation projection. Durable commands are planned and committed by
 * [ConversationCommandCoordinator]; this runtime only publishes snapshots, streaming
 * deltas and one private active turn.
 */
class ConversationRuntime internal constructor(
    val id: Uuid,
    initial: ConversationAggregateSnapshot,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
    draftAssets: ConversationDraft? = null,
) {
    private val ownedDraft = AtomicReference(draftAssets)

    private val _inputQueue = MutableStateFlow(ConversationInputQueueSnapshot())
    internal val inputQueue: StateFlow<ConversationInputQueueSnapshot> = _inputQueue.asStateFlow()
    private var inputQueueLease: ConversationRuntimeLease? = null
    private val inputEditCleanups = mutableMapOf<Uuid, () -> Unit>()
    private var inputContextRevision = 0L
    // releaseTurnWorker can retire a request inside finally, before its Job actually completes.
    private var lastInstalledWorker: Job? = null
    // A completed Job is not dispatch permission until its typed result has been reconciled.
    private var pendingQueueOutcomeWorker: Job? = null

    @Synchronized
    internal fun lastRequestWorker(): Job? = lastInstalledWorker

    @Synchronized
    internal fun mustQueueInput(): Boolean = ownedRequests.isNotEmpty() || snapshot.value.stream != null ||
        lastInstalledWorker?.isCompleted == false || pendingQueueOutcomeWorker != null || _inputQueue.value.items.isNotEmpty()

    @Synchronized
    internal fun enqueueInput(
        selection: net.weero.measix.pilot.data.enterprise.RealmSelection,
        assistantId: ConfigurationReference,
        parts: List<me.rerere.ai.ui.UIMessagePart>,
        answer: Boolean,
        submission: net.weero.measix.pilot.service.ArtifactSubmission?,
    ): QueuedConversationInput {
        check(selection.access.scope == durable.header.scope && assistantId == durable.header.assistantId) {
            "queue_context_mismatch"
        }
        val queue = _inputQueue.value
        check(queue.items.all { it.selection.access == selection.access }) { "queue_session_mismatch" }
        check(submission == null || queue.items.none { it.submission === submission }) { "queue_submission_already_owned" }
        val item = QueuedConversationInput(Uuid.random(), 0, Uuid.random(), parts.toList(), answer,
            selection, assistantId, inputContextRevision, QueuedInputPhase.WAITING, submission)
        if (inputQueueLease == null) inputQueueLease = acquireLease()
        _inputQueue.value = queue.copy(items = queue.items + item)
        return item
    }

    @Synchronized
    internal fun pauseInputQueue(reason: InputQueuePauseReason, contextChanged: Boolean = false) {
        if (contextChanged) inputContextRevision++
        val queue = _inputQueue.value
        _inputQueue.value = queue.copy(pauseReason = reason, epoch = queue.epoch + 1)
    }

    @Synchronized
    internal fun resumeInputQueue(
        selection: net.weero.measix.pilot.data.enterprise.RealmSelection,
        assistantId: ConfigurationReference,
    ) {
        check(selection.access.scope == durable.header.scope && assistantId == durable.header.assistantId) {
            "queue_context_mismatch"
        }
        val queue = _inputQueue.value
        check(queue.items.all { it.selection.access == selection.access }) { "queue_session_mismatch" }
        check(queue.items.none { it.phase == QueuedInputPhase.SUBMITTING }) { "queue_submission_in_progress" }
        check(queue.items.none { it.phase == QueuedInputPhase.EDITING }) { "queue_edit_in_progress" }
        _inputQueue.value = queue.copy(pauseReason = null, epoch = queue.epoch + 1,
            items = queue.items.map { it.copy(selection = selection, assistantId = assistantId,
                contextRevision = inputContextRevision, version = it.version + 1) })
    }

    @Synchronized
    internal fun beginQueuedInputEdit(itemId: Uuid, version: Long): QueuedConversationInput {
        val item = requireQueuedInput(itemId, version)
        check(item.phase == QueuedInputPhase.WAITING) { "queue_item_not_editable" }
        return item.copy(version = item.version + 1, phase = QueuedInputPhase.EDITING,
            editToken = Uuid.random()).also(::replaceQueuedInput)
    }

    @Synchronized
    internal fun registerQueuedInputEditCleanup(itemId: Uuid, version: Long, editToken: Uuid, cleanup: () -> Unit) {
        val item = requireQueuedInput(itemId, version)
        check(item.phase == QueuedInputPhase.EDITING && item.editToken == editToken) { "queue_edit_changed" }
        check(editToken !in inputEditCleanups) { "queue_edit_cleanup_already_registered" }
        inputEditCleanups[editToken] = cleanup
    }

    @Synchronized
    internal fun cancelQueuedInputEdit(itemId: Uuid, version: Long, editToken: Uuid): Boolean {
        val item = _inputQueue.value.items.find { it.id == itemId && it.version == version }
            ?: return false
        if (item.phase != QueuedInputPhase.EDITING || item.editToken != editToken) return false
        replaceQueuedInput(item.copy(version = item.version + 1, phase = QueuedInputPhase.WAITING,
            editToken = null))
        closeQueuedInputEditor(item)
        return true
    }

    /** Replacement already owns its resources; the caller closes it if this CAS rejects. */
    @Synchronized
    internal fun replaceQueuedInputContents(
        itemId: Uuid, version: Long, editToken: Uuid, parts: List<me.rerere.ai.ui.UIMessagePart>,
        submission: net.weero.measix.pilot.service.ArtifactSubmission?,
    ) {
        val item = requireQueuedInput(itemId, version)
        check(item.phase == QueuedInputPhase.EDITING) { "queue_item_not_editing" }
        check(item.editToken == editToken) { "queue_edit_changed" }
        check(submission == null || _inputQueue.value.items.none { it.submission === submission }) {
            "queue_submission_already_owned"
        }
        val replacement = item.copy(version = item.version + 1, parts = parts.toList(),
            phase = QueuedInputPhase.WAITING, submission = submission, editToken = null)
        if (submission != null) item.submission?.transferRetainedCreationTo(submission)
        replaceQueuedInput(replacement)
        closeQueuedInputEditor(item)
        item.submission?.close()
    }

    @Synchronized
    internal fun removeQueuedInput(itemId: Uuid, version: Long) {
        val item = requireQueuedInput(itemId, version)
        check(item.phase != QueuedInputPhase.SUBMITTING) { "queue_submission_in_progress" }
        item.submission?.close()
        removeQueuedInput(item)
    }

    @Synchronized
    internal fun nextQueuedInputDispatch(): QueuedInputDispatch? {
        val queue = _inputQueue.value
        val head = queue.items.firstOrNull() ?: return null
        if (queue.pauseReason != null || head.phase != QueuedInputPhase.WAITING) return null
        return QueuedInputDispatch(head.id, head.version, queue.epoch)
    }

    /** Confirmation covers exactly the observed revisions, never inputs added while the dialog was open. */
    @Synchronized
    internal fun clearQueuedInputs(expectedVersions: Map<Uuid, Long>) {
        val items = _inputQueue.value.items
        check(items.associate { it.id to it.version } == expectedVersions) { "queue_items_changed" }
        check(items.none { it.phase == QueuedInputPhase.SUBMITTING }) { "queue_submission_in_progress" }
        items.forEach(::closeQueuedInputEditor)
        items.forEach { it.submission?.close() }
        _inputQueue.value = _inputQueue.value.copy(items = emptyList())
        releaseInputQueueLeaseIfEmpty()
    }

    /** Called under the original operation lock and Session authorization; never supersedes a worker. */
    @Synchronized
    internal fun installQueuedInputWorker(
        dispatch: QueuedInputDispatch, selection: net.weero.measix.pilot.data.enterprise.RealmSelection,
        turnId: Uuid, worker: Job, requireQueueOutcome: Boolean = false,
    ): QueuedConversationInput? {
        val queue = _inputQueue.value
        val head = queue.items.firstOrNull() ?: return null
        if (dispatch != nextQueuedInputDispatch() || head.selection != selection) return null
        if (worker.isCancelled || worker.isCompleted) return null
        if (head.assistantId != durable.header.assistantId || head.contextRevision != inputContextRevision) return null
        if (ownedRequests.isNotEmpty() || snapshot.value.stream != null || lastInstalledWorker?.isCompleted == false ||
            pendingQueueOutcomeWorker != null) return null
        val accepted = head.copy(phase = QueuedInputPhase.SUBMITTING)
        replaceQueuedInput(accepted)
        installTurnWorker(turnId, worker, requireQueueOutcome = requireQueueOutcome)
        return accepted
    }

    /** The original operation lock orders result publication with every dispatch installation. */
    @Synchronized
    internal fun reconcileInputQueueOutcome(worker: Job, epoch: Long, outcome: InputQueueRequestOutcome): Boolean {
        if (pendingQueueOutcomeWorker !== worker || lastInstalledWorker !== worker || !worker.isCompleted) return false
        if (_inputQueue.value.items.isNotEmpty() && _inputQueue.value.epoch == epoch &&
            outcome != InputQueueRequestOutcome.COMPLETED && outcome != InputQueueRequestOutcome.AWAITING_USER) {
            pauseInputQueue(if (outcome == InputQueueRequestOutcome.CANCELLED)
                InputQueuePauseReason.USER_STOP else InputQueuePauseReason.REQUEST_FAILED)
        }
        pendingQueueOutcomeWorker = null
        return true
    }

    /** A stop epoch cannot erase a USER transaction that already committed. */
    @Synchronized
    internal fun settleQueuedInputSubmission(dispatch: QueuedInputDispatch, committed: Boolean) {
        val item = requireQueuedInput(dispatch.itemId, dispatch.version)
        check(item.phase == QueuedInputPhase.SUBMITTING) { "queue_item_not_submitting" }
        if (committed) {
            // Publication belongs to the request, which still holds the same submission capability.
            removeQueuedInput(item)
        } else {
            replaceQueuedInput(item.copy(phase = QueuedInputPhase.WAITING))
            if (_inputQueue.value.pauseReason == null) pauseInputQueue(InputQueuePauseReason.REQUEST_FAILED)
        }
    }

    @Synchronized
    internal fun clearInputQueueAfterStop() {
        check(ownedRequests.isEmpty() && snapshot.value.stream == null && lastInstalledWorker?.isCompleted != false) {
            "queue_workers_must_finish_before_clear"
        }
        pauseInputQueue(InputQueuePauseReason.USER_STOP)
        _inputQueue.value.items.forEach(::closeQueuedInputEditor)
        _inputQueue.value.items.forEach { it.submission?.close() }
        _inputQueue.value = _inputQueue.value.copy(items = emptyList())
        releaseInputQueueLeaseIfEmpty()
    }

    private fun requireQueuedInput(itemId: Uuid, version: Long): QueuedConversationInput =
        requireNotNull(_inputQueue.value.items.find { it.id == itemId && it.version == version }) { "queue_item_changed" }

    private fun replaceQueuedInput(item: QueuedConversationInput) {
        _inputQueue.value = _inputQueue.value.copy(items = _inputQueue.value.items.map { if (it.id == item.id) item else it })
    }

    private fun removeQueuedInput(item: QueuedConversationInput) {
        _inputQueue.value = _inputQueue.value.copy(items = _inputQueue.value.items.filterNot { it.id == item.id })
        closeQueuedInputEditor(item)
        releaseInputQueueLeaseIfEmpty()
    }

    /** Resource revocation only; callbacks must not acquire Session or conversation operation locks. */
    private fun closeQueuedInputEditor(item: QueuedConversationInput) {
        item.editToken?.let { inputEditCleanups.remove(it)?.invoke() }
    }

    private fun releaseInputQueueLeaseIfEmpty() {
        if (_inputQueue.value.items.isEmpty()) {
            inputQueueLease?.close()
            inputQueueLease = null
        }
    }

    internal fun draftArtifacts() = ownedDraft.get()?.artifacts.orEmpty()

    internal suspend fun publishDraftArtifacts() {
        val draft = ownedDraft.get() ?: return
        draft.publish()
        ownedDraft.compareAndSet(draft, null)
    }

    // 唯一事实流：durable 聚合 + 唯一高频流式投影
    private val _snapshot = MutableStateFlow(
        ConversationRuntimeSnapshot(durable = initial, stream = null),
    )

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    /**
     * Process-local owner of one Conversation request. Durable CAS still uses [TurnHandle];
     * this machine never becomes a second write protocol.
     */
    private class ActiveTurnSession(
        val assistantId: ConfigurationReference,
        val turnId: Uuid,
        val worker: Job,
        handle: TurnHandle? = null,
        phase: TurnLivePhase = TurnLivePhase.PREPARING,
        turnContext: TurnContext? = null,
        var modelExecutionLease: ModelExecutionLease? = null,
        var mcpExecutionLease: McpExecutionLease? = null,
        val childExecutions: MutableSet<TurnFinalizer.ChildExecution> = linkedSetOf(),
    ) {
        var releaseStarted = false
        private val _phase = AtomicReference(phase)
        private val _handle = AtomicReference(handle)
        private val _turnContext = AtomicReference(turnContext)
        private val _cancelReason = AtomicReference<String?>(null)
        private val _processingText = AtomicReference<String?>(null)

        val handle: TurnHandle? get() = _handle.get()

        /**
         * 本 Turn 冻结的 `TurnContext`。它在 `StartTurn` 事务之后由 materialize 绑定，
         * 绑定前不存在合法读取者，故对外为必需（未绑定即读取是编程错误）。
         */
        val turnContext: TurnContext
            get() = requireNotNull(_turnContext.get()) { "turn context is missing for turn $turnId" }
        val processingText: String? get() = _processingText.get()
        val workerIdentity: Int get() = System.identityHashCode(worker)

        fun presentationPhase(): TurnLivePhase = _phase.get()

        fun bindTurnContext(context: TurnContext) {
            check(_turnContext.compareAndSet(null, context)) {
                "turn context is already bound for turn $turnId"
            }
        }

        fun peekCancelReason(): String? = _cancelReason.get()

        fun consumeCancelReason(): String? = _cancelReason.getAndSet(null)

        fun requestCancel(reason: String) {
            _cancelReason.compareAndSet(null, reason)
            val current = _phase.get()
            _phase.compareAndSet(current, TurnLivePhase.STOPPING)
            worker.cancel(CancellationException(_cancelReason.get() ?: reason))
        }

        fun markRunning(handle: TurnHandle) {
            check(handle.turnId == turnId) { "running handle ${handle.turnId} does not match turn $turnId" }
            _handle.set(handle)
            // Turn 已运行但尚未产出任何流式内容：初始子阶段是 PREPARING，由 loop 的 typed 阶段事件细化。
            _phase.set(TurnLivePhase.PREPARING)
        }

        fun markAwaitingUser(handle: TurnHandle) {
            check(handle.turnId == turnId) { "handle ${handle.turnId} does not match turn $turnId" }
            _handle.set(handle)
            _phase.set(TurnLivePhase.AWAITING_USER)
        }

        /**
         * loop 驱动的生成子阶段（PREPARING/MODEL_WAITING/MODEL_STREAMING/TOOL_PREPARING/TOOL_EXECUTING）。
         * AWAITING_USER 与 STOPPING 由 markAwaitingUser/requestCancel 拥有，loop 阶段不得覆盖；
         * 续跑时 markRunning 先把 AWAITING_USER 复位为 PREPARING，loop 才重新推进。
         */
        fun updateLivePhase(phase: TurnLivePhase) {
            val current = _phase.get()
            if (current == TurnLivePhase.STOPPING || current == TurnLivePhase.AWAITING_USER) return
            _phase.set(phase)
        }

        fun reportProcessingText(text: String?) {
            _processingText.set(text)
        }

    }

    private val _activeTurn = MutableStateFlow<ActiveTurnSession?>(null)
    private val _activeTurnRevision = MutableStateFlow(0L)
    /**
     * Last request identity that was released or superseded. This is a read-only
     * lifecycle marker for receipt consumers; it is not a second turn state.
     */
    private val _lastTerminatedRequestTurnId = AtomicReference<Uuid?>(null)
    /**
     * Requests stay keyed by turnId until their worker completes so a superseded
     * owner can still read its own cancel reason. Removing on replace would make
     * TurnCommitter fall back to `user_stop`. A completed turnId is a no-op target.
     */
    private val ownedRequests = ConcurrentHashMap<Uuid, ActiveTurnSession>()
    internal val hasExecutionLeases: Boolean
        @Synchronized get() = ownedRequests.values.any {
            it.modelExecutionLease != null || it.mcpExecutionLease != null || it.childExecutions.isNotEmpty()
        }
    private class AuxiliaryWorker(val access: RealmAccess, val assistantId: ConfigurationReference) {
        val models = linkedSetOf<ModelExecutionLease>()
    }
    private val auxiliaryWorkers = ConcurrentHashMap<Job, AuxiliaryWorker>()
    internal val hasAuxiliaryWork: Boolean get() = auxiliaryWorkers.isNotEmpty()

    /** Registration happens under Session and conversation admission, before the worker starts. */
    @Synchronized
    internal fun registerAuxiliaryWorker(access: RealmAccess, worker: Job) {
        check(access.scope == durable.header.scope) { "conversation_scope_mismatch" }
        check(auxiliaryWorkers.putIfAbsent(worker, AuxiliaryWorker(access, durable.header.assistantId)) == null) { "auxiliary_worker_already_registered" }
        cancelIdleCheck()
        worker.invokeOnCompletion { retireAuxiliaryWorker(worker) }
    }

    @Synchronized
    internal fun ownsAuxiliaryWorker(access: RealmAccess, worker: Job): Boolean = auxiliaryWorkers[worker]?.access == access

    @Synchronized
    internal fun bindAuxiliaryModelExecution(access: RealmAccess, worker: Job, assistantId: ConfigurationReference, lease: ModelExecutionLease) {
        worker.ensureActive()
        val owner = requireNotNull(auxiliaryWorkers[worker]) { "auxiliary_worker_owner_missing" }
        check(owner.access == access && owner.assistantId == assistantId) { "auxiliary_worker_owner_changed" }
        check(owner.models.add(lease)) { "auxiliary_model_already_registered" }
    }

    /** Completed jobs retain failed releases; stop and deletion retry the same owned leases. */
    internal suspend fun releaseAuxiliaryModels(workers: Collection<Job>) {
        val leases = synchronized(this) {
            workers.flatMap { worker -> auxiliaryWorkers[worker]?.models.orEmpty().map { worker to it } }
        }
        var failure: Throwable? = null
        leases.forEach { (worker, lease) ->
            try {
                lease.release()
                synchronized(this) { auxiliaryWorkers[worker]?.models?.remove(lease) }
            } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        workers.forEach(::retireAuxiliaryWorker)
        failure?.let { throw it }
    }

    @Synchronized
    private fun retireAuxiliaryWorker(worker: Job) {
        val owner = auxiliaryWorkers[worker] ?: return
        if (worker.isCompleted && owner.models.isEmpty()) {
            check(auxiliaryWorkers.remove(worker, owner)) { "auxiliary_worker_owner_changed" }
            if (!isInUse) scheduleIdleCheck()
        }
    }

    /** Capture exact workers, including completed jobs whose resource release needs retry. */
    internal fun captureAndCancelAuxiliaryWorkers(assistantId: ConfigurationReference? = null): List<Job> =
        auxiliaryWorkers.entries.filter { assistantId == null || it.value.assistantId == assistantId }.map { it.key }.also { workers ->
            workers.forEach { it.cancel() }
        }
    internal val activeTurnRevision: StateFlow<Long> = _activeTurnRevision.asStateFlow()

    internal fun lastTerminatedRequestTurnId(): Uuid? = _lastTerminatedRequestTurnId.get()

    private val toolApprovalMutex = Mutex()
    private var ttsQueueSessionId: String? = null
    private var ttsPlaybackContext: net.weero.measix.pilot.data.ai.tools.local.TtsToolPlaybackContext? = null
    internal val isGenerating: Boolean get() = _activeTurn.value?.worker?.isActive == true
    val isInUse: Boolean
        get() = refCount.get() > 0 || ownedRequests.isNotEmpty() || snapshot.value.stream != null || hasAuxiliaryWork

    private val turnEpoch = AtomicLong(0)

    // ---- snapshot 事实源 ----

    /** internal 事实流；UI 订阅的是 ConversationPresentationSnapshot（见 ConversationQueryService）。 */
    internal val snapshot: StateFlow<ConversationRuntimeSnapshot> = _snapshot.asStateFlow()

    /**
     * durable 聚合的即时读取入口：命令 planning、终态收口与 request 组装只读已提交树。
     * 它是 [snapshot] 单一事实流的 durable 视图，不是第二状态源；流式草稿经 [snapshot] 读取。
     */
    internal val durable: ConversationAggregateSnapshot get() = _snapshot.value.durable

    /**
     * 流式高频更新：非挂起、conflated、永不落库、不加锁。只换流式投影，durable 树保持共享引用。
     */
    fun applyStreamingDelta(handle: TurnHandle, assistantMessage: UIMessage): StreamingDeltaResult {
        if (handle.conversationId != id) return StreamingDeltaResult.STALE_TURN
        var applied = false
        _snapshot.update { current ->
            val stream = current.stream
            if (stream != null && stream.matches(handle)) {
                applied = true
                current.copy(stream = stream.withStreamingAssistant(assistantMessage))
            } else {
                applied = false
                current
            }
        }
        return if (applied) StreamingDeltaResult.APPLIED else StreamingDeltaResult.STALE_TURN
    }

    internal fun nextTurnEpoch(): Long = turnEpoch.incrementAndGet()

    internal fun publishDraft(snapshot: ConversationAggregateSnapshot) {
        check(snapshot.header.newConversation) { "publishDraft requires a Draft snapshot" }
        check(_snapshot.value.durable.header.newConversation) { "a Ready runtime cannot accept a Draft publish" }
        _snapshot.update { it.copy(durable = snapshot) }
    }

    /**
     * durable 提交只换 [ConversationRuntimeSnapshot.durable]；流式投影按命令语义独立演进：
     * StartTurn 建立空草稿、Finalize/Recover 清空、checkpoint/resolve 只在归属同一 Turn 的
     * 最新草稿上重放（[afterCheckpoint]/[afterResolve]），从而提交事务前到达的在途 delta 不被回退。
     * durable 与 stream 是两个正交字段，各自独立更新。
     */
    internal fun publishCommitted(
        command: ConversationCommand,
        committed: ConversationAggregateSnapshot,
    ) {
        _snapshot.update { latest ->
            val nextStream: TurnStreamProjection? = when (command) {
                is StartTurn -> TurnStreamProjection(
                    epoch = command.epoch,
                    turnId = command.turnId,
                    assistantMessageId = command.assistantMessageId,
                    assistantMessage = null,
                )

                is FinalizeTurn,
                is RecoverInterruptedTurn,
                -> null

                is TurnCheckpoint ->
                    latest.stream?.takeIf { it.matches(command.turn) }?.afterCheckpoint(command)
                        ?: latest.stream

                is ResolveToolInteraction ->
                    latest.stream?.takeIf { it.matches(command.handle) }?.afterResolve(command)
                        ?: latest.stream

                else -> latest.stream
            }
            val next = latest.copy(durable = committed, stream = nextStream)
            if (next == latest) latest else next
        }
    }

    // ---- Active turn ownership ----

    internal fun currentGenerationTurnId(): Uuid? = _activeTurn.value?.turnId

    internal fun isAwaitingUser(turnId: Uuid): Boolean {
        val current = _activeTurn.value ?: return false
        return current.turnId == turnId &&
            current.presentationPhase() == TurnLivePhase.AWAITING_USER
    }

    internal suspend fun awaitCurrentWorker() {
        _activeTurn.value?.worker?.join()
    }

    internal fun currentWorker(): Job? = _activeTurn.value?.worker

    /** Binds the immutable request context once to the exact worker that owns this Turn. */
    @Synchronized
    internal fun bindTurnContext(turnId: Uuid, worker: Job, context: TurnContext) {
        val current = _activeTurn.value
        check(current != null && current.turnId == turnId && current.worker === worker) {
            "turn context owner does not match turn $turnId"
        }
        check(current.assistantId == context.assistant.id) { "turn_context_assistant_changed" }
        check(!current.releaseStarted && current.modelExecutionLease?.owns(context.model.requests) == true) {
            "turn_context_execution_owner_mismatch"
        }
        current.bindTurnContext(context)
    }

    /** Continuations and retries must reuse the context owned by their exact active worker. */
    internal fun requireTurnContext(turnId: Uuid, worker: Job): TurnContext {
        val current = _activeTurn.value
        check(current != null && current.turnId == turnId && current.worker === worker) {
            "turn context owner does not match turn $turnId"
        }
        return current.turnContext
    }

    internal fun activeTurnPresentationFacts(): ActiveTurnPresentationFacts? {
        val current = _activeTurn.value ?: return null
        return ActiveTurnPresentationFacts(
            turnId = current.turnId,
            phase = current.presentationPhase(),
            handle = current.handle,
            processingText = current.processingText,
        )
    }

    @Synchronized
    internal fun requestCancel(turnId: Uuid, reason: String) {
        ownedRequests[turnId]?.requestCancel(reason)
    }

    internal fun consumeCancelReason(turnId: Uuid): String? =
        ownedRequests[turnId]?.consumeCancelReason()

    internal fun peekCancelReason(turnId: Uuid): String? =
        ownedRequests[turnId]?.peekCancelReason()

    /**
     * Captures one active-turn identity and requests its stop.
     * Job and turnId come from the same object so a concurrent START cannot mix them.
     */
    @Synchronized
    internal fun captureAndRequestStop(reason: String, assistantId: ConfigurationReference? = null, turnId: Uuid? = null): CapturedTurnWorker? {
        val current = _activeTurn.value
        val owners = (listOfNotNull(current) + ownedRequests.values.filter { it !== current })
            .filter { (assistantId == null || it.assistantId == assistantId) && (turnId == null || it.turnId == turnId) }
        if (owners.isNotEmpty()) {
            return owners.asReversed().fold<ActiveTurnSession, CapturedTurnWorker?>(null) { pending, owner ->
                owner.requestCancel(reason)
                CapturedTurnWorker(owner.turnId, owner.worker, pending)
            }
        }
        if (assistantId != null && (ownedRequests.isNotEmpty() || durable.header.assistantId != assistantId)) return null
        val durableTurnId = snapshot.value.stream?.turnId ?: return null
        if (turnId != null && durableTurnId != turnId) return null
        return CapturedTurnWorker(durableTurnId, null)
    }

    @Synchronized
    internal fun ownsStoppedWorker(captured: CapturedTurnWorker): Boolean {
        val current = _activeTurn.value
        return if (captured.worker == null) {
            current == null && snapshot.value.stream?.turnId == captured.turnId
        } else ownedRequests[captured.turnId]?.worker === captured.worker
    }

    @Synchronized
    internal fun installTurnWorker(
        turnId: Uuid,
        worker: Job,
        handle: TurnHandle? = null,
        phase: TurnLivePhase = TurnLivePhase.PREPARING,
        supersedeReason: String? = null,
        turnContext: TurnContext? = null,
        modelExecutionLease: ModelExecutionLease? = null,
        mcpExecutionLease: McpExecutionLease? = null,
        childExecutions: Collection<TurnFinalizer.ChildExecution> = emptyList(),
        requireQueueOutcome: Boolean = false,
    ): InstalledTurnWorker {
        cancelIdleCheck()
        lastInstalledWorker = worker
        pendingQueueOutcomeWorker = worker.takeIf { requireQueueOutcome }
        val previous = _activeTurn.value
        if (previous != null && previous.turnId != turnId) {
            previous.requestCancel(supersedeReason ?: "superseded_by_new_turn")
            _lastTerminatedRequestTurnId.set(previous.turnId)
        }
        val installed = ActiveTurnSession(
            assistantId = turnContext?.assistant?.id ?: durable.header.assistantId,
            turnId = turnId,
            worker = worker,
            handle = handle,
            phase = phase,
            turnContext = turnContext,
            modelExecutionLease = modelExecutionLease,
            mcpExecutionLease = mcpExecutionLease,
            childExecutions = childExecutions.toMutableSet(),
        )
        ownedRequests[turnId] = installed
        publishActive(installed)
        worker.invokeOnCompletion {
            completeActiveWorker(installed)
        }
        return InstalledTurnWorker(turnId, previous?.turnId, previous?.worker)
    }

    @Synchronized
    internal fun markRunning(handle: TurnHandle) {
        val current = requireNotNull(_activeTurn.value) { "no active turn to mark running" }
        check(current.turnId == handle.turnId) {
            "running handle ${handle.turnId} does not match active turn ${current.turnId}"
        }
        current.markRunning(handle)
        publishActive(current)
    }

    @Synchronized
    internal fun retainAwaitingUser(handle: TurnHandle) {
        val current = _activeTurn.value ?: return
        if (current.turnId != handle.turnId) return
        current.markAwaitingUser(handle)
        publishActive(current)
    }

    /**
     * Replaces the completed user-paused worker with the continuation worker.
     * A mismatched owner, phase or handle is rejected without cancelling the current request.
     */
    @Synchronized
    internal fun continueAwaitingUser(handle: TurnHandle, worker: Job, requireQueueOutcome: Boolean = false): InstalledTurnWorker {
        val current = _activeTurn.value
        val stream = snapshot.value.stream
        if (
            current == null ||
            current.turnId != handle.turnId ||
            current.presentationPhase() != TurnLivePhase.AWAITING_USER ||
            current.handle != handle ||
            stream == null ||
            !stream.matches(handle)
        ) {
            throw ConversationCommandConflictException(
                "continuation ${handle.turnId} is not the current awaiting owner",
            )
        }
        val context = current.turnContext
        check(!current.releaseStarted) { "turn_execution_release_started" }
        return installTurnWorker(
            turnId = handle.turnId,
            worker = worker,
            handle = handle,
            phase = TurnLivePhase.PREPARING,
            turnContext = context,
            modelExecutionLease = current.modelExecutionLease,
            mcpExecutionLease = current.mcpExecutionLease,
            childExecutions = current.childExecutions,
            requireQueueOutcome = requireQueueOutcome,
        ).also { current.modelExecutionLease = null; current.mcpExecutionLease = null; current.childExecutions.clear() }
    }

    /** The accepting worker hands off immediately after capture, before suspending preparation. */
    @Synchronized
    internal fun bindModelExecution(turnId: Uuid, worker: Job, assistantId: ConfigurationReference, lease: ModelExecutionLease) {
        val current = requireNotNull(ownedRequests[turnId]) { "turn_execution_owner_missing" }
        check(current.worker === worker && worker.isActive && !current.releaseStarted && current.assistantId == assistantId) {
            "turn_execution_owner_changed"
        }
        check(current.modelExecutionLease == null) { "turn_execution_already_bound" }
        current.modelExecutionLease = lease
    }

    @Synchronized
    internal fun bindMcpExecution(turnId: Uuid, worker: Job, assistantId: ConfigurationReference, lease: McpExecutionLease) {
        val current = requireNotNull(ownedRequests[turnId]) { "turn_execution_owner_missing" }
        check(current.worker === worker && worker.isActive && !current.releaseStarted && current.assistantId == assistantId) {
            "turn_execution_owner_changed"
        }
        check(current.mcpExecutionLease == null) { "mcp_execution_already_bound" }
        current.mcpExecutionLease = lease
    }

    /** The committed tool locator identifies its original parent request, including during replacement. */
    @Synchronized
    internal fun bindChildExecution(assistantMessageId: Uuid, execution: TurnFinalizer.ChildExecution): Uuid {
        val current = requireNotNull(ownedRequests.values.singleOrNull { it.handle?.assistantMessageId == assistantMessageId }) {
            "parent_turn_execution_owner_missing"
        }
        current.worker.ensureActive()
        check(!current.releaseStarted) { "parent_turn_execution_owner_changed" }
        check(execution.runtime.durable.header.parentConversationId == id && execution.runtime.durable.header.scope == durable.header.scope) {
            "child_execution_parent_mismatch"
        }
        check(current.childExecutions.add(execution)) { "child_execution_already_bound" }
        return current.turnId
    }

    internal suspend fun releaseChildExecution(turnId: Uuid, execution: TurnFinalizer.ChildExecution, stopReason: String? = null) {
        if (synchronized(this) { ownedRequests[turnId]?.childExecutions?.contains(execution) != true }) return
        if (execution.release(stopReason)) {
            synchronized(this) { ownedRequests[turnId]?.childExecutions?.remove(execution) }
        }
    }

    /** Child finalization and worker joins must happen before taking the parent's command lock. */
    internal suspend fun releaseChildExecutions(turnId: Uuid, worker: Job?, stopReason: String? = null) {
        val children = synchronized(this) {
            val current = ownedRequests[turnId] ?: return
            if (worker != null && current.worker !== worker) return
            current.childExecutions.toList()
        }
        var failure: Throwable? = null
        children.forEach { child ->
            try { releaseChildExecution(turnId, child, stopReason) }
            catch (error: Throwable) { failure = combineReleaseFailure(failure, error) }
        }
        failure?.let { throw it }
    }

    internal fun processingReporter(): (String?) -> Unit {
        val current = _activeTurn.value ?: return {}
        val turnId = current.turnId
        val workerIdentity = current.workerIdentity
        return { text -> reportProcessingText(turnId, workerIdentity, text) }
    }

    @Synchronized
    internal fun reportProcessingText(turnId: Uuid, workerIdentity: Int, text: String?) {
        val current = _activeTurn.value ?: return
        if (current.turnId != turnId || current.workerIdentity != workerIdentity) return
        current.reportProcessingText(text)
        publishActive(current)
    }

    /** Captures the current owner identity so loop phase events only advance this exact Turn's live phase. */
    internal fun livePhaseReporter(): (TurnLivePhase) -> Unit {
        val current = _activeTurn.value ?: return {}
        val turnId = current.turnId
        val workerIdentity = current.workerIdentity
        return { phase -> updateLivePhase(turnId, workerIdentity, phase) }
    }

    @Synchronized
    internal fun updateLivePhase(turnId: Uuid, workerIdentity: Int, phase: TurnLivePhase) {
        val current = _activeTurn.value ?: return
        if (current.turnId != turnId || current.workerIdentity != workerIdentity) return
        current.updateLivePhase(phase)
        publishActive(current)
    }

    internal suspend fun releaseTurnWorker(
        turnId: Uuid,
        worker: Job? = null,
        retainPendingTurnOwner: Boolean = true,
    ) {
        val current = synchronized(this) {
            val owned = ownedRequests[turnId] ?: return
            if (worker != null && owned.worker !== worker) return
            if (retainPendingTurnOwner && snapshot.value.stream?.turnId == turnId) {
                _activeTurnRevision.value++
                return
            }
            owned.releaseStarted = true
            owned
        }
        var failure: Throwable? = null
        try { releaseChildExecutions(turnId, current.worker) }
        catch (error: Throwable) { failure = error }
        current.mcpExecutionLease?.let { lease ->
            try {
                lease.release()
                synchronized(this) { current.mcpExecutionLease = null }
            } catch (error: Throwable) { failure = combineReleaseFailure(failure, error) }
        }
        current.modelExecutionLease?.let { lease ->
            try {
                lease.release()
                synchronized(this) { current.modelExecutionLease = null }
            } catch (error: Throwable) { failure = combineReleaseFailure(failure, error) }
        }
        failure?.let { throw it }
        synchronized(this) {
            if (current.childExecutions.isEmpty()) retireTurnWorker(current) else _activeTurnRevision.value++
        }
    }

    private fun combineReleaseFailure(previous: Throwable?, error: Throwable): Throwable {
        if (previous == null || previous === error) return error
        val cancelled = (previous as? CancellationException) ?: (error as? CancellationException)
        val primary = cancelled ?: previous
        return (if (cancelled != null) CancellationException("Conversation resource cleanup cancelled").apply {
            initCause(cancelled)
        } else java.io.IOException("Conversation resource cleanup failed", primary)).apply {
            addSuppressed(if (primary === previous) error else previous)
        }
    }

    private fun retireTurnWorker(current: ActiveTurnSession) {
        val turnId = current.turnId
        if (!ownedRequests.remove(turnId, current)) return
        val pending = snapshot.value.stream?.turnId?.takeIf { it != turnId }?.let { ownedRequests[it] }
            // A superseded request may still own failed cleanup after its durable Turn has ended.
            ?: ownedRequests.values.firstOrNull()
        if (_activeTurn.compareAndSet(current, pending)) {
            _lastTerminatedRequestTurnId.set(turnId)
            _activeTurnRevision.value++
        }
        if (!isInUse) scheduleIdleCheck()
    }

    private fun publishActive(request: ActiveTurnSession?) {
        _activeTurn.value = request
        _activeTurnRevision.value++
    }

    @Synchronized
    private fun completeActiveWorker(request: ActiveTurnSession) {
        if (snapshot.value.stream?.turnId != request.turnId && request.modelExecutionLease == null && request.mcpExecutionLease == null &&
            request.childExecutions.isEmpty() && !request.releaseStarted) {
            retireTurnWorker(request)
        }
    }

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    private fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
        Log.d(TAG, "acquire $id (refs=$it)")
    }

    private fun release(): Int = refCount.decrementAndGet().also {
        check(it >= 0) {
            refCount.incrementAndGet()
            "unpaired conversation runtime release: $id"
        }
        Log.d(TAG, "release $id (refs=$it)")
        if (it == 0 && !isInUse) scheduleIdleCheck()
    }

    internal fun acquireLease(): ConversationRuntimeLease {
        acquire()
        return ConversationRuntimeLease(this)
    }

    internal fun releaseLease() {
        release()
    }

    /**
     * 返回主代理 turn 独占的 TTS 队列 ID。
     */
    @Synchronized
    fun getTtsQueueSessionId(resumeExistingTurn: Boolean): String {
        if (!resumeExistingTurn || ttsQueueSessionId == null) {
            ttsQueueSessionId = Uuid.random().toString()
        }
        return requireNotNull(ttsQueueSessionId)
    }

    @Synchronized
    fun peekTtsPlaybackContext(): net.weero.measix.pilot.data.ai.tools.local.TtsToolPlaybackContext? = ttsPlaybackContext

    @Synchronized
    fun bindTtsPlaybackContext(context: net.weero.measix.pilot.data.ai.tools.local.TtsToolPlaybackContext) {
        check(context.sessionId == ttsQueueSessionId) { "turn_speech_queue_mismatch" }
        ttsPlaybackContext = context
    }

    suspend fun <T> withToolApprovalLock(block: suspend () -> T): T =
        toolApprovalMutex.withLock { block() }

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(idleTimeoutMs)
            if (!isInUse) {
                onIdle(id)
            }
        }
    }

    /** Registry 安装后立即武装空闲逐出；短生命周期后台命令无需手写 acquire/release。 */
    internal fun armIdleEviction() {
        if (!isInUse) scheduleIdleCheck()
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    @Synchronized
    fun cleanup() {
        check(_inputQueue.value.items.isEmpty() && inputQueueLease == null) { "queued_inputs_must_close_before_eviction" }
        check(!hasAuxiliaryWork) { "auxiliary_workers_must_finish_before_eviction" }
        check(!hasExecutionLeases) { "turn_execution_leases_must_finish_before_eviction" }
        _activeTurn.value?.worker?.cancel()
        _activeTurn.value = null
        ownedRequests.clear()
        ownedDraft.getAndSet(null)?.close()
        ttsQueueSessionId = null
        ttsPlaybackContext = null
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

}

/** Exactly-once observation lease; a caller can only release the reference it acquired. */
internal class ConversationRuntimeLease internal constructor(
    private val runtime: ConversationRuntime,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    internal fun draftArtifacts(): List<net.weero.measix.pilot.data.files.OwnedArtifact> {
        check(!closed.get()) { "conversation_runtime_lease_closed" }
        return runtime.draftArtifacts()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runtime.releaseLease()
    }
}

internal fun TurnStreamProjection.matches(handle: TurnHandle): Boolean =
    epoch == handle.epoch &&
        turnId == handle.turnId &&
        assistantMessageId == handle.assistantMessageId

internal fun Conversation.toSnapshot(
    modelContextEntries: List<net.weero.measix.pilot.data.model.ConversationModelContextEntry> = emptyList(),
    contextAdmissions: List<net.weero.measix.pilot.data.model.ConversationContextAdmission> = emptyList(),
    opening: net.weero.measix.pilot.data.model.ConversationOpening? = null,
): ConversationAggregateSnapshot = ConversationAggregateSnapshot(
    conversationId = id,
    header = ConversationHeader(
        id = id,
        title = title,
        assistantId = assistantId,
        folderId = folderId,
        isPinned = isPinned,
        chatSuggestions = chatSuggestions,
        customSystemPrompt = customSystemPrompt,
        modeInjectionIds = modeInjectionIds,
        workspaceCwd = workspaceCwd,
        parentConversationId = parentConversationId,
        newConversation = newConversation,
        createAt = createAt.toEpochMilli(),
        updateAt = updateAt.toEpochMilli(),
        scope = scope,
    ),
    nodes = messageNodes,
    // durable context 只进入 internal aggregate，不进入 public Conversation。
    modelContextEntries = modelContextEntries,
    contextAdmissions = contextAdmissions,
    opening = opening,
)
