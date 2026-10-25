package net.weero.measix.pilot.ui.components.files

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
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

internal fun isRenderedFile(name: String): Boolean =
    fileType(name) == FileType.MARKDOWN || name.substringAfterLast('.', "").lowercase() in setOf("html", "htm", "svg")

/** All file browsers share rendering and actions; the caller keeps editing and source switching. */
@Composable
internal fun FileDocumentPreview(file: RenderedFileSource, text: String, name: String, modifier: Modifier = Modifier, onFileLink: (suspend (String, () -> Unit) -> Unit)? = null) {
    var revision by remember(file) { mutableIntStateOf(0) }
    val preview = remember(file, text, revision) { file.newPreview() }
    DisposableEffect(preview) { onDispose { preview.close() } }
    key(preview) { FileDocumentContent(preview, text, name, modifier, onFileLink) { revision++ } }
}

@Composable
private fun FileDocumentContent(file: RenderedFileSource, text: String, name: String, modifier: Modifier, onFileLink: (suspend (String, () -> Unit) -> Unit)?, retry: () -> Unit) {
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
            RichTextHost(source, onFileLink = onFileLink) {
                if (fileType(name) == FileType.MARKDOWN) {
                    MarkdownBlock(content = text, modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()))
                } else RenderedContentWebView(RenderedContent(source, text), Modifier.weight(1f).fillMaxWidth())
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
