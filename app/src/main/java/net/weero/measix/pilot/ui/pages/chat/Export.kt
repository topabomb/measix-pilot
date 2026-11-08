package net.weero.measix.pilot.ui.pages.chat

import net.weero.measix.pilot.ui.components.ui.rememberExportDocumentLauncher
import net.weero.measix.pilot.ui.components.ui.ExportAction
import net.weero.measix.pilot.ui.components.ui.DocumentExportActions
import net.weero.measix.pilot.service.RenderedContentSource
import net.weero.measix.pilot.service.FileManagementApplicationService
import net.weero.measix.pilot.service.ConversationViewLease
import net.weero.measix.pilot.service.AttachmentPreview
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.FlowRow

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Book02
import me.rerere.hugeicons.stroke.Book04
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Wrench01
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import net.weero.measix.pilot.ui.adaptive.AdaptiveModal
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableStateListOf
import androidx.navigation3.runtime.NavKey
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.ui.context.Navigator
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import net.weero.measix.pilot.ui.components.toast.ToastType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyUIMessage
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.ai.tools.ToolOutputToolNames
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.ui.components.message.MessagePartBlock
import net.weero.measix.pilot.ui.components.message.ThinkingStep
import net.weero.measix.pilot.ui.components.message.groupMessageParts
import net.weero.measix.pilot.ui.components.message.resolveAttachmentImageSource
import net.weero.measix.pilot.ui.components.message.resolveAttachmentMediaUrl
import net.weero.measix.pilot.ui.components.richtext.MarkdownBlock
import net.weero.measix.pilot.ui.components.ui.BitmapComposer
import net.weero.measix.pilot.ui.components.ui.ChainOfThought
import net.weero.measix.pilot.ui.components.ui.ChainOfThoughtScope
import net.weero.measix.pilot.ui.components.ui.ModelIcon
import net.weero.measix.pilot.ui.components.ui.ModelIconFallback
import net.weero.measix.pilot.ui.components.ui.ErrorDetails
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.ui.components.toast.rememberToasterState
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.service.MediaExportService
import net.weero.measix.pilot.service.ChatError
import net.weero.measix.pilot.service.ChatErrorRetention
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.ui.theme.MeasixTheme
import net.weero.measix.pilot.utils.getActivity
import net.weero.measix.pilot.utils.JsonInstantPretty
import net.weero.measix.pilot.utils.jsonPrimitiveOrNull
import net.weero.measix.pilot.utils.toLocalString
import java.time.LocalDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

@Composable
internal fun rememberChatExportLauncher(actions: DocumentExportActions): (Boolean, String) -> Unit {
    val saveMarkdown = rememberExportDocumentLauncher(actions, "text/markdown")
    val savePng = rememberExportDocumentLauncher(actions, "image/png")
    return { image, name -> if (image) savePng(name) else saveMarkdown(name) }
}

@Composable
internal fun ChatExportFailure(actions: DocumentExportActions) {
    actions.failure?.let { detail ->
        ErrorDetails(
            ChatError(detail = stringResource(R.string.chat_page_export_failed, detail), retention = ChatErrorRetention.UNTIL_DISMISSED),
            onDismiss = actions::clearFailure,
        )
    }
}

