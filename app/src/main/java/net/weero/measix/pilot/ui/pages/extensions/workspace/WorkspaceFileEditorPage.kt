package net.weero.measix.pilot.ui.pages.extensions.workspace

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import net.weero.measix.pilot.service.workspace.WorkspaceExportRequest
import net.weero.measix.pilot.service.workspace.WorkspaceExportItem
import net.weero.measix.pilot.service.workspace.WorkspaceDocumentTreeDestination
import net.weero.measix.pilot.ui.components.files.*
import net.weero.measix.pilot.service.files.MediaPreviewSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.weero.measix.pilot.ui.components.toast.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.workspace.WorkspaceApplicationService
import net.weero.measix.pilot.service.workspace.WorkspaceQueryService
import net.weero.measix.pilot.service.workspace.WorkspaceTextPreviewResult
import net.weero.measix.pilot.ui.context.LocalNavController
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.FloppyDisk
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.theme.CustomColors
import net.weero.measix.pilot.ui.components.files.FileEditorState
import net.weero.measix.pilot.ui.components.files.FileTextEditor
import net.weero.measix.pilot.ui.components.ui.Tooltip
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import net.weero.measix.pilot.utils.logDiagnosticFailure
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject

/**
 * 工作区文件预览页，文本沿原服务显式进入编辑。
 *
 * FILES 区文件可编辑并保存; LINUX (rootfs) 区文件仅只读预览 (readOnly), 避免误改系统文件.
 */
@Composable
fun WorkspaceFileEditorPage(
    id: String,
    area: WorkspaceStorageArea,
    path: String,
    applicationService: WorkspaceApplicationService = koinInject(),
    queryService: WorkspaceQueryService = koinInject(),
) {
    key(id, area, path) {
        WorkspaceFileEditorContent(id, area, path, applicationService, queryService)
    }
}

