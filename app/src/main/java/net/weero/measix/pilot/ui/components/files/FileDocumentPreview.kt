package net.weero.measix.pilot.ui.components.files

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.weero.measix.pilot.R
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import net.weero.measix.pilot.service.RenderedContent
import net.weero.measix.pilot.service.RenderedContentSource
import net.weero.measix.pilot.service.RenderedFileSource
import net.weero.measix.pilot.ui.components.richtext.MarkdownBlock
import net.weero.measix.pilot.ui.components.richtext.RichTextHost
import net.weero.measix.pilot.ui.components.webview.RenderedContentReadFailure
import net.weero.measix.pilot.ui.components.webview.RenderedContentWebView
import net.weero.measix.pilot.ui.components.webview.WebViewScrollPosition
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.SourceCode
import me.rerere.hugeicons.stroke.Eye
import net.weero.measix.pilot.ui.components.ui.Tooltip

internal class FilePreviewState {
    var sourceView by mutableStateOf(false)
    var markdownScroll = ScrollState(0)
    val webScroll = WebViewScrollPosition()
    val mediaPosition = mutableLongStateOf(0)
    val pdfPage = mutableIntStateOf(0)
    val imageHeights = net.weero.measix.pilot.ui.components.richtext.PreviewImageHeights()
    private var documentHash = 0
    fun bindDocument(text: String) {
        val hash = text.hashCode()
        if (documentHash != hash) { webScroll.clearImageSizes(); imageHeights.clear(); documentHash = hash }
    }

    companion object {
        val Saver = listSaver<FilePreviewState, Any>(
            save = { state ->
                val heights = state.imageHeights.snapshot()
                listOf(state.sourceView, state.markdownScroll.value, state.webScroll.x, state.webScroll.y,
                    state.mediaPosition.longValue, state.pdfPage.intValue, state.documentHash,
                    state.webScroll.imageSizes().flatMap { (index, size) -> listOf(index, size.first, size.second) }.toIntArray(),
                    heights.keys.toTypedArray(), heights.values.toFloatArray())
            },
            restore = { saved -> FilePreviewState().apply {
                sourceView = saved[0] as Boolean
                markdownScroll = ScrollState(saved[1] as Int)
                webScroll.x = saved[2] as Int; webScroll.y = saved[3] as Int
                mediaPosition.longValue = saved[4] as Long; pdfPage.intValue = saved[5] as Int
                documentHash = saved[6] as Int
                (saved[7] as IntArray).toList().chunked(3).forEach { webScroll.recordImageSize(it[0], it[1], it[2]) }
                val keys = saved[8] as Array<*>
                (saved[9] as FloatArray).forEachIndexed { index, height -> imageHeights.put(keys[index] as String, height) }
            } },
        )
    }
}

@Composable
internal fun FilePreviewToggleButton(sourceView: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val label = stringResource(if (sourceView) R.string.file_preview_rendered else R.string.file_preview_source)
    Tooltip(tooltip = { Text(label) }) {
        IconButton(onClick, enabled = enabled) { Icon(if (sourceView) HugeIcons.Eye else HugeIcons.SourceCode, label) }
    }
}

internal fun isRenderedFile(name: String): Boolean =
    fileType(name) == FileType.MARKDOWN || name.substringAfterLast('.', "").lowercase() in setOf("html", "htm", "svg")

/** All file browsers share rendering and actions; the caller keeps editing and source switching. */
@Composable
internal fun FileDocumentPreview(file: RenderedFileSource, text: String, name: String, modifier: Modifier = Modifier,
    onFileLink: (suspend (String, () -> Unit) -> Unit)? = null,
    state: FilePreviewState = remember(file) { FilePreviewState() },
) {
    SideEffect { state.bindDocument(text) }
    var revision by remember(file) { mutableIntStateOf(0) }
    val preview = remember(file, text, revision) { file.newPreview() }
    DisposableEffect(preview) { onDispose { preview.close() } }
    key(preview) { FileDocumentContent(preview, text, name, modifier, onFileLink, state) { revision++ } }
}

@Composable
private fun FileDocumentContent(file: RenderedFileSource, text: String, name: String, modifier: Modifier, onFileLink: (suspend (String, () -> Unit) -> Unit)?, state: FilePreviewState, retry: () -> Unit) {
    val source = remember(file) { RenderedContentSource.WorkspaceFile(file) }
    var ready by remember(file) { mutableStateOf(false) }
    var failure by remember(file) { mutableStateOf<Exception?>(null) }
    LaunchedEffect(file) {
        ready = false
        failure = null
        try {
            file.accessChanges.collect { file.requireAccess(); ready = true }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { ready = false; failure = error }
    }
    when {
        failure != null -> RenderedContentReadFailure(requireNotNull(failure), retry, modifier)
        !ready -> LinearProgressIndicator(modifier)
        else -> Column(modifier) {
            FilePreviewResourceStatus(file, retry)
            CompositionLocalProvider(net.weero.measix.pilot.ui.components.richtext.LocalPreviewImageHeights provides state.imageHeights) {
                RichTextHost(source, onFileLink = onFileLink) {
                    if (fileType(name) == FileType.MARKDOWN) {
                        MarkdownBlock(content = text, modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(state.markdownScroll))
                    } else RenderedContentWebView(RenderedContent(source, text), Modifier.weight(1f).fillMaxWidth(), state.webScroll)
                }
            }
        }
    }
}

@Composable
internal fun FilePreviewResourceStatus(file: RenderedFileSource, retry: () -> Unit) {
    val failures by file.resourceFailures.collectAsState()
    var dismissed by remember(file, failures) { mutableStateOf(false) }
    if (failures.isNotEmpty() && !dismissed) {
        net.weero.measix.pilot.ui.components.ui.ErrorNotice(
            detail = failures.joinToString("\n\n") { it.userVisibleDiagnostic() },
            title = stringResource(R.string.file_preview_resources_failed),
            onRetry = retry,
            onDismiss = { dismissed = true },
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}