@Composable
internal fun ChatExportSheet(
    source: ConversationViewLease?,
    actions: DocumentExportActions,
    launchDocument: (Boolean, String) -> Unit,
    modelIconFallback: ModelIconFallback = ModelIconFallback.INITIALS,
    visible: Boolean,
    onDismissRequest: () -> Unit,
    conversationTitle: String,
    selectedMessages: List<UIMessage>,
    attachmentPreviews: Map<String, AttachmentPreview> = emptyMap(),
    queries: ConversationQueryService = org.koin.compose.koinInject(),
    contentFiles: FileManagementApplicationService = org.koin.compose.koinInject(),
    mediaExports: MediaExportService = org.koin.compose.koinInject(),
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val settings = LocalSettings.current
    var options by remember { mutableStateOf(ChatExportOptions()) }

    actions.completedName?.let { name ->
        val message = stringResource(R.string.chat_page_export_success, name)
        LaunchedEffect(name) {
            toaster.show(message, type = ToastType.Success)
            actions.acknowledgeCompletion()
            onDismissRequest()
        }
    }
    if (!visible) return

    fun export(action: ExportAction, image: Boolean) {
        val view = source ?: return
        val messages = selectedMessages.toList()
        if (messages.isEmpty()) return
        val selectedOptions = options
        val title = conversationTitle
        val attachmentPreview = attachmentPreviews::get
        val extension = if (image) "png" else "md"
        val fileName = "chat-export-${LocalDateTime.now().format(EXPORT_TIME_FORMAT)}.$extension"
        scope.launch {
            actions.execute(context, action, prepare = {
                queries.requireViewAccess(view)
                if (image) {
                    val messageSources = messages.associate { message ->
                        message.id to queries.messageResourceSource(view, view.conversationId, message.id)
                    }
                    val rendering = messageSources.mapValues { (_, messageSource) ->
                        val rendered = RenderedContentSource.Conversation(view, messageSource)
                        MessageExportRendering(rendered) { url -> contentFiles.resolveContentImage(rendered, url) }
                    }
                    val contentSource = RenderedContentSource.Conversation(view)
                    val bitmap = renderConversationBitmap(
                        context = context,
                        density = density,
                        conversationTitle = title,
                        messages = messages,
                        settings = settings,
                        attachmentPreview = attachmentPreview,
                        imageResolver = { contentFiles.resolveContentImage(contentSource, it) },
                        contentSource = contentSource,
                        modelIconFallback = modelIconFallback,
                        options = selectedOptions,
                        messageRendering = rendering,
                    )
                    try {
                        mediaExports.prepareConversationImage(context, view, messageSources.values.toList(), bitmap, fileName)
                    } finally {
                        bitmap.recycle()
                    }
                } else {
                    val text = withContext(Dispatchers.Default) {
                        renderMarkdownExport(title, messages, attachmentPreview, selectedOptions)
                    }
                    mediaExports.prepareConversationMarkdown(view, text, fileName)
                }
            }, launchDocument = { launchDocument(image, it) })
        }
    }

    val canExport = source != null && selectedMessages.isNotEmpty() && !actions.busy
    AdaptiveModal(
        onDismissRequest = { if (!actions.busy) onDismissRequest() },
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.chat_page_export_format), style = MaterialTheme.typography.titleMedium)
            ListItem(
                headlineContent = { Text(stringResource(R.string.chat_page_export_include_reasoning)) },
                trailingContent = {
                    Switch(options.includeReasoning, { options = options.copy(includeReasoning = it) }, enabled = !actions.busy)
                },
            )
            ExportFormatCard(
                title = stringResource(R.string.chat_page_export_markdown),
                description = stringResource(R.string.chat_page_export_markdown_desc),
                enabled = canExport,
                onSave = { export(ExportAction.SAVE, image = false) },
                onShare = { export(ExportAction.SHARE, image = false) },
            )
            ExportFormatCard(
                title = stringResource(R.string.chat_page_export_image),
                description = stringResource(R.string.chat_page_export_image_desc),
                enabled = canExport,
                onSave = { export(ExportAction.SAVE, image = true) },
                onShare = { export(ExportAction.SHARE, image = true) },
                onGallery = { export(ExportAction.GALLERY, image = true) },
                options = {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.chat_page_export_image_expand_reasoning)) },
                        trailingContent = {
                            Switch(options.expandReasoning, { options = options.copy(expandReasoning = it) },
                                enabled = options.includeReasoning && !actions.busy)
                        },
                    )
                },
            )
        }
    }
}

private val EXPORT_TIME_FORMAT = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

