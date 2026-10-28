package net.weero.measix.pilot.service

import net.weero.measix.pilot.utils.logDiagnosticFailure

import net.weero.measix.pilot.service.turn.TurnFinalizer
import net.weero.measix.pilot.service.subassistant.SubAssistantLifecycle
import net.weero.measix.pilot.service.turn.TurnContextFactory
import android.app.Application
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.service.runtime.InstalledTurnWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.ui.TurnTerminalReasons
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.findUserTurnStart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.event.AppEvent
import net.weero.measix.pilot.data.event.AppEventBus
import net.weero.measix.pilot.service.turn.TurnRunner
import net.weero.measix.pilot.service.turn.TurnRunInputs
import net.weero.measix.pilot.service.turn.TurnDisclosureSource
import net.weero.measix.pilot.data.model.DisclosureNamespace
import net.weero.measix.pilot.data.model.storageId
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.ai.tools.shouldUseExternalWebSearch
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallState
import net.weero.measix.pilot.data.ai.subassistant.buildSubAssistantCallResult
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.parseAssistantCallExtrasFromInput
import net.weero.measix.pilot.data.ai.mcp.McpRuntimeCoordinator
import net.weero.measix.pilot.data.ai.mcp.McpServerCapabilityState
import net.weero.measix.pilot.data.ai.mcp.McpTurnPreparationException
import net.weero.measix.pilot.data.ai.mcp.McpTurnPreparationFailureReason
import net.weero.measix.pilot.data.ai.tools.AssistantToolFactory
import net.weero.measix.pilot.data.ai.tools.buildMemoryTools
import net.weero.measix.pilot.data.ai.tools.local.TtsToolPlaybackContext
import net.weero.measix.pilot.data.ai.tts.TtsPlaybackSource
import net.weero.measix.pilot.data.ai.tools.TurnToolSetFactory
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.findModelById
import net.weero.measix.pilot.data.datastore.getAssistantById
import net.weero.measix.pilot.data.datastore.getChatModel
import net.weero.measix.pilot.data.datastore.getCurrentChatModel
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.AssistantAffectScope
import net.weero.measix.pilot.data.model.replaceRegexes
import net.weero.measix.pilot.data.model.toMessageNode
import net.weero.measix.pilot.service.MemoryService
import net.weero.measix.pilot.data.repository.WorkspaceRepository
import java.time.Instant
import java.util.Locale
import kotlinx.datetime.LocalDateTime
import kotlin.uuid.Uuid
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.turnLivePhaseOf
import net.weero.measix.pilot.service.runtime.ConversationRuntimeRegistry
import net.weero.measix.pilot.service.runtime.TurnTransition
import net.weero.measix.pilot.service.runtime.ConversationCommandCoordinator
import net.weero.measix.pilot.service.runtime.ConversationCommandConflictException
import net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot
import net.weero.measix.pilot.service.turn.TurnPipelineFactory
import net.weero.measix.pilot.service.turn.TurnCommitter
import net.weero.measix.pilot.service.runtime.TurnHandle
import net.weero.measix.pilot.service.runtime.TurnKind
import net.weero.measix.pilot.service.turn.TurnOutcome
import net.weero.measix.pilot.service.turn.TurnRunResult
import net.weero.measix.pilot.service.turn.TurnPause
import net.weero.measix.pilot.service.runtime.ToolLivePhase
import net.weero.measix.pilot.service.runtime.currentTurnPresentation
import net.weero.measix.pilot.service.runtime.AppendUserMessage
import net.weero.measix.pilot.service.runtime.ConversationRuntimeSnapshot
import net.weero.measix.pilot.service.runtime.TurnStreamProjection
import net.weero.measix.pilot.service.runtime.BackfillAttachmentRefs
import net.weero.measix.pilot.service.subassistant.SubAssistantRunCoordinator
import net.weero.measix.pilot.service.runtime.DeleteMessage
import net.weero.measix.pilot.service.runtime.EditMessageVariant
import net.weero.measix.pilot.service.runtime.FinalizeTurn
import net.weero.measix.pilot.service.runtime.OptionalFolderId
import net.weero.measix.pilot.service.runtime.ReplaceMessageTree
import net.weero.measix.pilot.service.runtime.SelectNodeVariant
import net.weero.measix.pilot.service.runtime.TruncateToNodeIndex
import net.weero.measix.pilot.service.runtime.UpdateHeader
import net.weero.measix.pilot.service.runtime.ResolveToolInteraction
import net.weero.measix.pilot.service.runtime.ToolInteractionDecision
import net.weero.measix.pilot.service.runtime.toInteractionState
import net.weero.measix.pilot.service.runtime.InputQueueRequestOutcome
import net.weero.measix.pilot.service.runtime.InputQueuePauseReason
import net.weero.measix.pilot.service.runtime.QueuedConversationInput
import net.weero.measix.pilot.service.runtime.QueuedInputDispatch

private const val TAG = "ConversationTurnService"

private fun TurnRunResult.queueOutcome(): InputQueueRequestOutcome = when (this) {
    is TurnOutcome.Completed -> InputQueueRequestOutcome.COMPLETED
    is TurnPause -> InputQueueRequestOutcome.AWAITING_USER
    is TurnOutcome.Cancelled -> InputQueueRequestOutcome.CANCELLED
    else -> InputQueueRequestOutcome.FAILED
}

/** 用户会话 Turn 有两个不可互换的入口协议。 */
internal enum class TurnEntry {
    /** Opens a new durable turn after structural preflight. */
    START,

    /** Continues the existing user-paused owner without mutating the message tree. */
    CONTINUE_USER_INTERACTION,
}

/** START captures configuration through the model owner; continuation reuses its original context. */
private sealed interface TurnLaunch {
    val entry: TurnEntry
    val realmAccess: RealmAccess

    data class Start(override val realmAccess: RealmAccess) : TurnLaunch {
        override val entry = TurnEntry.START
    }

    data class Continue(override val realmAccess: RealmAccess) : TurnLaunch {
        override val entry = TurnEntry.CONTINUE_USER_INTERACTION
    }
}

internal data class TurnLaunchPolicy(
    val runStructuralPreflight: Boolean,
    val reuseTtsQueue: Boolean,
)

/**
 * Validates the entry against its owning snapshot before any command is submitted.
 * User-interaction continuation must retain the current turn owner and cannot become a second start path.
 */
internal fun turnLaunchPolicy(
    entry: TurnEntry,
    activeTurn: TurnStreamProjection?,
    turnId: Uuid,
    messageRange: ClosedRange<Int>?,
): TurnLaunchPolicy = when (entry) {
    TurnEntry.START -> {
        check(activeTurn == null) { "a new conversation turn cannot start while another turn is active" }
        TurnLaunchPolicy(runStructuralPreflight = true, reuseTtsQueue = false)
    }

    TurnEntry.CONTINUE_USER_INTERACTION -> {
        check(messageRange == null) { "a user-interaction continuation cannot use a message range" }
        val active = requireNotNull(activeTurn) { "user-interaction continuation has no active turn" }
        check(active.turnId == turnId) {
            "active turn ${active.turnId} does not match user-interaction continuation $turnId"
        }
        TurnLaunchPolicy(runStructuralPreflight = false, reuseTtsQueue = true)
    }
}

/**
 * User decisions are continuations of the active durable turn. This orchestration seam owns the
 * exact interaction command and makes it impossible for approve/deny/answer to enter the new-turn
 * structural preflight path. A decision whose type does not match the interaction the call paused
 * for is rejected fail-closed; an Answer is never permission and an Approve is never an answer.
 */
