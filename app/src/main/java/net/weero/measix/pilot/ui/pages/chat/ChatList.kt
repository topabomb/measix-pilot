package net.weero.measix.pilot.ui.pages.chat

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.ArrowDownDouble
import me.rerere.hugeicons.stroke.ArrowUpDouble
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Cancel01
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalScrollCaptureInProgress
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.ChatError
import net.weero.measix.pilot.service.ConversationStarterUiModel
import net.weero.measix.pilot.service.runtime.ConversationPresentationSnapshot
import net.weero.measix.pilot.service.runtime.ConversationPresentation
import net.weero.measix.pilot.service.runtime.TurnLivePhase
import net.weero.measix.pilot.service.runtime.ToolInteractionDecision
import net.weero.measix.pilot.ui.components.ai.ApprovalRequiredIndicator
import net.weero.measix.pilot.ui.adaptive.AdaptiveLayoutDefaults
import net.weero.measix.pilot.ui.components.message.ChatMessage
import net.weero.measix.pilot.ui.components.message.LocalAttachmentPreview
import net.weero.measix.pilot.ui.components.message.LocalConversationImages
import net.weero.measix.pilot.ui.components.message.collectMessageImages
import net.weero.measix.pilot.ui.components.ui.LocalImagePreviewActions
import net.weero.measix.pilot.ui.components.ui.LocalImagePreviewOverlay
import net.weero.measix.pilot.ui.components.ui.rememberImageBackgroundHost
import net.weero.measix.pilot.ui.theme.asChatChrome
import net.weero.measix.pilot.ui.components.ui.DocumentExportActions
import net.weero.measix.pilot.ui.components.ui.ErrorCardsDisplay
import net.weero.measix.pilot.ui.components.ui.ListSelectableItem
import net.weero.measix.pilot.ui.components.ui.ModelIconFallback
import net.weero.measix.pilot.ui.components.ui.ProviderConfigWarningCard
import net.weero.measix.pilot.ui.components.ui.RabbitLoadingIndicator
import net.weero.measix.pilot.ui.components.ui.Tooltip
import net.weero.measix.pilot.ui.hooks.ImeLazyListAutoScroller
import net.weero.measix.pilot.ui.theme.ChatFontProvider
import kotlin.uuid.Uuid

internal enum class ChatScrollIntent {
    FOLLOW_TAIL,
    VIEW_HISTORY,
    DRAGGING,
    AFTER_DRAG,
}

private const val TAG = "ChatList"
private const val LoadingIndicatorKey = "LoadingIndicator"
private const val ScrollBottomKey = "ScrollBottomKey"