@Composable
private fun ExportFormatCard(
    title: String,
    description: String,
    enabled: Boolean,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onGallery: (() -> Unit)? = null,
    options: @Composable () -> Unit = {},
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        ListItem(headlineContent = { Text(title) }, supportingContent = { Text(description) })
        options()
        FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onSave, enabled = enabled) { Text(stringResource(R.string.export_to_file)) }
            TextButton(onClick = onShare, enabled = enabled) { Text(stringResource(R.string.export_share)) }
            if (onGallery != null) TextButton(onClick = onGallery, enabled = enabled) {
                Text(stringResource(R.string.chat_page_export_save_gallery))
            }
        }
    }
}

internal fun renderMarkdownExport(
    conversationTitle: String,
    messages: List<UIMessage>,
    attachmentPreview: (String) -> net.weero.measix.pilot.service.AttachmentPreview?,
    options: ChatExportOptions = ChatExportOptions(),
): String = buildString {
    append("# $conversationTitle\n\n")
    append("*Exported on ${LocalDateTime.now().toLocalString()}*\n\n")

    messages.forEach { message ->
        val role = if (message.role == MessageRole.USER) "**User**" else "**Assistant**"
        append("$role:\n\n")
        exportParts(message.parts, options).forEach { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    append(part.text)
                    appendLine()
                }

                is UIMessagePart.Image -> {
                    resolveAttachmentMediaUrl(part.url, part, attachmentPreview)?.let { url ->
                        append("![Image]($url)")
                        appendLine()
                    }
                }

                is UIMessagePart.Reasoning -> {
                    part.reasoning.lines()
                        .filter { it.isNotBlank() }
                        .map { "> $it" }
                        .forEach {
                            appendLine(it)
                        }
                    appendLine()
                    appendLine()
                }

                is UIMessagePart.Tool -> {
                    append("**Tool**: `${part.toolName}`")
                    appendLine()
                    if (part.providerCallId.isNotBlank()) {
                        append("- Call ID: `${part.providerCallId}`")
                        appendLine()
                    }

                    append("Input:")
                    appendLine()
                    append("```json")
                    appendLine()
                    append(JsonInstantPretty.encodeToString(part.inputAsJson()))
                    appendLine()
                    append("```")
                    appendLine()

                    if (part.output.isNotEmpty()) {
                        append("Output:")
                        appendLine()
                        part.output.forEach { outputPart ->
                            when (outputPart) {
                                is UIMessagePart.Text -> {
                                    append("```text")
                                    appendLine()
                                    append(outputPart.text)
                                    appendLine()
                                    append("```")
                                    appendLine()
                                }

                                is UIMessagePart.Reasoning -> {
                                    outputPart.reasoning.lines()
                                        .filter { it.isNotBlank() }
                                        .forEach {
                                            append("> $it")
                                            appendLine()
                                        }
                                }

                                is UIMessagePart.Image -> {
                                    resolveAttachmentMediaUrl(outputPart.url, outputPart, attachmentPreview)?.let { url ->
                                        append("![Tool Image]($url)")
                                        appendLine()
                                    }
                                }

                                is UIMessagePart.Document -> {
                                    append("[Document: ${outputPart.fileName}]")
                                    resolveAttachmentMediaUrl(outputPart.url, outputPart, attachmentPreview)
                                        ?.let { append("($it)") }
                                    appendLine()
                                }

                                is UIMessagePart.Video -> {
                                    append("[Video]")
                                    resolveAttachmentMediaUrl(outputPart.url, outputPart, attachmentPreview)
                                        ?.let { append("($it)") }
                                    appendLine()
                                }

                                is UIMessagePart.Audio -> {
                                    append("[Audio]")
                                    resolveAttachmentMediaUrl(outputPart.url, outputPart, attachmentPreview)
                                        ?.let { append("($it)") }
                                    appendLine()
                                }

                                else -> {}
                            }
                        }
                    }
                    appendLine()
                }

                is UIMessagePart.Document -> {
                    append("[Document: ${part.fileName}]")
                    resolveAttachmentMediaUrl(part.url, part, attachmentPreview)?.let { append("($it)") }
                    appendLine()
                }
                is UIMessagePart.Audio -> {
                    append("[Audio]")
                    resolveAttachmentMediaUrl(part.url, part, attachmentPreview)?.let { append("($it)") }
                    appendLine()
                }
                is UIMessagePart.Video -> {
                    append("[Video]")
                    resolveAttachmentMediaUrl(part.url, part, attachmentPreview)?.let { append("($it)") }
                    appendLine()
                }
                else -> {}
            }
        }
        appendLine()
        append("---")
        appendLine()
    }
}