internal suspend fun applyToolInteractionDecision(
    locator: ToolCallLocator,
    decision: ToolInteractionDecision,
    awaitPreviousGeneration: suspend () -> Unit,
    currentSnapshot: () -> ConversationRuntimeSnapshot,
    submit: suspend (ResolveToolInteraction) -> Unit,
    onMoreApprovalsPending: suspend () -> Unit,
    continueTurn: suspend (TurnStreamProjection, TurnEntry) -> Unit,
) {
    awaitPreviousGeneration()
    val before = currentSnapshot()
    val located = before.durable.currentMessages()
        .firstOrNull { it.id == locator.assistantMessageId }
        ?.getTools()
        ?.firstOrNull { it.localCallId == locator.localCallId }
        ?: throw ConversationCommandConflictException("stale tool interaction locator: $locator")
    val targetState = decision.toInteractionState()
    if (located.interactionState == targetState) return
    val pending = located.takeIf { it.isPending }
        ?: throw ConversationCommandConflictException("tool interaction is no longer pending: $locator")
    check(!pending.hasReplayResult) { "tool with a replay result cannot accept a user decision" }
    requireDecisionMatchesInteraction(pending, decision)

    submit(
        ResolveToolInteraction(
            messageId = locator.assistantMessageId,
            stepId = locator.stepId,
            localCallId = locator.localCallId,
            decision = decision,
            handle = before.stream.let { owner ->
                requireNotNull(owner) { "tool interaction has no active turn owner" }
                TurnHandle(
                    conversationId = before.conversationId,
                    epoch = owner.epoch,
                    turnId = owner.turnId,
                    assistantMessageId = owner.assistantMessageId,
                )
            },
        ),
    )

    val after = currentSnapshot()
    val assistant = after.durable.currentMessages().firstOrNull { it.id == locator.assistantMessageId }
        ?: throw ConversationCommandConflictException("stale tool interaction locator: $locator")
    val committed = assistant.getTools().firstOrNull { it.localCallId == locator.localCallId }
    check(committed?.interactionState == targetState) {
        "tool interaction command was not committed"
    }
    if (assistant.getTools().any { it.isPending }) {
        onMoreApprovalsPending()
        return
    }
    continueTurn(
        requireNotNull(after.stream) { "decided tool has no owning active turn" },
        TurnEntry.CONTINUE_USER_INTERACTION,
    )
}

/** 决策类型必须与调用挂起时的 typed 交互一致；Approve/Deny 只对审批，Answer 只对用户输入。 */
private fun requireDecisionMatchesInteraction(
    pending: UIMessagePart.Tool,
    decision: ToolInteractionDecision,
) {
    val matches = when (decision) {
        ToolInteractionDecision.Approve,
        is ToolInteractionDecision.Deny,
        -> pending.interactionState is ToolInteractionState.AwaitingApproval

        is ToolInteractionDecision.Answer -> pending.interactionState is ToolInteractionState.AwaitingInput
    }
    check(matches) {
        "decision ${decision::class.simpleName} does not match interaction ${pending.interactionState}"
    }
}

/**
 * 主回合生成编排器。持久化命令、终态处理和边缘副作用分别委托给各自 owner。
 */

internal fun retainValidMessageNodes(nodes: List<MessageNode>): List<MessageNode> {
    var messagesNodes = nodes.map { node ->
        val current = runCatching { node.currentMessage }.getOrNull() ?: return@map node
        val tools = current.getTools()
        val hasPendingReplayResults = tools.any { !it.hasReplayResult }
        if (!hasPendingReplayResults) return@map node
        if (tools.any { !it.hasReplayResult && (it.isPending || it.canResumeResultAssembly) }) {
            return@map node
        }
        if (current.terminalStatus != null) return@map node
        node.copy(
            messages = node.messages.filter { it.id != current.id },
            selectIndex = node.selectIndex - 1,
        )
    }
    messagesNodes = messagesNodes.map { node ->
        if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
            node.copy(selectIndex = 0)
        } else {
            node
        }
    }
    return messagesNodes.filter { it.messages.isNotEmpty() }
}

/** Stable committed USER identity for append or edit/resend; generation completion is separate. */
data class SendMessageReceipt(
    val conversationId: Uuid,
    val turnId: Uuid,
    val userMessageId: Uuid,
)

internal data class QueuedInputReceipt(val conversationId: Uuid, val itemId: Uuid, val version: Long)

internal sealed interface ChatInputSubmissionReceipt {
    data class Sent(val receipt: SendMessageReceipt) : ChatInputSubmissionReceipt
    data class Queued(val receipt: QueuedInputReceipt) : ChatInputSubmissionReceipt
}

private sealed interface RequestAdmission {
    data class Started(val turnId: Uuid) : RequestAdmission
    data class Queued(val receipt: QueuedInputReceipt) : RequestAdmission
}