@Composable
private fun WorkspaceFileEditorContent(
    id: String, area: WorkspaceStorageArea, path: String,
    applicationService: WorkspaceApplicationService, queryService: WorkspaceQueryService,
) {
    val toaster = LocalToaster.current
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val editable = area == WorkspaceStorageArea.FILES
    val fileName = path.substringAfterLast('/').ifBlank { path }

    val type = remember(fileName) { fileType(fileName) }
    var editing by remember(id, area, path) { mutableStateOf(false) }
    var sourceView by remember(id, area, path) { mutableStateOf(false) }
    var media by remember(id, area, path) { mutableStateOf<MediaPreviewSource?>(null) }
    var renderedFile by remember(id, area, path) { mutableStateOf<net.weero.measix.pilot.service.RenderedFileSource?>(null) }
    val context = LocalContext.current
    val savedText = stringResource(R.string.workspace_file_editor_saved)
    val saveButtonText = stringResource(R.string.common_save)
    val tooLargeText = stringResource(R.string.workspace_file_editor_too_large)

    val textState = remember(id, area, path) { FileEditorState() }
    var readRevision by remember(id, area, path) { androidx.compose.runtime.mutableIntStateOf(0) }
    var loading by remember(id, area, path) { mutableStateOf(true) }
    var loadError by remember(id, area, path) { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var saving by remember(id, area, path) { mutableStateOf(false) }
    var preparingPreview by remember { mutableStateOf(false) }
    var publishedText by remember(id, area, path) { mutableStateOf<String?>(null) }
    var discard by remember(id, area, path) { mutableStateOf(false) }
    val dirty by remember(textState) { derivedStateOf {
        textState.revision
        editable && publishedText?.let { textState.snapshot() != it } == true
    } }
    var exporting by remember { mutableStateOf(false) }
    var exportJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var exportPending by remember(id, area, path) { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && exportPending) {
            exporting = true
            exportJob = scope.launch {
                try {
                    applicationService.exportFiles(WorkspaceExportRequest(id, area, listOf(path)),
                        WorkspaceDocumentTreeDestination(context.contentResolver, uri)) { item ->
                        if (item is WorkspaceExportItem.Failed) saveError = item.cause.userVisibleDiagnostic()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { saveError = failure.userVisibleDiagnostic() }
                finally { exporting = false }
            }
        }
        exportPending = false
    }
    fun download() {
        if (exportPending || exporting) return
        exportPending = true
        try { exportLauncher.launch(null) }
        catch (failure: Exception) { exportPending = false; saveError = failure.userVisibleDiagnostic() }
    }
    fun external() { scope.launch {
        try {
            val uri = applicationService.previewDocumentUri(id, path, "${context.packageName}.documents")
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substringAfterLast('.').lowercase()) ?: "application/octet-stream"
            context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, mime).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), fileName))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { saveError = failure.userVisibleDiagnostic() }
    } }
    fun close() {
        if (saving || preparingPreview) return
        if (dirty) discard = true else navController.popBackStack()
    }
    BackHandler { close() }

    LaunchedEffect(id, area, path, readRevision) {
        loading = true
        loadError = null
        try {
            if (type == FileType.VIDEO || type == FileType.AUDIO) {
                val source = applicationService.mediaSource(id, area, path)
                try { media = source; loading = false; kotlinx.coroutines.awaitCancellation() }
                finally { source.close() }
            } else if (type != FileType.PDF) {
            when (val result = queryService.readTextForPreview(id, area, path)) {
                is WorkspaceTextPreviewResult.Success -> {
                    textState.replaceText(result.content)
                    publishedText = result.content
                }
                is WorkspaceTextPreviewResult.TooLarge -> loadError = tooLargeText.format(result.sizeBytes)
                is WorkspaceTextPreviewResult.Unavailable -> {
                    logDiagnosticFailure("WorkspaceFileEditor", "Read failed", result.cause)
                    loadError = result.cause.userVisibleDiagnostic()
                }
            }
            }
            if (isRenderedFile(fileName)) renderedFile = applicationService.renderedSource(id, area, path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logDiagnosticFailure("WorkspaceFileEditor", "Read failed", error)
            loadError = error.userVisibleDiagnostic()
        } finally {
            loading = false
        }
    }

    media?.let {
        MediaPreview(it, { navController.popBackStack() },
            speechPlayback = koinInject<net.weero.measix.pilot.service.SpeechApplicationService>().playback,
            onExternal = if (area == WorkspaceStorageArea.FILES) ::external else null,
            onDownload = ::download, actionsEnabled = !exporting && !exportPending, status = {
                if (exporting) androidx.compose.material3.Surface {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(8.dp))
                        Text(stringResource(R.string.remote_workspace_download), Modifier.weight(1f))
                        TextButton({ exportJob?.cancel() }) { Text(stringResource(R.string.common_cancel)) }
                    }
                }
            })
        saveError?.let { detail -> net.weero.measix.pilot.ui.components.ui.ErrorDetails(
            remember(detail) { net.weero.measix.pilot.service.ChatError(detail = detail) }, onDismiss = { saveError = null }) }
        return
    }
    val showSaveAction = editable && editing && !loading && loadError == null
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { close() }, enabled = !saving && !preparingPreview) {
                        Icon(HugeIcons.ArrowLeft01, stringResource(R.string.back))
                    }
                },
                actions = {
                    if (editing && isRenderedFile(fileName)) {
                        TextButton(enabled = !dirty && !saving && !preparingPreview, onClick = {
                            preparingPreview = true
                            scope.launch {
                                try {
                                    renderedFile = applicationService.renderedSource(id, area, path)
                                    sourceView = false
                                    editing = false
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { saveError = failure.userVisibleDiagnostic() }
                                finally { preparingPreview = false }
                            }
                        }) { Text(stringResource(R.string.file_preview_rendered)) }
                    }
                    if (publishedText != null && !editing) {
                        if (isRenderedFile(fileName)) TextButton({ sourceView = !sourceView }) {
                            Text(stringResource(if (sourceView) R.string.file_preview_rendered else R.string.file_preview_source))
                        }
                        if (editable) TextButton({ editing = true }) { Text(stringResource(R.string.edit)) }
                    }
                    if (showSaveAction) {
                        Tooltip(tooltip = { Text(saveButtonText) }) {
                            IconButton(
                                onClick = {
                                    if (saving || preparingPreview) return@IconButton
                                    saving = true
                                    val body = textState.snapshot()
                                    scope.launch {
                                        try {
                                            applicationService.writeText(
                                                workspaceId = id,
                                                path = path,
                                                text = body,
                                            )
                                            publishedText = body
                                            toaster.show(savedText, type = ToastType.Success)
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (error: Exception) {
                                            logDiagnosticFailure("WorkspaceFileEditor", "Save failed", error)
                                            saveError = error.userVisibleDiagnostic()
                                        } finally { saving = false }
                                    }
                                },
                                enabled = !saving && !preparingPreview,
                            ) {
                                Icon(HugeIcons.FloppyDisk, saveButtonText)
                            }
                        }
                    }
                },
                colors = CustomColors.topBarColors,
                expandedHeight = 48.dp,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        when {
            loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            loadError != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
            ) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    net.weero.measix.pilot.ui.components.ui.ErrorNotice(loadError.orEmpty(), onRetry = { readRevision++ })
                    Row {
                        TextButton(::download, enabled = !exporting && !exportPending) { Text(stringResource(R.string.remote_workspace_download)) }
                        if (area == WorkspaceStorageArea.FILES) TextButton(::external) { Text(stringResource(R.string.remote_workspace_open_external)) }
                    }
                    if (exporting) {
                        CircularProgressIndicator()
                        TextButton({ exportJob?.cancel() }) { Text(stringResource(R.string.common_cancel)) }
                    }
                }
            }

            type == FileType.PDF -> PdfPreview("$id:$area:$path", { applicationService.openPreview(id, area, path) },
                Modifier.fillMaxSize().padding(innerPadding)) { loadError = it.userVisibleDiagnostic() }
            isRenderedFile(fileName) && !editing && !sourceView && renderedFile != null -> FileDocumentPreview(
                requireNotNull(renderedFile), publishedText.orEmpty(), fileName,
                Modifier.fillMaxSize().padding(innerPadding), onFileLink = { target, before ->
                    renderedFile?.requireAccess()
                    before()
                    navController.navigate(net.weero.measix.pilot.Screen.WorkspaceFileEditor(id, area.name, target))
                })
            else -> FileTextEditor(
                state = textState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding(),
                readOnly = !editing || !editable || saving || preparingPreview,
                showNavigation = true,
                fillViewport = true,
                fileName = fileName,
            )
        }
    }
    if (discard) AlertDialog(
        onDismissRequest = { discard = false },
        title = { Text(stringResource(R.string.remote_workspace_unsaved)) },
        confirmButton = {
            TextButton(onClick = { discard = false; navController.popBackStack() }) {
                Text(stringResource(R.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { discard = false }) { Text(stringResource(R.string.common_cancel)) }
        },
    )
    saveError?.let { detail ->
        net.weero.measix.pilot.ui.components.ui.ErrorDetails(
            remember(detail) { net.weero.measix.pilot.service.ChatError(detail = detail) },
            onDismiss = { saveError = null })
    }

}
