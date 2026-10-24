package net.weero.measix.pilot.ui.components.files

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
internal fun FileDocumentPreview(file: RenderedFileSource, text: String, name: String, modifier: Modifier = Modifier) {
    val source = remember(file) { RenderedContentSource.WorkspaceFile(file) }
    var ready by remember(file) { mutableStateOf(false) }
    var failure by remember(file) { mutableStateOf<Exception?>(null) }
    var revision by remember(file) { mutableIntStateOf(0) }
    LaunchedEffect(file, revision) {
        ready = false
        failure = null
        try {
            file.accessChanges.collect { file.requireAccess(); ready = true }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { ready = false; failure = error }
    }
    when {
        failure != null -> RenderedContentReadFailure(requireNotNull(failure), { revision++ }, modifier)
        !ready -> LinearProgressIndicator(modifier)
        else -> RichTextHost(source) {
            if (fileType(name) == FileType.MARKDOWN) {
                MarkdownBlock(content = text, modifier = modifier.verticalScroll(rememberScrollState()))
            } else RenderedContentWebView(RenderedContent(source, text), modifier)
        }
    }
}