class ConversationTurnService internal constructor(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val modelExecutions: ModelExecutionService,
    private val speech: SpeechApplicationService,
    private val memoryService: MemoryService,
    private val sessions: EnterpriseSessionController,
    private val turnRunner: TurnRunner,
    private val turnPipelineFactory: TurnPipelineFactory,
    private val mcpManager: McpRuntimeCoordinator,
    private val toolSetFactory: TurnToolSetFactory,
    private val turnContextFactory: net.weero.measix.pilot.service.turn.TurnContextFactory,
    private val assistantToolFactory: AssistantToolFactory,
    private val subAssistantRunCoordinator: SubAssistantRunCoordinator,
    private val turnFinalizer: TurnFinalizer,
    private val subAssistantLifecycle: SubAssistantLifecycle,
    private val runtimeRegistry: ConversationRuntimeRegistry,
    private val commandCoordinator: ConversationCommandCoordinator,
    private val recoveryGate: ApplicationRecoveryGate,
    private val chatErrorStore: ChatErrorStore,
    private val sideEffects: GenerationSideEffects,
    private val artifactUseCase: ArtifactUseCase,
    private val titleCoordinator: ConversationTitleCoordinator,
) {

    // 生成完成流
    private val _completedSpeech = MutableSharedFlow<ConversationSpeechCompletion>()
    internal val completedSpeech: SharedFlow<ConversationSpeechCompletion> = _completedSpeech.asSharedFlow()

    // 前台状态管理
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> _isForeground.value = true
            Lifecycle.Event.ON_STOP -> _isForeground.value = false
            else -> {}
        }
    }

    init {
        // 添加生命周期观察者
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        // 预加载 loop 声音反馈资源（生成副作用域）
        sideEffects.preloadSoundEffects()
    }

    // ---- Runtime 管理 ----

    private fun requireRuntime(conversationId: Uuid): ConversationRuntime {
        return runtimeRegistry.requireRuntime(conversationId)
    }

    // ---- 对话状态访问 ----

    private fun liveSnapshot(conversationId: Uuid): ConversationAggregateSnapshot =
        runtimeRegistry.requireRuntime(conversationId).durable

    // ---- 发送消息 ----

    private suspend fun <T> withUiTarget(
        target: ConversationCommandTarget,
        expectedAssistantId: me.rerere.common.configuration.ConfigurationReference? = null,
        operation: suspend (ConversationRuntime) -> T,
    ): T {
        recoveryGate.awaitReady()
        return sessions.withSelectedRealmSelection(target.selection) {
            suspend fun execute(): T =
                commandCoordinator.withRootHeaders(target.selection.access.scope, listOf(target.conversationId)) {
                    target.requireOpen()
                    val runtime = requireRuntime(target.conversationId)
                    if (expectedAssistantId != null) {
                        check(runtime.durable.header.assistantId == expectedAssistantId) { "conversation_assistant_changed" }
                    }
                    operation(runtime)
                }
            if (expectedAssistantId == null) execute()
            else settingsStore.withResolvedConfiguration(target.selection.access.scope, sessions.state.value) { configuration ->
                // Keep admission and definition deletion ordered without requiring a usable model to queue a draft.
                check(expectedAssistantId in configuration.assistants) { "conversation_assistant_unavailable" }
                check(configuration.access(net.weero.measix.pilot.data.configuration.ConfigurationCategory.ASSISTANT,
                    expectedAssistantId).canSelect) { "conversation_assistant_unavailable" }
                execute()
            }
        }
    }

    private suspend fun <T> withRequest(
        access: RealmAccess,
        runtime: ConversationRuntime,
        turnId: Uuid,
        tree: Boolean = false,
        operation: suspend () -> T,
    ): T = sessions.withRealmAccess(access) {
        withRequestOwner(access, runtime, turnId, tree, operation)
    }

    /** Caller holds the Session boundary; configuration, when needed, is locked before the tree. */
    private suspend fun <T> withRequestOwner(
        access: RealmAccess,
        runtime: ConversationRuntime,
        turnId: Uuid,
        tree: Boolean,
        operation: suspend () -> T,
    ): T {
        val worker = requireNotNull(currentCoroutineContext()[Job])
        suspend fun execute(): T {
            worker.ensureActive()
            check(runtimeRegistry.findRuntime(runtime.id) === runtime && runtime.currentWorker() === worker &&
                runtime.currentGenerationTurnId() == turnId) { "conversation_request_owner_changed" }
            return operation()
        }
        return if (tree) commandCoordinator.withRootTree(access.scope, runtime.id, requestWorker = worker) { execute() }
        else commandCoordinator.withRootHeaders(access.scope, listOf(runtime.id)) { execute() }
    }

    /** Installation accepts a request; the page owns only the wait, never the accepted worker. */
    private suspend fun startRequest(
        target: ConversationCommandTarget,
        content: List<UIMessagePart>,
        artifactDraftScope: ArtifactDraftScope?,
        errorTitle: Int,
        onFailure: suspend (Throwable, ArtifactSubmission?) -> Unit = { _, _ -> },
        queueForAssistant: me.rerere.common.configuration.ConfigurationReference? = null,
        queuedAnswer: Boolean = true,
        operation: suspend (ConversationRuntime, Uuid, ArtifactSubmission?) -> InputQueueRequestOutcome,
    ): RequestAdmission {
        recoveryGate.awaitReady()
        fun reportFailure(error: Exception) {
            logDiagnosticFailure(TAG, "Conversation request preparation or cleanup failed", error)
            chatErrorStore.add(ChatError(
                title = context.getString(errorTitle),
                detail = error.userVisibleDiagnostic(),
                conversationId = target.conversationId,
            ))
        }
        val submission = artifactDraftScope?.claimSubmission(target, content)
        val turnId = Uuid.random()
        var accepted = false
        var queueEpoch = 0L
        var outcome = InputQueueRequestOutcome.FAILED
        var installedRuntime: ConversationRuntime? = null
        var queuedReceipt: QueuedInputReceipt? = null
        val ready = CompletableDeferred<Pair<ConversationRuntime, InstalledTurnWorker>?>()
        val job = appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val requestWorker = requireNotNull(coroutineContext[Job])
            var previousCleanupAttempted = false
            suspend fun closePrevious(runtime: ConversationRuntime, installed: InstalledTurnWorker) = withContext(NonCancellable) {
                previousCleanupAttempted = true
                installed.previousWorker?.join()
                turnFinalizer.finalizeSupersededTurn(runtime.id, installed.previousTurnId)
                installed.previousTurnId?.let { runtime.releaseTurnWorker(it, installed.previousWorker) }
                check(runtime.snapshot.value.stream == null) { "previous_turn_still_pending" }
            }
            try {
                val (runtime, installed) = ready.await() ?: return@launch
                closePrevious(runtime, installed)
                currentCoroutineContext().ensureActive()
                outcome = operation(runtime, turnId, submission)
            } catch (cancelled: CancellationException) {
                outcome = InputQueueRequestOutcome.CANCELLED
                withContext(NonCancellable) {
                    if (ready.await() != null) onFailure(cancelled, submission)
                }
                throw cancelled
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    if (ready.await() != null) onFailure(error, submission)
                }
                reportFailure(error)
            } finally {
                withContext(NonCancellable) {
                    ready.await()?.let { (runtime, installed) ->
                        try {
                            if (!previousCleanupAttempted) closePrevious(runtime, installed)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            reportFailure(error)
                        } finally {
                            submission?.close()
                            releaseRequestWorker(runtime, turnId, requestWorker)
                        }
                    }
                }
            }
        }
        job.invokeOnCompletion { cause ->
            installedRuntime?.let { runtime -> notifyQueueRequestFinished(runtime, job, queueEpoch,
                if (cause != null && outcome == InputQueueRequestOutcome.COMPLETED) InputQueueRequestOutcome.FAILED else outcome) }
        }
        try {
            withUiTarget(target, queueForAssistant) { runtime ->
                currentCoroutineContext().ensureActive()
                check(!job.isCancelled) { "conversation_request_scope_closed" }
                withContext(NonCancellable) {
                    if (queueForAssistant != null) {
                        check(runtime.durable.header.assistantId == queueForAssistant) { "conversation_assistant_changed" }
                        if (runtime.mustQueueInput()) {
                            val item = runtime.enqueueInput(target.selection, queueForAssistant, content, queuedAnswer, submission)
                            queuedReceipt = QueuedInputReceipt(runtime.id, item.id, item.version)
                            accepted = true
                            ready.complete(null)
                            job.cancel()
                            return@withContext
                        }
                    }
                    queueEpoch = runtime.inputQueue.value.epoch
                    val installed = runtimeRegistry.installAndStartTurnWorker(
                        runtime.id, turnId, job, supersedeReason = TurnTerminalReasons.SUPERSEDED_BY_NEW_TURN,
                        requireQueueOutcome = true,
                    )
                    accepted = true
                    installedRuntime = runtime
                    ready.complete(runtime to installed)
                }
            }
            return queuedReceipt?.let {
                requestQueueDispatch(target.conversationId)
                RequestAdmission.Queued(it)
            } ?: RequestAdmission.Started(turnId)
        } finally {
            if (!accepted) {
                ready.complete(null)
                job.cancel()
                if (submission != null) requireNotNull(artifactDraftScope).returnToDraft(submission)
            }
        }
    }

    internal suspend fun enqueueMessage(
        target: ConversationAssistantTarget,
        content: List<UIMessagePart>,
        answer: Boolean,
        draft: ArtifactDraftScope?,
    ): QueuedInputReceipt? {
        if (content.isEmptyInputMessage()) return null
        val submission = draft?.claimSubmission(target.conversation, content)
        var accepted = false
        try {
            val item = withUiTarget(target.conversation, target.assistantId) { runtime ->
                currentCoroutineContext().ensureActive()
                runtime.enqueueInput(target.conversation.selection, target.assistantId, content, answer, submission)
                    .also { accepted = true }
            }
            requestQueueDispatch(target.conversation.conversationId)
            return QueuedInputReceipt(target.conversation.conversationId, item.id, item.version)
        } finally {
            if (!accepted && submission != null) requireNotNull(draft).returnToDraft(submission)
        }
    }

    internal suspend fun removeQueuedInput(target: ConversationCommandTarget, itemId: Uuid, version: Long) {
        withUiTarget(target) { it.removeQueuedInput(itemId, version) }
        requestQueueDispatch(target.conversationId)
    }

    internal suspend fun clearQueuedInputs(target: ConversationCommandTarget, expectedVersions: Map<Uuid, Long>) {
        withUiTarget(target) { runtime -> runtime.clearQueuedInputs(expectedVersions) }
    }

    internal suspend fun beginQueuedInputEdit(
        target: ConversationAssistantTarget,
        itemId: Uuid,
        version: Long,
    ): QueueInputEditLease {
        var created: QueueInputEditLease? = null
        var handedOff = false
        try {
            val lease = withUiTarget(target.conversation, target.assistantId) { runtime ->
                check(runtime.durable.header.assistantId == target.assistantId) { "conversation_assistant_changed" }
                check(runtime.inputQueue.value.items.singleOrNull { it.id == itemId && it.version == version }
                    ?.selection?.access == target.conversation.selection.access) { "queue_edit_changed" }
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    val item = runtime.beginQueuedInputEdit(itemId, version)
                    try {
                        check(item.selection.access == target.conversation.selection.access) { "queue_session_mismatch" }
                        QueueInputEditLease(target, runtime, item, createDraft = { requireEditor ->
                            artifactUseCase.openDraftScope(ConversationCommandTarget(runtime.id, target.conversation.selection) {
                                target.conversation.requireOpen()
                                requireEditor()
                            })
                        }, onClose = ::closeQueuedInputEdit).also { editor ->
                            created = editor
                            runtime.registerQueuedInputEditCleanup(item.id, item.version, requireNotNull(item.editToken), editor::close)
                        }
                    } catch (error: Throwable) {
                        runtime.cancelQueuedInputEdit(item.id, item.version, requireNotNull(item.editToken))
                        throw error
                    }
                }
            }
            val observer = appScope.launch {
                try {
                    combine(lease.runtime.inputQueue, lease.runtime.snapshot, sessions.observeSelectedRealmSelection()) { queue, snapshot, selection ->
                        selection != lease.target.conversation.selection || snapshot.durable.header.assistantId != lease.target.assistantId ||
                            (queue.epoch != lease.initialQueueEpoch && queue.pauseReason == InputQueuePauseReason.CONTEXT_CHANGED) || queue.items.none {
                            it.id == lease.itemId && it.version == lease.version && it.editToken == lease.editToken
                        }
                    }.first { it }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    reportQueuedInputEditFailure(lease, error)
                } finally { lease.close() }
            }
            lease.observeWith(observer)
            currentCoroutineContext().ensureActive()
            handedOff = true
            return lease
        } finally {
            if (!handedOff) created?.close()
        }
    }

    internal suspend fun saveQueuedInputEdit(
        lease: QueueInputEditLease,
        parts: List<UIMessagePart>,
    ): QueuedInputReceipt? {
        if (parts.isEmptyInputMessage()) return null
        lease.beginSave()
        var replacement: ArtifactSubmission? = null
        var accepted = false
        try {
            replacement = lease.draft.claimSubmission(lease.draft.target, parts)
            withUiTarget(lease.target.conversation, lease.target.assistantId) { runtime ->
                requireQueuedInputEdit(lease, runtime)
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    lease.acceptReplacement {
                        try {
                            runtime.replaceQueuedInputContents(lease.itemId, lease.version, lease.editToken, parts, replacement)
                        } finally {
                            // The Runtime closes the edit lease after accepting its replacement.
                            accepted = runtime.inputQueue.value.items.any { item ->
                                item.id == lease.itemId && item.version == lease.version + 1 &&
                                    item.submission === replacement && item.editToken == null
                            }
                        }
                    }
                }
            }
            requestQueueDispatch(lease.runtime.id)
            return QueuedInputReceipt(lease.runtime.id, lease.itemId, lease.version + 1)
        } finally {
            withContext(NonCancellable) {
                try {
                    if (!accepted) replacement?.let { lease.draft.returnToDraft(it) }
                } finally {
                    lease.finishSave()
                    if (accepted) lease.close()
                }
            }
        }
    }

    internal suspend fun queuedInputEditPreviews(
        lease: QueueInputEditLease,
        parts: List<UIMessagePart>,
    ): Map<String, ArtifactInputPreview> {
        suspend fun verify() = withUiTarget(lease.target.conversation) { runtime -> requireQueuedInputEdit(lease, runtime) }
        verify()
        fun attachmentUrl(part: UIMessagePart): String? = when (part) {
            is UIMessagePart.Image -> part.url
            is UIMessagePart.Audio -> part.url
            is UIMessagePart.Video -> part.url
            is UIMessagePart.Document -> part.url
            else -> null
        }
        val borrowedUris = lease.parts.mapNotNull(::attachmentUrl).toSet()
        val (originalParts, newParts) = parts.partition { attachmentUrl(it) in borrowedUris }
        val originals = lease.original.submission?.describeInputs(originalParts, ::verify)
            ?: lease.draft.describeInputs(originalParts)
        // The two owners each take Session independently; never call one while the other holds it.
        val added = lease.draft.describeInputs(newParts)
        verify()
        return originals + added
    }

    private fun requireQueuedInputEdit(lease: QueueInputEditLease, runtime: ConversationRuntime) {
        lease.requireReadable()
        check(runtime === lease.runtime && runtime.durable.header.assistantId == lease.target.assistantId) { "queue_edit_context_changed" }
        check(runtime.inputQueue.value.items.any { item ->
            item.id == lease.itemId && item.version == lease.version && item.editToken == lease.editToken &&
                item.selection.access == lease.target.conversation.selection.access
        }) { "queue_edit_changed" }
    }

    private fun closeQueuedInputEdit(lease: QueueInputEditLease, closeFailure: Throwable?) {
        appScope.launch {
            try {
                closeFailure?.let { reportQueuedInputEditFailure(lease, it) }
                commandCoordinator.withResidentRuntime(lease.runtime.id) { current ->
                    if (current === lease.runtime) current.cancelQueuedInputEdit(lease.itemId, lease.version, lease.editToken)
                }
                requestQueueDispatch(lease.runtime.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportQueuedInputEditFailure(lease, error)
            }
        }
    }

    private fun reportQueuedInputEditFailure(lease: QueueInputEditLease, error: Throwable) {
        logDiagnosticFailure(TAG, "Queued input editing failed", error)
        chatErrorStore.add(ChatError(title = context.getString(R.string.error_title_send_message),
            detail = error.userVisibleDiagnostic(), conversationId = lease.runtime.id))
    }

    internal suspend fun resumeInputQueue(target: ConversationAssistantTarget) {
        withUiTarget(target.conversation) { it.resumeInputQueue(target.conversation.selection, target.assistantId) }
        requestQueueDispatch(target.conversation.conversationId)
    }

    private fun notifyQueueRequestFinished(runtime: ConversationRuntime, worker: Job, epoch: Long, result: InputQueueRequestOutcome) {
        appScope.launch {
            try {
                val reconciled = commandCoordinator.withResidentRuntime(runtime.id) { current ->
                    current === runtime && current.reconcileInputQueueOutcome(worker, epoch, result)
                }
                if (reconciled == true) dispatchQueuedInput(runtime.id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { reportQueueFailure(runtime.id, error) }
        }
    }

    private fun requestQueueDispatch(conversationId: Uuid) {
        appScope.launch {
            try { dispatchQueuedInput(conversationId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { reportQueueFailure(conversationId, error) }
        }
    }

    private fun reportQueueFailure(conversationId: Uuid, error: Exception) {
        chatErrorStore.add(error, conversationId, context.getString(R.string.error_title_send_message))
    }

    private suspend fun dispatchQueuedInput(conversationId: Uuid) {
        recoveryGate.awaitReady()
        val runtime = runtimeRegistry.findRuntime(conversationId) ?: return
        val dispatch = runtime.nextQueuedInputDispatch() ?: return
        val original = runtime.inputQueue.value.items.firstOrNull { it.id == dispatch.itemId && it.version == dispatch.version } ?: return
        val turnId = Uuid.random()
        val ready = CompletableDeferred<QueuedConversationInput?>()
        var accepted = false
        var result = InputQueueRequestOutcome.FAILED
        // Enter cleanup before admission: cancellation after installation may beat the first resumed instruction.
        val worker = appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val requestWorker = requireNotNull(coroutineContext[Job])
            var acceptedItem: QueuedConversationInput? = null
            var committed = false
            var settled = false
            try {
                val item = ready.await() ?: return@launch
                acceptedItem = item
                val processed = appendRequestInput(runtime, turnId, item.selection.access, item.userMessageId, item.parts) {
                    committed = true
                    runtime.settleQueuedInputSubmission(dispatch, true)
                    settled = true
                }
                item.submission?.publishCommittedReferences(processed)
                result = if (item.answer) launchRun(runtime.id, turnId, launch = TurnLaunch.Start(item.selection.access)).queueOutcome()
                    else InputQueueRequestOutcome.COMPLETED
            } catch (cancelled: CancellationException) {
                result = InputQueueRequestOutcome.CANCELLED
                throw cancelled
            } catch (error: Exception) {
                result = InputQueueRequestOutcome.FAILED
                reportQueueFailure(runtime.id, error)
            } finally {
                withContext(NonCancellable) {
                    // Admission always completes ready, including rejection. An accepted item must settle
                    // even when cancellation prevented ready.await from returning to the request body.
                    val item = acceptedItem ?: ready.await()
                    if (item != null) try {
                        committed = committed || runtime.durable.nodes.any { node -> node.messages.any { it.id == item.userMessageId } }
                        if (!settled) commandCoordinator.withResidentRuntime(runtime.id) { current ->
                            check(current === runtime) { "queue_runtime_changed" }
                            current.settleQueuedInputSubmission(dispatch, committed)
                        }
                        if (committed) item.submission?.close()
                    } finally {
                        releaseRequestWorker(runtime, turnId, requestWorker)
                    }
                }
            }
        }
        worker.invokeOnCompletion { cause ->
            if (accepted) notifyQueueRequestFinished(runtime, worker, dispatch.epoch,
                if (cause != null && result == InputQueueRequestOutcome.COMPLETED) InputQueueRequestOutcome.FAILED else result)
        }
        try {
            sessions.withSelectedRealmSelection(original.selection) {
                commandCoordinator.withRootHeaders(original.selection.access.scope, listOf(conversationId)) {
                    if (runtimeRegistry.findRuntime(conversationId) !== runtime) return@withRootHeaders
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        val item = runtimeRegistry.installAndStartQueuedInputWorker(conversationId, dispatch,
                            original.selection, turnId, worker)
                        accepted = item != null
                        ready.complete(item)
                    }
                }
            }
        } catch (error: net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException) {
            commandCoordinator.withResidentRuntime(runtime.id) { current ->
                if (current === runtime && current.inputQueue.value.epoch == dispatch.epoch)
                    current.pauseInputQueue(InputQueuePauseReason.REALM_CHANGED)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            commandCoordinator.withResidentRuntime(runtime.id) { current ->
                if (current === runtime && current.inputQueue.value.epoch == dispatch.epoch)
                    current.pauseInputQueue(InputQueuePauseReason.REQUEST_FAILED)
            }
            throw error
        } finally {
            if (!accepted) {
                ready.complete(null)
                worker.cancel()
            }
        }
    }

    suspend fun sendMessage(
        target: ConversationCommandTarget,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        artifactDraftScope: ArtifactDraftScope? = null,
    ): SendMessageReceipt? = (sendMessageWithAdmission(target, content, answer, artifactDraftScope, null)
        as? ChatInputSubmissionReceipt.Sent)?.receipt

    internal suspend fun submitChatInput(
        target: ConversationAssistantTarget, content: List<UIMessagePart>, answer: Boolean = true,
        artifactDraftScope: ArtifactDraftScope? = null,
    ): ChatInputSubmissionReceipt? = sendMessageWithAdmission(target.conversation, content, answer, artifactDraftScope, target.assistantId)

    private suspend fun sendMessageWithAdmission(
        target: ConversationCommandTarget, content: List<UIMessagePart>, answer: Boolean,
        artifactDraftScope: ArtifactDraftScope?, queueForAssistant: me.rerere.common.configuration.ConfigurationReference?,
    ): ChatInputSubmissionReceipt? {
        if (content.isEmptyInputMessage()) return null
        val userMessageId = Uuid.random()
        val access = target.selection.access
        val appended = CompletableDeferred<Boolean>()
        val admission = startRequest(target, content, artifactDraftScope, R.string.error_title_send_message,
            queueForAssistant = queueForAssistant, queuedAnswer = answer,
            onFailure = { error, submission ->
                // Cancellation can arrive after the command committed but before its caller resumed.
                if (!appended.isCompleted) {
                    val committed = runtimeRegistry.findRuntime(target.conversationId)?.durable?.nodes?.any { node ->
                        node.messages.any { message -> message.id == userMessageId }
                    } == true
                    try {
                        if (!committed && submission != null) {
                            requireNotNull(artifactDraftScope).returnToDraft(submission)
                        }
                        appended.complete(committed)
                    } catch (cleanup: Throwable) {
                        error.addSuppressed(cleanup)
                        appended.completeExceptionally(error)
                    }
                }
            },
        ) { runtime, turnId, submission ->
            val processed = appendRequestInput(runtime, turnId, access, userMessageId, content) { appended.complete(true) }
            // Publication follows the message transaction, without holding Session/conversation locks.
            submission?.publishCommittedReferences(processed)
            if (answer) launchRun(runtime.id, turnId, launch = TurnLaunch.Start(access)).queueOutcome()
            else InputQueueRequestOutcome.COMPLETED
        }
        return when (admission) {
            is RequestAdmission.Queued -> ChatInputSubmissionReceipt.Queued(admission.receipt)
            is RequestAdmission.Started -> if (appended.await()) ChatInputSubmissionReceipt.Sent(
                SendMessageReceipt(target.conversationId, admission.turnId, userMessageId)) else null
        }
    }

    private suspend fun appendRequestInput(
        runtime: ConversationRuntime, turnId: Uuid, access: RealmAccess, userMessageId: Uuid,
        content: List<UIMessagePart>, onCommitted: () -> Unit = {},
    ): List<UIMessagePart> = sessions.withRealmAccess(access) {
        if (access is RealmAccess.Enterprise) {
            check((sessions.state.value as? net.weero.measix.pilot.data.enterprise.EnterpriseState.Available)
                ?.manifest?.phase == net.weero.measix.pilot.data.enterprise.EnterpriseSessionPhase.READY) {
                "enterprise_session_not_ready"
            }
        }
        settingsStore.withResolvedConfiguration(access.scope, sessions.state.value) { configuration ->
            withRequestOwner(access, runtime, turnId, tree = true) {
                val snapshot = runtime.durable
                if (snapshot.header.newConversation) {
                    snapshot.opening?.let { requireCurrentStarterOpening(it, snapshot.header.assistantId, configuration) }
                }
                val assistant = configuration.assistants[snapshot.header.assistantId]
                    ?: error("conversation_assistant_unavailable")
                val parts = preprocessUserInputParts(content, assistant)
                val message = UIMessage(id = userMessageId, role = MessageRole.USER, parts = parts)
                val localTitle = deriveLocalConversationTitle(message)
                commandCoordinator.executeOrThrow(runtime.id, AppendUserMessage(message, initialTitle = localTitle))
                onCommitted()
                if (snapshot.header.title.isBlank()) titleCoordinator.synchronize(runtime.id, runtime.durable.header.title, localTitle)
                parts
            }
        }
    }

    suspend fun editAndResend(
        target: ConversationCommandTarget,
        messageId: Uuid,
        content: List<UIMessagePart>,
        artifactDraftScope: ArtifactDraftScope? = null,
    ): SendMessageReceipt? {
        if (content.isEmptyInputMessage()) return null
        withUiTarget(target) { it.pauseInputQueue(InputQueuePauseReason.CONTEXT_CHANGED, contextChanged = true) }
        val userMessageId = Uuid.random()
        val access = target.selection.access
        val edited = CompletableDeferred<Boolean>()
        val admission = startRequest(target, content, artifactDraftScope, R.string.error_title_send_message,
            onFailure = { error, submission ->
                if (!edited.isCompleted) {
                    val committed = runtimeRegistry.findRuntime(target.conversationId)?.durable?.nodes?.any { node ->
                        node.messages.any { message -> message.id == userMessageId }
                    } == true
                    try {
                        if (!committed && submission != null) {
                            requireNotNull(artifactDraftScope).returnToDraft(submission)
                        }
                        edited.complete(committed)
                    } catch (cleanup: Throwable) {
                        error.addSuppressed(cleanup)
                        edited.completeExceptionally(error)
                    }
                }
            },
        ) { runtime, turnId, submission ->
            val configuration = modelExecutions.read(access).configuration
            val processed = withRequest(access, runtime, turnId, tree = true) {
                val snapshot = subAssistantLifecycle.requireClosedRunsBeforeTreeMutation(runtime.durable)
                val nodeIndex = snapshot.nodes.indexOfFirst { node -> node.messages.any { it.id == messageId } }
                check(nodeIndex >= 0) { "Message not found: $messageId" }
                val node = snapshot.nodes[nodeIndex]
                check(node.messages.first { it.id == messageId }.role == MessageRole.USER) { "edit-and-resend requires a USER message" }
                check(snapshot.modelContextEntries.none { it.ownerNodeId == node.id &&
                    it.payload.source is net.weero.measix.pilot.data.model.ConversationContextSource.HistorySummary }) {
                    "summary_cannot_be_sent_as_user_input"
                }
                val assistant = configuration.assistants[snapshot.header.assistantId] ?: error("conversation_assistant_unavailable")
                val parts = preprocessUserInputParts(content, assistant)
                commandCoordinator.executeOrThrow(runtime.id, EditMessageVariant(node.id,
                    UIMessage(id = userMessageId, role = MessageRole.USER, parts = parts), truncateAfterNode = true))
                edited.complete(true)
                subAssistantLifecycle.applyRetentionAfterTreeMutation(runtime.id)
                parts
            }
            submission?.publishCommittedReferences(processed)
            launchRun(runtime.id, turnId, launch = TurnLaunch.Start(access)).queueOutcome()
        }
        return if (edited.await()) SendMessageReceipt(target.conversationId,
            (admission as RequestAdmission.Started).turnId, userMessageId) else null
    }

    suspend fun regenerateAtMessage(
        target: ConversationCommandTarget,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true,
    ) {
        withUiTarget(target) { it.pauseInputQueue(InputQueuePauseReason.CONTEXT_CHANGED, contextChanged = true) }
        val access = target.selection.access
        startRequest(target, emptyList(), null, R.string.error_title_regenerate_message) { runtime, turnId, _ ->
            withRequest(access, runtime, turnId, tree = true) {
                val snapshot = subAssistantLifecycle.requireClosedRunsBeforeTreeMutation(runtime.durable)
                val nodeIndex = snapshot.nodes.indexOfFirst { node -> node.messages.any { it.id == message.id } }
                check(nodeIndex >= 0) { "Message not found: ${message.id}" }
                val original = snapshot.nodes[nodeIndex].messages.first { it.id == message.id }
                check(original.role == message.role) { "regeneration_message_changed" }
                check(snapshot.modelContextEntries.none { it.ownerNodeId == snapshot.nodes[nodeIndex].id &&
                    it.payload.source is net.weero.measix.pilot.data.model.ConversationContextSource.HistorySummary }) {
                    "summary_cannot_be_sent_as_user_input"
                }
                if (original.role == MessageRole.USER || regenerateAssistantMsg) {
                    commandCoordinator.executeOrThrow(runtime.id, TruncateToNodeIndex(nodeIndexInclusive = nodeIndex))
                    subAssistantLifecycle.applyRetentionAfterTreeMutation(runtime.id)
                } else commandCoordinator.executeOrThrow(runtime.id, ReplaceMessageTree(snapshot.nodes))
            }
            if (message.role == MessageRole.USER || regenerateAssistantMsg) {
                launchRun(runtime.id, turnId, launch = TurnLaunch.Start(access)).queueOutcome()
            } else InputQueueRequestOutcome.COMPLETED
        }
    }

    suspend fun submitToolDecision(
        target: ConversationCommandTarget,
        locator: ToolCallLocator,
        decision: ToolInteractionDecision,
    ) {
        val runtime = withUiTarget(target) { it }
        runtime.withToolApprovalLock {
            // Pending is published just before worker completion. Never join under Session/root locks.
            runtime.awaitCurrentWorker()
            withUiTarget(target) { current ->
                check(current === runtime) { "conversation_request_owner_changed" }
                val owner = requireNotNull(runtime.snapshot.value.stream) { "tool interaction has no active turn owner" }
                val previousWorker = requireNotNull(runtime.currentWorker()) { "tool interaction has no worker" }
                val original = runtime.requireTurnContext(owner.turnId, previousWorker)
                check(original.realmAccess == target.selection.access) { "tool_interaction_session_changed" }
                check(runtime.isAwaitingUser(owner.turnId)) { "tool interaction is not awaiting user" }
                currentCoroutineContext().ensureActive()
                // The decision commit and continuation installation are one accepted operation.
                withContext(NonCancellable) {
                    applyToolInteractionDecision(
                        locator, decision, awaitPreviousGeneration = {}, currentSnapshot = { runtime.snapshot.value },
                        submit = { commandCoordinator.executeOrThrow(runtime.id, it) },
                        onMoreApprovalsPending = {},
                        continueTurn = { owner, _ ->
                            val handle = TurnHandle(runtime.id, owner.epoch, owner.turnId, owner.assistantMessageId)
                            val queueEpoch = runtime.inputQueue.value.epoch
                            var result = InputQueueRequestOutcome.FAILED
                            var installed = false
                            val resumeJob = appScope.launch(start = CoroutineStart.LAZY) {
                                val worker = requireNotNull(coroutineContext[Job])
                                try {
                                    result = launchRun(runtime.id, owner.turnId, launch = TurnLaunch.Continue(original.realmAccess)).queueOutcome()
                                } catch (cancelled: CancellationException) {
                                    result = InputQueueRequestOutcome.CANCELLED
                                    throw cancelled
                                } finally { withContext(NonCancellable) { releaseRequestWorker(runtime, owner.turnId, worker) } }
                            }
                            resumeJob.invokeOnCompletion { cause ->
                                if (installed) notifyQueueRequestFinished(runtime, resumeJob, queueEpoch,
                                    if (cause != null && result == InputQueueRequestOutcome.COMPLETED) InputQueueRequestOutcome.FAILED else result)
                            }
                            try {
                                installed = true
                                runtimeRegistry.installAndStartUserInteractionContinuation(runtime.id, handle, resumeJob, requireQueueOutcome = true)
                            }
                            catch (error: Throwable) { installed = false; resumeJob.cancel(); throw error }
                        },
                    )
                }
            }
        }
    }

    /** Cleanup failure keeps the Runtime owner retryable and must remain visible to its conversation. */
    private suspend fun releaseRequestWorker(runtime: ConversationRuntime, turnId: Uuid, worker: Job) {
        try {
            runtime.releaseTurnWorker(turnId, worker)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logDiagnosticFailure(TAG, "Conversation request resource cleanup failed", error)
            chatErrorStore.add(error, runtime.id, context.getString(R.string.error_title_operation))
            throw error
        }
    }

    private suspend fun launchRun(
        conversationId: Uuid,
        turnId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        launch: TurnLaunch,
    ): TurnRunResult {
        val entry = launch.entry
        // 用户可见地开始或继续本会话生成后，请求平台保活；何时停止由 service 依据
        // conversationActivities 投影自决，这里只做单向请求，不读取任何运行结果。
        GenerationForegroundLifetime.ensureStarted(context)
        var inFlightAssistantMessageId: Uuid? = null
        var senderName: String? = null
        var generationSoundEnabled = false
        var turnCommitter: TurnCommitter? = null
        var startedRuntime: ConversationRuntime? = null
        try {
            val runtime = requireRuntime(conversationId)
            startedRuntime = runtime
            val launchPolicy = turnLaunchPolicy(entry, runtime.snapshot.value.stream, turnId, messageRange)

            if (launchPolicy.runStructuralPreflight) withRequest(launch.realmAccess, runtime, turnId, tree = true) {
                // Structural maintenance belongs exclusively to START. Approval and denial both
                // continue the existing turn and must never submit tree commands while it is active.
                commandCoordinator.executeOrThrow(conversationId, UpdateHeader(suggestions = emptyList()))
                checkInvalidMessages(conversationId)
                val attachmentRefBackfills = planDurableAttachmentRefBackfills(liveSnapshot(conversationId))
                if (attachmentRefBackfills.isNotEmpty()) {
                    commandCoordinator.executeOrThrow(
                        conversationId,
                        BackfillAttachmentRefs(attachmentRefBackfills),
                    )
                }
            }

            var snapshot = liveSnapshot(conversationId)
            val sourceMessages = if (messageRange != null) {
                snapshot.currentMessages().subList(messageRange.start, messageRange.endInclusive + 1)
            } else {
                snapshot.currentMessages()
            }
            inFlightAssistantMessageId = null
            val worker = requireNotNull(kotlinx.coroutines.currentCoroutineContext()[Job])
            // START 先做一次性 prepareLaunch（唯一允许 IO、可失败，此时尚无 Turn）。
            val launchPlan = when (entry) {
                TurnEntry.START -> {
                    val realmAccess = launch.realmAccess
                    val captured = modelExecutions.captureTurn(realmAccess, runtime, turnId,
                        requireNotNull(currentCoroutineContext()[Job]), snapshot.header.assistantId) {
                        turnFinalizer.stopInteraction(runtime, turnId, "managed_snapshot_required")
                    }
                    val settings = captured.userSettings
                    val assistant = captured.assistant
                    val model = captured.model.model
                    val mediaCapabilities = captured.mediaCapabilities
                    val memoryAccess = memoryService.captureExecution(realmAccess, assistant)
                    val mcpCapabilities = try {
                        mcpManager.prepareTurnCapabilities(realmAccess, captured, runtime, turnId, worker,
                            onCatalogRefresh = { serverId, serverName, error ->
                                chatErrorStore.updateMcpCatalogRefresh(conversationId, serverId,
                                    context.getString(R.string.mcp_catalog_refresh_failed),
                                    error?.let { "$serverName\n${it.detail ?: it.message}" })
                            }) {
                            turnFinalizer.stopInteraction(runtime, turnId, "managed_snapshot_required")
                        }
                    } catch (failure: McpTurnPreparationException) {
                        val message = when (failure.reason) {
                            McpTurnPreparationFailureReason.REFERENCE_UNAVAILABLE ->
                                context.getString(R.string.error_mcp_reference_unavailable, failure.serverNames.joinToString(", "))
                            McpTurnPreparationFailureReason.REQUIRED_GATEWAY_UNAVAILABLE ->
                                context.getString(R.string.error_mcp_required_gateway_unavailable, failure.serverNames.joinToString(", "))
                        }
                        throw IllegalStateException(message, failure)
                    }
                    val policyBlockedMcp = mcpCapabilities.serverOutcomes.filter {
                        it.state == McpServerCapabilityState.POLICY_BLOCKED
                    }
                    if (policyBlockedMcp.isNotEmpty()) {
                        chatErrorStore.add(
                            IllegalStateException(
                                context.getString(
                                    R.string.error_mcp_enterprise_policy_skipped,
                                    policyBlockedMcp.joinToString(", ") { it.serverName },
                                ),
                            ),
                            conversationId,
                        )
                    }
                    val unavailableMcp = mcpCapabilities.serverOutcomes.filter {
                        it.state != McpServerCapabilityState.READY
                            && it.state != McpServerCapabilityState.POLICY_BLOCKED
                    }
                    if (unavailableMcp.isNotEmpty()) {
                        chatErrorStore.add(
                            IllegalStateException(
                                context.getString(
                                    R.string.error_mcp_turn_capability_unavailable,
                                    unavailableMcp.joinToString(", ") { it.serverName },
                                ),
                            ),
                            conversationId,
                            title = context.getString(R.string.error_title_tool_unavailable),
                        )
                    }
                    senderName = if (assistant.useAssistantAvatar) {
                        assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
                    } else {
                        model.displayName
                    }
                    if (!model.abilities.contains(ModelAbility.TOOL) &&
                        (shouldUseExternalWebSearch(assistant, model) || mcpCapabilities.tools.isNotEmpty())
                    ) {
                        chatErrorStore.add(
                            IllegalStateException(context.getString(R.string.tools_warning)),
                            conversationId,
                            title = context.getString(R.string.error_title_tool_unavailable),
                        )
                    }
                    val turnTtsContext = TtsToolPlaybackContext(
                        sessionId = runtime.getTtsQueueSessionId(launchPolicy.reuseTtsQueue),
                        capture = speech.captureTurn(captured, realmAccess) {
                            turnFinalizer.stopInteraction(runtime, turnId, "managed_snapshot_required")
                        },
                        assistantId = assistant.id,
                        assistantName = assistant.name,
                        sourceType = TtsPlaybackSource.SourceType.NORMAL,
                    )
                    runtime.bindTtsPlaybackContext(turnTtsContext)
                    val regularTools = toolSetFactory.buildTools(
                        realmAccess = realmAccess,
                        assistant = assistant,
                        conversationId = conversationId,
                        settings = settings,
                        configuration = captured.configuration,
                        capabilityModel = model,
                        inspectionModel = captured.inspectionModel,
                        imageModel = captured.imageModel,
                        workspaceCwd = snapshot.header.workspaceCwd,
                        ttsPlaybackContext = turnTtsContext,
                        mcpCapabilities = mcpCapabilities,
                        additionalToolsBeforeMcp = assistantToolFactory.buildTools(
                            callerAssistant = assistant,
                            masterConversationId = conversationId,
                            realmAccess = realmAccess,
                            ttsPlaybackContext = turnTtsContext,
                        ),
                        onInvalidMcpServerNames = { invalidNames ->
                            chatErrorStore.add(
                                error = IllegalStateException(
                                    context.getString(
                                        R.string.error_mcp_invalid_server_name,
                                        invalidNames.joinToString(", "),
                                    ),
                                ),
                                conversationId = conversationId,
                            )
                        },
                    )
                    val tools = buildList {
                        if (memoryAccess != null) {
                            addAll(
                                buildMemoryTools(
                                    onCreation = { content -> memoryService.add(memoryAccess, content) },
                                    onUpdate = { id, content -> memoryService.update(memoryAccess, id, content) },
                                    onDelete = { id -> memoryService.delete(memoryAccess, id) },
                                    isStillAllowed = { memoryService.isAllowed(memoryAccess) },
                                ),
                            )
                        }
                        addAll(regularTools)
                    }
                    turnContextFactory.prepareLaunch(
                        realmAccess = realmAccess,
                        settings = settings,
                        assistant = assistant,
                        model = captured.model,
                        mediaCapabilities = mediaCapabilities,
                        conversationSystemPrompt = snapshot.header.customSystemPrompt,
                        conversationModeInjectionIds = snapshot.header.modeInjectionIds,
                        workspaceCwd = snapshot.header.workspaceCwd,
                        tools = tools,
                          opening = snapshot.opening,
                          disclosure = TurnDisclosureSource.capture(
                              configuration = captured.configuration,
                              assistant = assistant,
                              namespace = DisclosureNamespace(memoryAccess?.address?.owner?.storageId, assistant.id),
                              readConfiguration = { modelExecutions.read(realmAccess).configuration },
                              readMemory = { memoryAccess?.let { memoryService.read(it) }.orEmpty() },
                              validateMemory = { memoryAccess?.let { memoryService.requireAccess(it) } },
                          ),
                    )
                }

                TurnEntry.CONTINUE_USER_INTERACTION -> null
            }
            val started = withRequest(launch.realmAccess, runtime, turnId) {
                withContext(NonCancellable) {
                    when (entry) {
                        TurnEntry.START -> TurnCommitter.start(
                            commandCoordinator = commandCoordinator,
                            runtime = runtime,
                            turnId = turnId,
                            turnFinalizer = turnFinalizer,
                            model = requireNotNull(launchPlan).model.model,
                        )

                        TurnEntry.CONTINUE_USER_INTERACTION -> {
                            val resumableApprovalMessage = sourceMessages.lastOrNull()?.takeIf { message ->
                                message.role == MessageRole.ASSISTANT &&
                                    message.getTools().any {
                                        !it.hasReplayResult && it.canResumeResultAssembly
                                    }
                            }
                            check(resumableApprovalMessage != null) {
                                "active turn does not point to a resumable approval message"
                            }
                            TurnCommitter.continueActive(
                                commandCoordinator = commandCoordinator,
                                runtime = runtime,
                                expectedTurnId = turnId,
                                messages = sourceMessages,
                                turnFinalizer = turnFinalizer,
                            )
                        }
                    }.also { turnCommitter = it.turnCommitter }
                }
            }
            // 先认领终态 owner：materialize 若失败，本 catch 收口；即便收口本身抛错，
            // 外层 launchRun catch 仍能以同一 committer 兜底，绝不留 RUNNING-without-context。
            // StartTurn 事务已建立 Turn；materialize 只做纯绑定并交给 durable 槽，禁止 IO / 重读 Settings。
            // 纯绑定抛错即编程错误：以专用 reason 收口已启动的 Turn，绝不留下无 TurnContext 的 RUNNING。
            val turnContext = when (entry) {
                TurnEntry.START -> try {
                    val context = turnContextFactory.materialize(requireNotNull(launchPlan))
                    runtime.bindTurnContext(turnId, worker, context)
                    context
                } catch (error: Exception) {
                    withContext(NonCancellable) {
                        started.turnCommitter.finalizeOwnerFailure(
                            TurnOutcome.Failed(error, TurnTerminalReasons.TURN_CONTEXT_MATERIALIZE),
                        )
                    }
                    throw error
                }

                TurnEntry.CONTINUE_USER_INTERACTION ->
                    runtime.requireTurnContext(turnId, worker)
            }
            val displaySettings = settingsStore.userSettings.value
            generationSoundEnabled = displaySettings.displaySetting.enableMessageGenerationSoundEffect
            if (senderName == null) {
                val currentAssistant = displaySettings.getAssistantById(turnContext.assistant.id)
                senderName = if (currentAssistant?.useAssistantAvatar == true) {
                    turnContext.assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
                } else {
                    turnContext.model.model.displayName
                }
            }
            val activeTurnCommitter = started.turnCommitter
            inFlightAssistantMessageId = started.assistantMessageId
            if (entry == TurnEntry.START) {
                snapshot = liveSnapshot(conversationId)
            }
            val generationMessages = if (started.resumableMessage == null) {
                snapshot.currentMessages()
            } else {
                sourceMessages
            }
            val soundTracker = sideEffects.soundTracker(generationMessages.lastOrNull())
            val phaseReporter = runtime.livePhaseReporter()
            val speechContext = runtime.peekTtsPlaybackContext()?.takeIf { it.capture.interactionId == "int_$turnId" }
            val turnResult = turnRunner.run(
                TurnRunInputs(
                    turnContext = turnContext,
                    handle = started.handle,
                    reportProcessingText = runtime.processingReporter(),
                    // loop 的 typed 阶段推进本 Turn 的进程内 live phase（本会话不再忽略 onPhase）。
                    onPhase = { phase, _ -> phaseReporter(turnLivePhaseOf(phase)) },
                    messages = generationMessages,
                    assistantMessageId = started.assistantMessageId,
                    providerSessionId = conversationId.toString(),
                    inputTransformers = turnPipelineFactory.input(),
                    outputTransformers = turnPipelineFactory.output(),
                    onCheckpoint = { checkpoint ->
                        activeTurnCommitter.onCheckpoint(checkpoint)
                        try {
                            soundTracker.onCheckpoint(
                                checkpoint.assistantMessage,
                                enabled = isForeground.value && generationSoundEnabled,
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            android.util.Log.w(TAG, "Approval sound failed after checkpoint", error)
                        }
                    },
                    onAssistantObserved = activeTurnCommitter::observeAssistant,
                    requestContext = activeTurnCommitter.requestContext,
                    // 提交协议唯一实现——流式 delta 只动投影（永不落库），随后做 turn-owned 呈现。
                    onStreamDelta = { lastMessage ->
                        activeTurnCommitter.publishStream(lastMessage)
                        inFlightAssistantMessageId = lastMessage.id

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        val tools = lastMessage.getTools()
                        val executingToolLocalCallId = tools.lastOrNull { tool ->
                            runtime.currentTurnPresentation().toolLivePhases[
                                ToolCallLocator(lastMessage.id, tool.stepId, tool.localCallId)
                            ] == ToolLivePhase.EXECUTING
                        }?.localCallId
                        appEventBus.tryEmit(
                            AppEvent.ChatGenerationUpdate(
                                access = launch.realmAccess,
                                conversationId = conversationId,
                                lastMessage = lastMessage,
                                senderName = senderName.orEmpty(),
                                executingToolLocalCallId = executingToolLocalCallId,
                            )
                        )

                        // Streamed output can signal a completed step; user gates are heard only after checkpoint.
                        soundTracker.onStreaming(lastMessage, enabled = isForeground.value && generationSoundEnabled)
                    },
                    onResult = activeTurnCommitter::commitRunResult,
                    cancelReason = { runtime.peekCancelReason(started.handle.turnId) },
                )
            )

            if (turnResult is TurnOutcome.Completed && speechContext != null) {
                runtime.durable.currentMessages().firstOrNull { it.id == started.assistantMessageId }?.let { message ->
                    _completedSpeech.emit(ConversationSpeechCompletion(conversationId, turnId, message, speechContext))
                }
            }
            if (turnResult is TurnOutcome.Failed && isForeground.value && generationSoundEnabled) {
                sideEffects.playTurnFailedSound()
            }
            if (turnResult is TurnPause) {
                val active = requireNotNull(runtime.snapshot.value.stream) {
                    "pause checkpoint has no durable turn owner"
                }
                runtime.retainAwaitingUser(
                    TurnHandle(conversationId, active.epoch, active.turnId, active.assistantMessageId),
                )
            }
            applyTurnSideEffects(
                access = launch.realmAccess,
                conversationId = conversationId,
                result = turnResult,
                inFlightAssistantMessageId = inFlightAssistantMessageId,
                senderName = senderName.orEmpty(),
            )
            if (!shouldLaunchCompletionSideEffects(turnResult)) {
                return turnResult
            }
            val hasPendingApproval = liveSnapshot(conversationId)
                .currentMessages().lastOrNull()?.getTools()?.any { it.isPending } == true
            if (hasPendingApproval) {
                return turnResult
            }

            if (isForeground.value && generationSoundEnabled) {
                sideEffects.playTurnCompleteSound()
            }

            try {
                withRequest(launch.realmAccess, runtime, turnId) {
                    sideEffects.launchTitle(runtime, launch.realmAccess)
                    sideEffects.launchSuggestion(runtime, launch.realmAccess)
                }
            } catch (error: net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException) {
                if (error.reason != "enterprise_data_access_unavailable") throw error
            }
            return turnResult
        } catch (e: CancellationException) {
            val outcome = TurnOutcome.Cancelled(
                startedRuntime?.consumeCancelReason(turnId) ?: TurnTerminalReasons.USER_STOP
            )
            try {
                withContext(NonCancellable) {
                    turnCommitter?.finalizeOwnerFailure(outcome = outcome)
                }
            } catch (finalizationError: Exception) {
                e.addSuppressed(finalizationError)
            }
            applyTurnSideEffects(
                access = launch.realmAccess,
                conversationId = conversationId,
                result = outcome,
                inFlightAssistantMessageId = inFlightAssistantMessageId,
                senderName = senderName.orEmpty(),
            )
            throw e
        } catch (e: Exception) {
            var reportedError = e
            var outcome = TurnOutcome.fromFailure(e)
            try {
                withContext(NonCancellable) {
                    turnCommitter?.finalizeOwnerFailure(outcome = outcome)
                }
            } catch (finalizationError: Exception) {
                finalizationError.addSuppressed(e)
                reportedError = finalizationError
                outcome = TurnOutcome.fromFailure(finalizationError)
            }
            logDiagnosticFailure(TAG, "launchRun failed", reportedError)
            Logging.log(TAG, reportedError.userVisibleDiagnostic())
            if (isForeground.value && generationSoundEnabled) {
                sideEffects.playTurnFailedSound()
            }
            applyTurnSideEffects(
                access = launch.realmAccess,
                conversationId = conversationId,
                result = outcome,
                inFlightAssistantMessageId = inFlightAssistantMessageId,
                senderName = senderName.orEmpty(),
            )
            return outcome
        }
    }

    // ---- 检查无效消息 ----

    private suspend fun checkInvalidMessages(conversationId: Uuid) {
        val snapshot = liveSnapshot(conversationId)
        val messagesNodes = retainValidMessageNodes(snapshot.nodes)
        if (messagesNodes == snapshot.nodes) return
        // 无效消息清理 = 树替换（命令通道 delta 落库）
        commandCoordinator.executeOrThrow(conversationId, ReplaceMessageTree(messagesNodes))
    }

    /** 本会话终态副作用（通知与错误上报）；执行事实已由 FinalizeTurn 原子收口。 */
    private suspend fun applyTurnSideEffects(
        access: RealmAccess,
        conversationId: Uuid,
        result: TurnRunResult,
        inFlightAssistantMessageId: Uuid? = null,
        senderName: String,
    ) {
        withContext(NonCancellable) {
            val finalMessage = liveSnapshot(conversationId).currentMessages()
                .firstOrNull { it.id == inFlightAssistantMessageId }
            val terminalStatus = when (result) {
                is TurnOutcome.Failed -> MessageTerminalStatus.FAILED
                is TurnOutcome.Incomplete -> MessageTerminalStatus.INCOMPLETE
                else -> null
            }
            if (terminalStatus != null && inFlightAssistantMessageId != null) {
                val outcome = result as TurnOutcome
                terminalChatError(
                    context = context,
                    conversationId = conversationId,
                    messageId = inFlightAssistantMessageId,
                    status = finalMessage?.terminalStatus ?: terminalStatus,
                    reason = finalMessage?.terminalReason ?: outcome.terminalReason,
                    detail = finalMessage?.terminalDetail ?: outcome.terminalDetail,
                    modelId = finalMessage?.modelId,
                )?.let(chatErrorStore::add)
            } else if (result is TurnOutcome.Failed) {
                chatErrorStore.add(
                    error = result.error,
                    conversationId = conversationId,
                    title = context.getString(R.string.error_title_generation),
                    context = context,
                )
            }
            val pendingToolLocalCallId = (result as? TurnPause)
                ?.pendingInteractions
                ?.firstOrNull()
                ?.locator
                ?.localCallId
            if (finalMessage != null && pendingToolLocalCallId != null) {
                appEventBus.emit(
                    AppEvent.ChatGenerationAwaitingUser(
                        access = access,
                        conversationId = conversationId,
                        lastMessage = finalMessage,
                        senderName = senderName,
                        pendingToolLocalCallId = pendingToolLocalCallId,
                    )
                )
            } else {
                appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        access = access,
                        conversationId = conversationId,
                        senderName = senderName,
                        contentPreview = finalMessage?.toText()?.take(50)?.trim(),
                        notifyCompletion = result is TurnOutcome.Completed,
                    )
                )
            }
        }
    }
}

/** Backfill plans are derived from durable nodes, never from the per-read rendering overlay. */
internal fun planDurableAttachmentRefBackfills(
    snapshot: ConversationAggregateSnapshot,
) = AttachmentRefs.planBackfills(snapshot.nodes)

/** A completed reply carries its original speech authority; consumers never look up a newer turn's context. */
internal data class ConversationSpeechCompletion(
    val conversationId: Uuid,
    val turnId: Uuid,
    val message: UIMessage,
    val context: TtsToolPlaybackContext,
)
