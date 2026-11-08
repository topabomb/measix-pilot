package net.weero.measix.pilot.ui.components.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import net.weero.measix.pilot.service.CreatedExportDocument
import net.weero.measix.pilot.service.MediaExportService
import net.weero.measix.pilot.service.PreparedExport
import net.weero.measix.pilot.utils.logDiagnosticFailure
import net.weero.measix.pilot.utils.userVisibleDiagnostic

internal enum class ExportAction { SAVE, SHARE, GALLERY }

/** Retained by the page VM while its ActivityResult launcher can be recreated. Main thread only. */
internal class DocumentExportActions(
    private val service: MediaExportService,
    private val scope: CoroutineScope,
) {
    private var pending by mutableStateOf<PreparedExport?>(null)
    private var working by mutableStateOf(false)
    private var closed = false
    private var lastTarget: Uri? = null
    val busy: Boolean get() = working || pending != null
    var failure by mutableStateOf<String?>(null)
        private set
    var completedName by mutableStateOf<String?>(null)
        private set

    suspend fun execute(
        context: Context,
        action: ExportAction,
        prepare: suspend () -> PreparedExport,
        launchDocument: (String) -> Unit,
    ) {
        if (closed || busy) return
        working = true
        failure = null
        completedName = null
        var candidate: PreparedExport? = null
        try {
            val request = prepare()
            candidate = request
            currentCoroutineContext().ensureActive()
            if (closed) return
            when (action) {
                ExportAction.SAVE -> {
                    pending = request
                    launchDocument(request.fileName)
                    // A new CreateDocument launch may legitimately reuse a previous URI.
                    lastTarget = null
                    candidate = null
                }
                ExportAction.SHARE -> {
                    service.sharePrepared(context, request)
                    completedName = request.fileName
                }
                ExportAction.GALLERY -> {
                    service.savePreparedImage(context, request)
                    completedName = request.fileName
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            report(error)
        } finally {
            candidate?.let {
                if (pending === it) pending = null
                service.release(it)
            }
            working = false
        }
    }

    fun onDocumentResult(context: Context, uri: Uri?) {
        if (uri != null && uri == lastTarget) return
        val request = pending
        pending = null
        if (uri == null) {
            request?.let(service::release)
            return
        }
        lastTarget = uri
        working = true
        val app = context.applicationContext
        // Enter the service's compensation scope even if navigation already cancelled the VM scope.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                service.savePreparedDocument(app, CreatedExportDocument(uri), request)
                completedName = request?.fileName
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                report(error)
            } finally {
                working = false
            }
        }
    }

    fun clearFailure() { failure = null }
    fun acknowledgeCompletion() { completedName = null }

    fun close() {
        closed = true
        pending?.let(service::release)
        pending = null
    }

    private fun report(error: Exception) {
        logDiagnosticFailure("DocumentExport", "Export failed", error)
        if (!closed) failure = error.userVisibleDiagnostic()
    }
}

@Composable
internal fun rememberExportDocumentLauncher(actions: DocumentExportActions, mimeType: String): (String) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mimeType)) { uri ->
        actions.onDocumentResult(context, uri)
    }
    return launcher::launch
}