@Composable
internal fun ChatList(
    detailSource: net.weero.measix.pilot.service.ConversationViewLease?,
    draftFolderLocation: @Composable () -> Unit,
    innerPadding: PaddingValues,
    snapshot: ConversationPresentationSnapshot,
    favoriteNodeIds: Set<Uuid>,
    state: LazyListState,
    scrollIntent: State<ChatScrollIntent>,
    onScrollIntentChange: (ChatScrollIntent) -> Unit,
    onReadHistory: () -> Unit,
    turnPresentation: ConversationPresentation,
    attachmentPreviews: Map<String, net.weero.measix.pilot.service.AttachmentPreview>,
    previewMode: Boolean,
    overviewNodeId: Uuid?,
    exportActions: DocumentExportActions,
    launchExportDocument: (Boolean, String) -> Unit,
    requestExportSelection: Boolean,
    onExportSelectionOpened: () -> Unit,
    settings: Settings,
    readiness: ConversationReadiness,
    assistant: Assistant?,
    starters: List<ConversationStarterUiModel>,
    hazeState: HazeState,
    errors: List<ChatError> = emptyList(),
    onDismissError: (Uuid) -> Unit = {},
    onClearAllErrors: () -> Unit = {},
    onRetryReads: () -> Unit = {},
    onRegenerate: (UIMessage) -> Unit = {},
    onEdit: (UIMessage) -> Unit = {},
    onForkMessage: (UIMessage) -> Unit = {},
    onDelete: (UIMessage) -> Unit = {},
    onShowTerminalError: (UIMessage) -> Unit = {},
    onUpdateMessage: (MessageNode) -> Unit = {},
    onClickSuggestion: (String) -> Unit = {},
    onJumpToMessage: (Uuid) -> Unit = {},
    onProviderConfigClick: () -> Unit,
    onReadinessModelClick: () -> Unit,
    onReadinessMcpClick: () -> Unit,
    onReadinessLocalToolsClick: () -> Unit,
    onReadinessWorkspaceClick: () -> Unit,
    onSwitchAssistant: () -> Unit,
    onManageAssistant: () -> Unit,
    onMemoryClick: () -> Unit,
    onStarterClick: (ConversationStarterUiModel) -> Unit,
    onToolDecision: (suspend (locator: ToolCallLocator, decision: ToolInteractionDecision) -> Boolean)? = null,
    onSubAssistantAnswer: (suspend (runId: String, interactionId: String, answer: String) -> Boolean)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onConversationSystemPromptChange: ((String?) -> Unit)? = null,
) {
    AnimatedContent(
        targetState = previewMode,
        label = "ChatListMode",
        contentAlignment = Alignment.TopCenter,
        transitionSpec = {
            (fadeIn() + scaleIn(initialScale = 0.8f) togetherWith fadeOut() + scaleOut(targetScale = 0.8f))
        }
    ) { target ->
        if (target) {
            ChatOverview(
                innerPadding = innerPadding,
                snapshot = snapshot,
                favoriteNodeIds = favoriteNodeIds,
                currentNodeId = overviewNodeId,
                hazeState = hazeState,
                onJumpToMessage = onJumpToMessage,
            )
        } else {
            ChatListNormal(
                detailSource = detailSource,
                draftFolderLocation = draftFolderLocation,
                innerPadding = innerPadding,
                snapshot = snapshot,
                favoriteNodeIds = favoriteNodeIds,
                state = state,
                scrollIntent = scrollIntent,
                onScrollIntentChange = onScrollIntentChange,
                onReadHistory = onReadHistory,
                turnPresentation = turnPresentation,
                attachmentPreviews = attachmentPreviews,
                exportActions = exportActions,
                launchExportDocument = launchExportDocument,
                requestExportSelection = requestExportSelection,
                onExportSelectionOpened = onExportSelectionOpened,
                settings = settings,
                readiness = readiness,
                assistant = assistant,
                starters = starters,
                hazeState = hazeState,
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRetryReads = onRetryReads,
                onRegenerate = onRegenerate,
                onEdit = onEdit,
                onForkMessage = onForkMessage,
                onDelete = onDelete,
                onShowTerminalError = onShowTerminalError,
                onUpdateMessage = onUpdateMessage,
                onClickSuggestion = onClickSuggestion,
                onProviderConfigClick = onProviderConfigClick,
                onReadinessModelClick = onReadinessModelClick,
                onReadinessMcpClick = onReadinessMcpClick,
                onReadinessLocalToolsClick = onReadinessLocalToolsClick,
                onReadinessWorkspaceClick = onReadinessWorkspaceClick,
                onSwitchAssistant = onSwitchAssistant,
                onManageAssistant = onManageAssistant,
                onMemoryClick = onMemoryClick,
                onStarterClick = onStarterClick,
                animatedVisibilityScope = this@AnimatedContent,
                onToolDecision = onToolDecision,
                onSubAssistantAnswer = onSubAssistantAnswer,
                onToggleFavorite = onToggleFavorite,
                onConversationSystemPromptChange = onConversationSystemPromptChange,
            )
        }
    }
}