internal data class MessageExportRendering(
    val source: net.weero.measix.pilot.service.RenderedContentSource,
    val imageResolver: suspend (String) -> net.weero.measix.pilot.service.ImageSource?,
)

internal suspend fun renderConversationBitmap(
    context: Context,
    density: Density,
    conversationTitle: String,
    messages: List<UIMessage>,
    settings: Settings,
    attachmentPreview: (String) -> net.weero.measix.pilot.service.AttachmentPreview?,
    imageResolver: (suspend (String) -> net.weero.measix.pilot.service.ImageSource?)?,
    contentSource: net.weero.measix.pilot.service.RenderedContentSource?,
    modelIconFallback: ModelIconFallback = ModelIconFallback.INITIALS,
    options: ChatExportOptions = ChatExportOptions(),
    messageRendering: Map<kotlin.uuid.Uuid, MessageExportRendering> = emptyMap(),
): Bitmap {
    val composer = BitmapComposer()
    val activity = requireNotNull(context.getActivity()) { "Unable to access the current activity" }

    return composer.composableToBitmap(
        activity = activity,
        width = 540.dp,
        screenDensity = density,
        content = {
            CompositionLocalProvider(
                net.weero.measix.pilot.ui.components.richtext.LocalRenderedContentSource provides contentSource,
                LocalSettings provides settings,
                net.weero.measix.pilot.ui.components.richtext.LocalImageSourceResolver provides imageResolver,
                net.weero.measix.pilot.ui.components.message.LocalAttachmentPreview provides attachmentPreview,
            ) {
                ExportedChatImage(
                    conversationTitle = conversationTitle,
                    messages = messages,
                    attachmentPreview = attachmentPreview,
                    modelIconFallback = modelIconFallback,
                    options = options,
                    messageRendering = messageRendering,
                )
            }
        }
    )
}

data class ChatExportOptions(val includeReasoning: Boolean = true, val expandReasoning: Boolean = false)

internal fun exportParts(parts: List<UIMessagePart>, options: ChatExportOptions): List<UIMessagePart> =
    if (options.includeReasoning) parts else parts.mapNotNull { part ->
        when (part) {
            is UIMessagePart.Reasoning -> null
            is UIMessagePart.Tool -> part.copy(output = exportParts(part.output, options))
            else -> part
        }
    }

