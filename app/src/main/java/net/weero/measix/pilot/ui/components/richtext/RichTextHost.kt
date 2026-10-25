package net.weero.measix.pilot.ui.components.richtext

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import net.weero.measix.pilot.ui.components.toast.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import org.koin.compose.koinInject

val LocalRenderedContentSource = staticCompositionLocalOf<RenderedContentSource?> { null }
internal val LocalRichTextActions = staticCompositionLocalOf<RichTextActions?> { null }

/** A message gets its own resource provenance; streaming text does not recreate the lease on every token. */
@Composable
internal fun MessageRichTextHost(
    view: ConversationViewLease?,
    conversationId: kotlin.uuid.Uuid?,
    messageId: kotlin.uuid.Uuid,
    hasContent: Boolean,
    content: @Composable () -> Unit,
) {
    if (view == null || conversationId == null) {
        content()
        return
    }
    val queries: ConversationQueryService = koinInject()
    var revision by remember(view, conversationId, messageId) { mutableIntStateOf(0) }
    val resolved by produceState<Result<ConversationMessageResourceSource>?>(null,
        view, conversationId, messageId, hasContent, revision) {
        value = null
        value = try { Result.success(queries.messageResourceSource(view, conversationId, messageId)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }
    resolved?.exceptionOrNull()?.let { failure ->
        net.weero.measix.pilot.ui.components.webview.RenderedContentReadFailure(failure, { revision++ })
    }
    val message = resolved?.getOrNull()
    val source = remember(view, message) { message?.let { RenderedContentSource.Conversation(view, it) } }
    RichTextHost(source, content = content)
}

internal class RichTextActions(
    val uriHandler: UriHandler,
    val exportText: ((String, String, String) -> Unit)?,
    val preview: (String, Boolean) -> Unit,
)

/** One pending picker belongs to the host, not to a recomposing code block or table cell. */
@Composable
fun RichTextHost(
    source: RenderedContentSource?,
    vararg values: ProvidedValue<*>,
    files: FileManagementApplicationService = koinInject(),
    exports: MediaExportService = koinInject(),
    onFileLink: (suspend (String, () -> Unit) -> Unit)? = null,
    beforeFileNavigation: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val exportFailure = androidx.compose.ui.res.stringResource(R.string.workspace_detail_export_failed)
    val openFailure = androidx.compose.ui.res.stringResource(R.string.chat_message_attachment_open_failed)
    val previewUnavailable = androidx.compose.ui.res.stringResource(R.string.rendered_content_unavailable)
    val toaster = LocalToaster.current
    val navigator = LocalNavController.current
    val scope = rememberCoroutineScope()
    val anchors = remember(source) { mutableMapOf<String, MutableList<androidx.compose.foundation.relocation.BringIntoViewRequester>>() }
    var linkError by remember(source) { mutableStateOf<Exception?>(null) }
    val fileLink by rememberUpdatedState(onFileLink)
    val beforeNavigation by rememberUpdatedState(beforeFileNavigation)
    var pending by remember { mutableStateOf<TextDocumentExport?>(null) }
    fun reportExportFailure() {
        toaster.show(exportFailure, type = ToastType.Error)
    }
    val picker = rememberLauncherForActivityResult(CreateTextDocument) { uri ->
        val request = pending
        pending = null
        if (uri != null) scope.launch(start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
            try {
                exports.saveTextDocument(context, uri, request)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { reportExportFailure() }
        }
    }
    val handler = remember(source, scope, exports, context, toaster, openFailure, anchors) {
        object : UriHandler {
            override fun openUri(uri: String) {
                scope.launch {
                    try {
                        val original = requireNotNull(source) { "content_source_unavailable" }
                        files.requireContentAccess(original)
                        val url = java.net.URI(uri)
                        when {
                            original is RenderedContentSource.WorkspaceFile && url.scheme == null && url.rawAuthority == null &&
                                url.path.isNullOrEmpty() && url.fragment != null -> {
                                val id = url.fragment
                                val target = anchors[id]?.firstOrNull() ?: Regex("(.+)-(\\d+)$").matchEntire(id)?.let {
                                    anchors[it.groupValues[1]]?.getOrNull(it.groupValues[2].toInt())
                                }
                                requireNotNull(target) { "content_anchor_unavailable: $id" }.bringIntoView()
                            }
                            original is RenderedContentSource.WorkspaceFile && url.scheme == null && url.rawAuthority == null -> {
                                val path = original.file.resourcePath(uri)
                                requireNotNull(fileLink) { "content_file_navigation_unavailable: $path" }.invoke(path, beforeNavigation)
                            }
                            else -> exports.openContentLink(context, original, uri)
                        }
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        net.weero.measix.pilot.utils.logDiagnosticFailure("RichTextHost", "Unable to open content link", error)
                        linkError = error
                    }
                }
            }
        }
    }
    val actions = RichTextActions(
        uriHandler = handler,
        exportText = { text, fileName, mime ->
            if (pending == null && source != null) {
                val request = TextDocumentExport(source, text, fileName, mime)
                pending = request
                try { picker.launch(request) }
                catch (_: Exception) { pending = null; reportExportFailure() }
            }
        },
        preview = { html, offlineDiagram ->
            if (source != null) scope.launch {
                try {
                    files.requireContentAccess(source)
                    navigator.navigate(Screen.ContentPreview(document = RenderedContent(source, html, offlineDiagram), onFileLink = onFileLink))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { toaster.show(previewUnavailable, type = ToastType.Error) }
            }
        },
    )
    val imageResolver: suspend (String) -> ImageSource? = remember(source, files) {
        { url -> source?.let { files.resolveContentImage(it, url) } }
    }
    CompositionLocalProvider(
        *values,
        LocalRenderedContentSource provides source,
        LocalMarkdownAnchors provides anchors.takeIf { source is RenderedContentSource.WorkspaceFile },
        LocalRichTextActions provides actions,
        LocalUriHandler provides handler,
        LocalImageSourceResolver provides imageResolver,
        content = {
            linkError?.let { net.weero.measix.pilot.ui.components.ui.ErrorNotice(
                it.userVisibleDiagnostic(), title = openFailure, onDismiss = { linkError = null }) }
            content()
        },
    )
}

private object CreateTextDocument : ActivityResultContract<TextDocumentExport, Uri?>() {
    override fun createIntent(context: Context, input: TextDocumentExport): Intent =
        ActivityResultContracts.CreateDocument(input.mimeType).createIntent(context, input.fileName)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}

/** Render-only trees have no navigation authority or platform picker. */
internal object UnavailableContentUriHandler : UriHandler {
    override fun openUri(uri: String) = Unit
}