@Composable
private fun ChatListNormal(
    detailSource: net.weero.measix.pilot.service.ConversationViewLease?,
    draftFolderLocation: @Composable () -> Unit,
    innerPadding: PaddingValues,
    snapshot: ConversationPresentationSnapshot,
    favoriteNodeIds: Set<Uuid>,
    state: LazyListState,
    scrollIntent: State<ChatScrollIntent>,
    onScrollIntentChange: (ChatScrollIntent) -> Unit,
    onReadHistory: () -> Unit,
    turnPresentation: ConversationPresentation,
    attachmentPreviews: Map<String, net.weero.measix.pilot.service.AttachmentPreview>,
    exportActions: DocumentExportActions,
    launchExportDocument: (Boolean, String) -> Unit,
    requestExportSelection: Boolean,
    onExportSelectionOpened: () -> Unit,
    settings: Settings,
    readiness: ConversationReadiness,
    assistant: Assistant?,
    starters: List<ConversationStarterUiModel>,
    hazeState: HazeState,
    errors: List<ChatError>,
    onDismissError: (Uuid) -> Unit,
    onClearAllErrors: () -> Unit,
    onRetryReads: () -> Unit,
    onRegenerate: (UIMessage) -> Unit,
    onEdit: (UIMessage) -> Unit,
    onForkMessage: (UIMessage) -> Unit,
    onDelete: (UIMessage) -> Unit,
    onShowTerminalError: (UIMessage) -> Unit,
    onUpdateMessage: (MessageNode) -> Unit,
    onClickSuggestion: (String) -> Unit,
    onProviderConfigClick: () -> Unit,
    onReadinessModelClick: () -> Unit,
    onReadinessMcpClick: () -> Unit,
    onReadinessLocalToolsClick: () -> Unit,
    onReadinessWorkspaceClick: () -> Unit,
    onSwitchAssistant: () -> Unit,
    onManageAssistant: () -> Unit,
    onMemoryClick: () -> Unit,
    onStarterClick: (ConversationStarterUiModel) -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onToolDecision: (suspend (locator: ToolCallLocator, decision: ToolInteractionDecision) -> Boolean)? = null,
    onSubAssistantAnswer: (suspend (runId: String, interactionId: String, answer: String) -> Boolean)? = null,
    onToggleFavorite: ((MessageNode) -> Unit)? = null,
    onConversationSystemPromptChange: ((String?) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val loading = turnPresentation.isActive
    val readHistory by rememberUpdatedState(onReadHistory)
    val changeScrollIntent by rememberUpdatedState(onScrollIntentChange)
    var isRecentScroll by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val activity = androidx.activity.compose.LocalActivity.current as? net.weero.measix.pilot.RouteActivity
    val historyScrollConnection = remember(state, snapshot.conversationId) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Accessibility and wheel input do not emit DragInteraction. They also revoke
                // a pending append scroll; flings and IME compensation keep their own protocol.
                if (source == NestedScrollSource.UserInput && available.y != 0f &&
                    scrollIntent.value != ChatScrollIntent.DRAGGING
                ) {
                    readHistory()
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(state, snapshot.conversationId) {
        state.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    readHistory()
                    changeScrollIntent(ChatScrollIntent.DRAGGING)
                }
                is DragInteraction.Stop -> if (scrollIntent.value == ChatScrollIntent.DRAGGING) {
                    changeScrollIntent(ChatScrollIntent.AFTER_DRAG)
                }
                is DragInteraction.Cancel -> if (scrollIntent.value == ChatScrollIntent.DRAGGING) {
                    readHistory()
                }
                else -> Unit
            }
        }
    }

    DisposableEffect(Unit) {
        val listener: (Boolean) -> Boolean = { isVolumeUp ->
            if (settings.displaySetting.enableVolumeKeyScroll) {
                val bottomPaddingPx = with(density) {
                    (32.dp + innerPadding.calculateBottomPadding()).toPx()
                }
                val scrollAmount = (state.layoutInfo.viewportSize.height - bottomPaddingPx) *
                    settings.displaySetting.volumeKeyScrollRatio
                readHistory()
                scope.launch {
                    state.scrollBy(if (isVolumeUp) -scrollAmount else scrollAmount)
                    changeScrollIntent(if (state.canScrollForward) ChatScrollIntent.VIEW_HISTORY else ChatScrollIntent.FOLLOW_TAIL)
                }
                true
            } else false
        }
        activity?.volumeKeyListeners?.add(listener)
        onDispose {
            activity?.volumeKeyListeners?.remove(listener)
        }
    }

    val selectedItems = rememberSaveable(snapshot.conversationId.toString(), saver = listSaver(
        save = { ids: SnapshotStateList<Uuid> -> ids.map(Uuid::toString) },
        restore = { ids -> mutableStateListOf<Uuid>().apply { addAll(ids.map(Uuid::parse)) } },
    )) { mutableStateListOf<Uuid>() }
    var selecting by rememberSaveable(snapshot.conversationId.toString()) { mutableStateOf(false) }
    var showExportSheet by rememberSaveable(snapshot.conversationId.toString()) { mutableStateOf(false) }
    LaunchedEffect(requestExportSelection) {
        if (requestExportSelection) {
            selectedItems.clear()
            selectedItems.addAll(snapshot.nodes.map { it.id })
            selecting = selectedItems.isNotEmpty()
            onExportSelectionOpened()
        }
    }

    // 自动跟随键盘滚动
    ImeLazyListAutoScroller(lazyListState = state)

    // 对话大小警告对话框
    val sizeInfo = rememberConversationSizeInfo(snapshot.nodes)
    var showSizeWarningDialog by rememberSaveable(snapshot.conversationId) { mutableStateOf(true) }
    if (sizeInfo.showWarning && showSizeWarningDialog) {
        ConversationSizeWarningDialog(
            sizeInfo = sizeInfo,
            onDismiss = { showSizeWarningDialog = false }
        )
    }

    val messageAssistant = assistant
    // 消息列表数据源来自 snapshot.nodes（未变节点引用相同；流式期间
    // 仅末节点由流式投影覆盖 → Compose skip 生效）。key = node.id 不变。
    val snapshotNodes = snapshot.nodes
    val modelIconFallback = if (snapshot.header.scope is ConfigurationScope.Enterprise) {
        ModelIconFallback.NOETRAL
    } else {
        ModelIconFallback.INITIALS
    }
    val snapshotNodesUpdated by rememberUpdatedState(snapshotNodes)
    val lastMessageIndex = snapshotNodes.lastIndex
    val isEmptyConversation = snapshotNodes.isEmpty()
    val showsConfigurationPrompt = isEmptyConversation || !readiness.canSend

    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        // 自动滚动到底部
        if (settings.displaySetting.enableAutoScroll) {
            LaunchedEffect(state, snapshot.conversationId) {
                snapshotFlow {
                    Triple(scrollIntent.value, state.isScrollInProgress, state.canScrollForward) to
                        snapshotNodesUpdated.isNotEmpty()
                }.collect { (scroll, hasMessages) ->
                    val (intent, scrolling, canScrollForward) = scroll
                    // Stop can precede fling. Keep the user's eligibility until the actual tail,
                    // while explicit history navigation revokes it even before the next layout.
                    if (intent == ChatScrollIntent.AFTER_DRAG && !scrolling && !canScrollForward) {
                        changeScrollIntent(ChatScrollIntent.FOLLOW_TAIL)
                    } else if (hasMessages && intent == ChatScrollIntent.FOLLOW_TAIL && !scrolling && canScrollForward) {
                        state.requestScrollToItem(snapshotNodesUpdated.lastIndex + 10)
                    }
                }
            }
        }

        // 判断最近是否滚动
        LaunchedEffect(state.isScrollInProgress) {
            if (state.isScrollInProgress) {
                isRecentScroll = true
                delay(1500)
                isRecentScroll = false
            } else {
                delay(1500)
                isRecentScroll = false
            }
        }

        val attachmentPreviewProvider = remember(attachmentPreviews) {
            { ref: String -> attachmentPreviews[ref] }
        }
        // 会话级时序相册：点击期读取最新节点，并只使用查询端口已校验的本地图片 URL。
        val conversationAlbum = remember(attachmentPreviewProvider) {
            {
                snapshotNodesUpdated.flatMap { node ->
                    collectMessageImages(node.currentMessage.parts, attachmentPreviewProvider)
                }
            }
        }
    val backgroundHost = rememberImageBackgroundHost(settings, snapshot.header.assistantId)
        val previewActions = remember(backgroundHost.action, assistant, snapshot.header.scope) {
            if (assistant != null) listOf(backgroundHost.action) else emptyList()
        }

        net.weero.measix.pilot.ui.components.richtext.RichTextHost(
            detailSource?.let { net.weero.measix.pilot.service.RenderedContentSource.Conversation(it) },
            net.weero.measix.pilot.ui.components.message.tools.LocalToolConversationId provides snapshot.header.id,
            LocalConversationImages provides conversationAlbum,
            LocalAttachmentPreview provides attachmentPreviewProvider,
            net.weero.measix.pilot.ui.components.message.LocalAttachmentPreviewRetry provides onRetryReads,
            LocalImagePreviewActions provides previewActions,
            LocalImagePreviewOverlay provides backgroundHost.overlay,
        ) {
            ChatFontProvider(displaySetting = settings.displaySetting) {
                LazyColumn(
                    state = state,
                    contentPadding = PaddingValues(
                    start = 16.dp,
                    top = if (showsConfigurationPrompt) 8.dp else 0.dp,
                    end = 16.dp,
                    bottom = 24.dp + innerPadding.calculateBottomPadding(),
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                    .widthIn(max = AdaptiveLayoutDefaults.ReadableContentMaxWidth)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .nestedScroll(historyScrollConnection)
                    .hazeSource(state = hazeState)
                    .padding(top = innerPadding.calculateTopPadding()),
                ) {
                    val prefix = chatListPrefix(snapshot, readiness)
                    if (prefix == ChatListPrefix.SETUP) {
                        item(key = "ConversationSetup") {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                draftFolderLocation()
                                if (readiness.requiresProviderConfiguration && snapshot.header.scope is net.weero.measix.pilot.data.configuration.ConfigurationScope.Personal) {
                                    ProviderConfigWarningCard(onClick = onProviderConfigClick)
                                }
                                ConversationReadinessCard(
                                    readiness = readiness,
                                    assistant = assistant,
                                    compact = false,
                                    enterprise = snapshot.header.scope is net.weero.measix.pilot.data.configuration.ConfigurationScope.Enterprise,
                                    starters = starters,
                                    onModelClick = onReadinessModelClick,
                                    onMcpClick = onReadinessMcpClick,
                                    onLocalToolsClick = onReadinessLocalToolsClick,
                                    onWorkspaceClick = onReadinessWorkspaceClick,
                                    onSwitchAssistant = onSwitchAssistant,
                                    onManageAssistant = onManageAssistant,
                                    onMemoryClick = onMemoryClick,
                                    onStarterClick = onStarterClick,
                                )
                            }
                        }
                    } else if (prefix == ChatListPrefix.PROVIDER) {
                        item(key = "ProviderConfigWarning") {
                            ProviderConfigWarningCard(onClick = onProviderConfigClick)
                        }
                    } else if (prefix == ChatListPrefix.MODEL) {
                        item(key = "ConversationModelReadiness") {
                            ConversationReadinessCard(
                                readiness = readiness,
                                assistant = assistant,
                                compact = true,
                                enterprise = snapshot.header.scope is net.weero.measix.pilot.data.configuration.ConfigurationScope.Enterprise,
                                starters = emptyList(),
                                onModelClick = onReadinessModelClick,
                                onMcpClick = onReadinessMcpClick,
                                onLocalToolsClick = onReadinessLocalToolsClick,
                                onWorkspaceClick = onReadinessWorkspaceClick,
                                onSwitchAssistant = onSwitchAssistant,
                                onManageAssistant = onManageAssistant,
                                onMemoryClick = onMemoryClick,
                                onStarterClick = onStarterClick,
                            )
                        }
                    }

                    itemsIndexed(
                        items = snapshotNodes,
                        key = { index, item -> item.id },
                    ) { index, node ->
                        Column {
                            ListSelectableItem(
                                key = node.id,
                                onSelectChange = {
                                    if (!selectedItems.contains(node.id)) {
                                        selectedItems.add(node.id)
                                    } else {
                                        selectedItems.remove(node.id)
                                    }
                                },
                                selectedKeys = selectedItems,
                                enabled = selecting,
                            ) {
                                ChatMessage(
                                    node = node,
                                    detailSource = detailSource,
                                    contextSummary = snapshot.context.messages[node.currentMessage.id],
                                    modelIconFallback = modelIconFallback,
                                    assistant = messageAssistant,
                                    loading = loading && index == lastMessageIndex,
                                    onRegenerate = {
                                        onRegenerate(node.currentMessage)
                                    },
                                    onEdit = {
                                        onEdit(node.currentMessage)
                                    },
                                    onFork = {
                                        onForkMessage(node.currentMessage)
                                    },
                                    onDelete = {
                                        onDelete(node.currentMessage)
                                    },
                                    onShowTerminalError = onShowTerminalError,
                                    onShare = {
                                        selecting = true  // 使用 CoroutineScope 延迟状态更新
                                        selectedItems.clear()
                                        selectedItems.addAll(snapshotNodes.map { it.id }
                                            .subList(0, snapshotNodes.indexOf(node) + 1))
                                    },
                                    onUpdate = {
                                        onUpdateMessage(it)
                                    },
                                    isFavorite = node.id in favoriteNodeIds,
                                    onToggleFavorite = {
                                        onToggleFavorite?.invoke(node)
                                    },
                                    onToolDecision = onToolDecision,
                                    onSubAssistantAnswer = onSubAssistantAnswer,
                                    toolLivePhases = turnPresentation.toolLivePhases,
                                    toolDurationsMillis = snapshot.toolDurationsMillis,
                                    lastMessage = index == lastMessageIndex,
                                )
                            }
                        }
                    }

                    val showsSystemPromptItem = !loading &&
                        messageAssistant?.allowConversationSystemPrompt == true &&
                        onConversationSystemPromptChange != null
                    if (showsSystemPromptItem) {
                        item(key = "ConversationSystemPrompt:${snapshot.conversationId}:${snapshot.header.assistantId}") {
                            ConversationSystemPromptButton(
                                customSystemPrompt = snapshot.header.customSystemPrompt,
                                onSystemPromptChange = onConversationSystemPromptChange,
                            )
                        }
                    }

                    if (loading) {
                        item(LoadingIndicatorKey) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (turnPresentation.phase == TurnLivePhase.AWAITING_USER) {
                                    ApprovalRequiredIndicator(
                                        showLabel = true,
                                    )
                                } else {
                                    RabbitLoadingIndicator(
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                AnimatedVisibility(
                                    visible = turnPresentation.isWorking &&
                                        turnPresentation.processingText != null,
                                ) {
                                    Text(
                                        text = turnPresentation.processingText ?: "",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    // 为了能正确滚动到这
                    item(ScrollBottomKey) {
                        Spacer(
                            Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 错误消息卡片
            ErrorCardsDisplay(
                errors = errors,
                onDismissError = onDismissError,
                onClearAllErrors = onClearAllErrors,
                onRetryReads = onRetryReads,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(5f)
            )

            // 完成选择
            AnimatedVisibility(
                visible = selecting,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = -(48).dp),
                enter = slideInVertically(
                    initialOffsetY = { it * 2 },
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it * 2 },
                ),
            ) {
                HorizontalFloatingToolbar(
                    expanded = true,
                ) {
                    Tooltip(
                        tooltip = {
                            Text(stringResource(R.string.cancel))
                        }
                    ) {
                        IconButton(
                            onClick = {
                                selecting = false
                                selectedItems.clear()
                            }
                        ) {
                            Icon(HugeIcons.Cancel01, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text(stringResource(R.string.setting_provider_page_select_all, snapshotNodes.size))
                        }
                    ) {
                        IconButton(
                            onClick = {
                                if (snapshotNodes.all { it.id in selectedItems }) {
                                    selectedItems.clear()
                                } else {
                                    selectedItems.clear()
                                    selectedItems.addAll(snapshotNodes.map { it.id })
                                }
                            }
                        ) {
                            Icon(HugeIcons.CursorPointer01, null)
                        }
                    }
                    Tooltip(
                        tooltip = {
                            Text(stringResource(R.string.confirm))
                        }
                    ) {
                        FilledIconButton(
                            onClick = {
                                selecting = false
                                val messages = snapshotNodes.filter { it.id in selectedItems }
                                if (messages.isNotEmpty()) {
                                    showExportSheet = true
                                }
                            }
                        ) {
                            Icon(HugeIcons.Tick01, null)
                        }
                    }
                }
            }

            // 导出对话框
            ChatExportSheet(
                source = detailSource,
                actions = exportActions,
                launchDocument = launchExportDocument,
                modelIconFallback = modelIconFallback,
                visible = showExportSheet,
                onDismissRequest = {
                    showExportSheet = false
                    selectedItems.clear()
                },
                conversationTitle = snapshot.header.title,
                selectedMessages = snapshotNodes.filter { it.id in selectedItems }
                    .map { it.currentMessage },
                attachmentPreviews = attachmentPreviews,
            )

            val captureProgress = LocalScrollCaptureInProgress.current

            // 消息快速跳转
            MessageJumper(
                show = isRecentScroll && !state.isScrollInProgress && settings.displaySetting.showMessageJumper && !captureProgress,
                onLeft = settings.displaySetting.messageJumperOnLeft,
                scope = scope,
                state = state,
                onReadHistory = onReadHistory,
                onScrollIntentChange = onScrollIntentChange,
            )

            // Suggestion
            if (snapshot.header.chatSuggestions.isNotEmpty() && !captureProgress) {
                ChatSuggestionsRow(
                    suggestions = snapshot.header.chatSuggestions,
                    onClickSuggestion = onClickSuggestion,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
private fun ChatSuggestionsRow(
    modifier: Modifier = Modifier,
    suggestions: List<String>,
    onClickSuggestion: (String) -> Unit
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(suggestions) { suggestion ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable {
                        onClickSuggestion(suggestion)
                    }
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.asChatChrome())
                    .padding(vertical = 4.dp, horizontal = 8.dp),
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun BoxScope.MessageJumper(
    show: Boolean,
    onLeft: Boolean,
    scope: CoroutineScope,
    state: LazyListState,
    onReadHistory: () -> Unit,
    onScrollIntentChange: (ChatScrollIntent) -> Unit,
) {
    AnimatedVisibility(
        visible = show,
        modifier = Modifier.align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd),
        enter = slideInHorizontally(
            initialOffsetX = { if (onLeft) -it * 2 else it * 2 },
        ),
        exit = slideOutHorizontally(
            targetOffsetX = { if (onLeft) -it * 2 else it * 2 },
        )
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier.testTag("ChatMessageJumperStart"),
                onClick = {
                    onReadHistory()
                    scope.launch {
                        state.scrollToItem(0)
                    }
                },
                shape = CircleShape,
                tonalElevation = 0.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowUpDouble,
                    contentDescription = stringResource(R.string.chat_page_scroll_to_top),
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    onReadHistory()
                    scope.launch {
                        state.animateScrollToItem(
                            (state.firstVisibleItemIndex - 1).fastCoerceAtLeast(
                                0
                            )
                        )
                        onScrollIntentChange(if (state.canScrollForward) ChatScrollIntent.VIEW_HISTORY else ChatScrollIntent.FOLLOW_TAIL)
                    }
                },
                shape = CircleShape,
                tonalElevation = 0.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowUp01,
                    contentDescription = stringResource(R.string.chat_page_previous_message),
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    onReadHistory()
                    scope.launch {
                        state.animateScrollToItem(state.firstVisibleItemIndex + 1)
                        onScrollIntentChange(if (state.canScrollForward) ChatScrollIntent.VIEW_HISTORY else ChatScrollIntent.FOLLOW_TAIL)
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f)
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowDown01,
                    contentDescription = stringResource(R.string.chat_page_next_message),
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
            Surface(
                onClick = {
                    onReadHistory()
                    scope.launch {
                        state.scrollToItem(state.layoutInfo.totalItemsCount - 1)
                        onScrollIntentChange(ChatScrollIntent.FOLLOW_TAIL)
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.65f),
            ) {
                Icon(
                    imageVector = HugeIcons.ArrowDownDouble,
                    contentDescription = stringResource(R.string.chat_page_scroll_to_bottom),
                    modifier = Modifier
                        .padding(4.dp)
                )
            }
        }
    }
}