@Composable
private fun ExportedChatImage(
    conversationTitle: String,
    messages: List<UIMessage>,
    attachmentPreview: (String) -> net.weero.measix.pilot.service.AttachmentPreview?,
    modelIconFallback: ModelIconFallback,
    options: ChatExportOptions = ChatExportOptions(),
    messageRendering: Map<kotlin.uuid.Uuid, MessageExportRendering> = emptyMap(),
) {
    val navBackStack = remember { mutableStateListOf<NavKey>() }
    val navigator = Navigator(navBackStack)
    val toasterState = rememberToasterState()
    MeasixTheme {
        CompositionLocalProvider(
            LocalNavController provides navigator,
            LocalToaster provides toasterState
        ) {
            Surface(
                modifier = Modifier.width(540.dp) // like 1080p but with density independence
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = conversationTitle,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                            Text(
                                text = "${LocalDateTime.now().toLocalString()}  measix-pilot.weero.net",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Use painterResource for the logo
                        val painter = painterResource(id = R.mipmap.ic_launcher_foreground)
                        Image(
                            painter = painter,
                            contentDescription = "Logo",
                            modifier = Modifier.size(60.dp)
                        )
                    }

                    // Messages
                    messages.forEachIndexed { index, message ->
                        val rendering = messageRendering[message.id]
                        CompositionLocalProvider(
                            net.weero.measix.pilot.ui.components.richtext.LocalRenderedContentSource provides
                                (rendering?.source ?: net.weero.measix.pilot.ui.components.richtext.LocalRenderedContentSource.current),
                            net.weero.measix.pilot.ui.components.richtext.LocalImageSourceResolver provides
                                (rendering?.imageResolver ?: net.weero.measix.pilot.ui.components.richtext.LocalImageSourceResolver.current),
                        ) {
                            ExportedChatMessage(
                                message = message.copy(parts = exportParts(message.parts, options)),
                                attachmentPreview = attachmentPreview,
                                modelIconFallback = modelIconFallback,
                                options = options,
                                prevMessage = messages.getOrNull(index - 1)
                            )
                        }
                    }

                    // Watermark
                    Column {
                        Text(
                            text = stringResource(R.string.export_image_warning),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun ExportedChatMessage(
    message: UIMessage,
    attachmentPreview: (String) -> net.weero.measix.pilot.service.AttachmentPreview?,
    modelIconFallback: ModelIconFallback,
    prevMessage: UIMessage? = null,
    options: ChatExportOptions = ChatExportOptions()
) {
    if (message.parts.isEmptyUIMessage()) return
    val context = LocalContext.current
    val settings = LocalSettings.current
    val model = message.modelInfo
    // Always show model icon for assistant messages in exported images
    val showModelIcon = message.role == MessageRole.ASSISTANT && prevMessage?.role == MessageRole.USER
    val iconLabel = when {
        model?.modelId?.isNotBlank() == true -> model.modelId
        model?.displayName?.isNotBlank() == true -> model.displayName
        else -> "AI"
    }
    val groupedParts = remember(message.parts) { message.parts.groupMessageParts() }
    val messageContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .widthIn(max = (540 * 0.9).dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start
        ) {
            groupedParts.forEach { block ->
                when (block) {
                    is MessagePartBlock.ContextUpdateBlock -> Unit // Export groups without application update markers.
                    is MessagePartBlock.ThinkingBlock -> {
                        if (block.steps.isNotEmpty()) {
                            ChainOfThought(
                                steps = block.steps,
                                collapsedVisibleCount = block.steps.size
                            ) { step ->
                                when (step) {
                                    is ThinkingStep.ReasoningStep -> {
                                        ExportedReasoningStep(
                                            reasoning = step.reasoning,
                                            expanded = options.expandReasoning
                                        )
                                    }

                                    is ThinkingStep.ToolStep -> {
                                        ExportedToolStep(
                                            tool = step.tool
                                        )
                                    }
                                }
                            }
                        }
                    }

                    is MessagePartBlock.SubAssistantCallBlock -> {
                        Card(
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp)
                            ) {
                                Text(
                                    text = block.tool.toolName,
                                    style = MaterialTheme.typography.labelMedium
                                )
                                if (block.tool.hasReplayResult) {
                                    block.tool.output.firstOrNull { it is UIMessagePart.Text }?.let {
                                        Text(
                                            text = (it as UIMessagePart.Text).text.take(200),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }

                    is MessagePartBlock.ContentBlock -> {
                        when (val part = block.part) {
                            is UIMessagePart.Text -> {
                                if (part.text.isNotBlank()) {
                                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                                        if (message.role == MessageRole.USER) {
                                            Card(
                                                shape = MaterialTheme.shapes.medium,
                                            ) {
                                                MarkdownBlock(
                                                    content = part.text,
                                                    modifier = Modifier.padding(8.dp)
                                                )
                                            }
                                        } else {
                                            if (settings.displaySetting.showAssistantBubble) {
                                                Card(
                                                    shape = MaterialTheme.shapes.medium,
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                                    )
                                                ) {
                                                    MarkdownBlock(
                                                        content = part.text,
                                                        modifier = Modifier.padding(8.dp)
                                                    )
                                                }
                                            } else {
                                                MarkdownBlock(
                                                    content = part.text,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            is UIMessagePart.Image -> {
                                resolveAttachmentImageSource(part, attachmentPreview)?.let { imageUrl ->
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(imageUrl)
                                            .allowHardware(false)
                                            .crossfade(false)
                                            .build(),
                                        contentDescription = "Image",
                                        modifier = Modifier
                                            .sizeIn(maxHeight = 300.dp)
                                            .clip(RoundedCornerShape(12.dp)),
                                    )
                                }
                            }

                            else -> {
                                // Other parts are not rendered in image export for now
                            }
                        }
                    }
                }
            }
        }
    }

    if (showModelIcon) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            ModelIcon(
                name = iconLabel,
                fallback = modelIconFallback,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(36.dp)
            )

            Text(
                text = model?.displayLabel ?: stringResource(R.string.setting_files_page_generated_unknown_model),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
    messageContent()
}

@Composable
private fun ChainOfThoughtScope.ExportedReasoningStep(
    reasoning: UIMessagePart.Reasoning,
    expanded: Boolean
) {
    val duration = reasoning.finishedAt?.let { endTime ->
        endTime - reasoning.createdAt
    } ?: (kotlin.time.Clock.System.now() - reasoning.createdAt)

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = {},
        icon = {
            Icon(
                painter = painterResource(R.drawable.deepthink),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
        },
        label = {
            Text(
                text = stringResource(R.string.deep_thinking),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        },
        extra = if (duration > 0.seconds) {
            {
                Text(
                    text = duration.toString(DurationUnit.SECONDS, 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        } else {
            null
        },
        contentVisible = expanded,
        content = {
            MarkdownBlock(
                content = reasoning.reasoning,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    )
}

@Composable
private fun ChainOfThoughtScope.ExportedToolStep(
    tool: UIMessagePart.Tool
) {
    val chart = remember(tool) {
        net.weero.measix.pilot.ui.components.message.tools.completedChart(tool,
            net.weero.measix.pilot.service.runtime.resolveToolLivePhase(tool, null))
    }
    if (chart != null) {
        net.weero.measix.pilot.ui.components.charts.ChartCard(chart)
        return
    }
    val memoryAction = runCatching {
        tool.inputAsJson().jsonObject["action"]?.jsonPrimitiveOrNull?.contentOrNull
    }.getOrNull()
    val title = when (tool.toolName) {
        "memory_tool" -> when (memoryAction) {
            "create" -> stringResource(R.string.chat_message_tool_create_memory)
            "edit" -> stringResource(R.string.chat_message_tool_edit_memory)
            "delete" -> stringResource(R.string.chat_message_tool_delete_memory)
            else -> stringResource(R.string.chat_message_tool_call_generic, tool.toolName)
        }

        "search_web" -> {
            val query = runCatching {
                tool.inputAsJson().jsonObject["query"]?.jsonPrimitiveOrNull?.contentOrNull ?: ""
            }.getOrDefault("")
            stringResource(R.string.chat_message_tool_search_web, query)
        }

        "scrape_web" -> stringResource(R.string.chat_message_tool_scrape_web)
        ToolOutputToolNames.READ -> stringResource(R.string.chat_message_tool_read_trimmed_result)
        ToolOutputToolNames.GREP -> stringResource(R.string.chat_message_tool_search_trimmed_result)
        else -> stringResource(R.string.chat_message_tool_call_generic, tool.toolName)
    }
    ControlledChainOfThoughtStep(
        expanded = true,
        onExpandedChange = {},
        icon = {
            Icon(
                imageVector = when (tool.toolName) {
                    "memory_tool" -> when (memoryAction) {
                        "create", "edit" -> HugeIcons.Book04
                        "delete" -> HugeIcons.Book02
                        else -> HugeIcons.Wrench01
                    }

                    "search_web" -> HugeIcons.Search01
                    "scrape_web" -> HugeIcons.Earth
                    ToolOutputToolNames.READ -> HugeIcons.FileView
                    ToolOutputToolNames.GREP -> HugeIcons.Search01
                    else -> HugeIcons.Wrench01
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
        },
        label = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        contentVisible = false,
        content = null,
    )
}
