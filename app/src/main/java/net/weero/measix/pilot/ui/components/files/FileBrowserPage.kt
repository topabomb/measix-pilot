package net.weero.measix.pilot.ui.components.files

import net.weero.measix.pilot.service.files.*

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.SquareArrowUpRight
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Refresh
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Tick02
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.hugeicons.stroke.FloppyDisk
import me.rerere.hugeicons.stroke.Download01
import me.rerere.hugeicons.stroke.Image01
import me.rerere.hugeicons.stroke.Pdf01
import net.weero.measix.pilot.R
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.files.FileBrowserSession
import net.weero.measix.pilot.ui.components.files.FileRow
import net.weero.measix.pilot.ui.components.files.FileType
import net.weero.measix.pilot.ui.components.files.fileType
import net.weero.measix.pilot.ui.components.files.MediaPreview
import net.weero.measix.pilot.service.files.MediaPreviewSource
import net.weero.measix.pilot.ui.components.files.PdfPreview
import net.weero.measix.pilot.ui.components.files.FileTextEditor
import net.weero.measix.pilot.ui.components.files.fileTextFormat
import androidx.compose.foundation.layout.consumeWindowInsets
import net.weero.measix.pilot.ui.components.ui.Tooltip
import net.weero.measix.pilot.ui.components.ui.ImagePreviewDialog
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.utils.fileSizeToString
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private data class NamePrompt(val title: Int, val handle: FileBrowserSession, val initial: String = "", val submit: (String) -> Unit)
private data class FolderPrompt(val title: Int, val handle: FileBrowserSession, val files: List<BrowserFile>, val action: BrowserFileAction)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileBrowserPage(vm: FileBrowserVM, title: String, diagnostic: String? = null,
    status: @Composable () -> Unit = {}, titleContent: (@Composable () -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val writable = state.handle?.writable == true
    val nav = LocalNavController.current
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(setOf<String>()) }
    var filter by remember { mutableStateOf("") }
    var filtering by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(false) }
    var sort by remember { mutableIntStateOf(0) }
    val positions = remember(state.handle) { linkedMapOf<String, androidx.compose.foundation.lazy.LazyListState>() }
    val fileListState = remember(state.handle, state.path) {
        positions.getOrPut(state.path) { androidx.compose.foundation.lazy.LazyListState() }.also {
            while (positions.size > 32) positions.remove(positions.keys.first())
        }
    }
    val loadingListState = rememberLazyListState()
    LaunchedEffect(filter, hidden, sort) { if (filter.isNotEmpty()) fileListState.scrollToItem(0) }
    LaunchedEffect(state.results, state.running) {
        if (!state.running && state.results.isNotEmpty()) fileListState.scrollToItem(0)
    }
    var menu by remember { mutableStateOf(false) }
    var add by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var detailFile by remember { mutableStateOf<BrowserFile?>(null) }
    var name by remember { mutableStateOf<NamePrompt?>(null) }
    var folder by remember { mutableStateOf<FolderPrompt?>(null) }
    var delete by remember { mutableStateOf<Pair<FileBrowserSession, List<BrowserFile>>?>(null) }
    var verification by remember { mutableStateOf<Pair<FileBrowserSession, String>?>(null) }
    var leave by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(false) }
    val visible = remember(state.directory, hidden) { state.directory?.files.orEmpty().filter { hidden || !it.name.startsWith('.') } }
    val entries = remember(visible, filter, sort) { visible.filter { it.name.contains(filter, true) }
        .sortedWith(compareBy<BrowserFile> { !it.directory }.thenComparator { a, b ->
            val order = when (sort) {
                1 -> compareValues(b.modifiedAt, a.modifiedAt)
                2 -> compareValues(b.size, a.size)
                else -> 0
            }
            if (order != 0) order else a.name.compareTo(b.name, ignoreCase = true)
        }) }
    val selectedFiles = state.directory?.files.orEmpty().filter { it.path in selected }
    LaunchedEffect(state.directory) {
        state.directory?.let { selected = selected.intersect(it.files.map { file -> file.path }.toSet()) }
    }

    fun exit() { if (state.running) leave = true else scope.launch { vm.leave(); nav.popBackStack() } }
    fun back() {
        when {
            selected.isNotEmpty() -> selected = emptySet()
            filtering -> { filter = ""; filtering = false; focus.clearFocus(); keyboard?.hide() }
            state.running -> exit()
            state.path.isNotEmpty() -> vm.browse(state.path.substringBeforeLast('/', ""))
            else -> exit()
        }
    }
    BackHandler { back() }
    LaunchedEffect(state.path, state.revoked) { filter = ""; filtering = false }
    LaunchedEffect(state.path, state.handle) {
        focus.clearFocus()
        keyboard?.hide()
        selected = emptySet()
        if (name?.handle !== state.handle) name = null
        if (folder?.handle !== state.handle) folder = null
        if (delete?.first !== state.handle) delete = null
        if (verification?.first !== state.handle) verification = null
        if (state.revoked) { detailFile = null; details = false; results = false }
    }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch {
                try { vm.resume() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { vm.report(error) }
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    val download = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        vm.downloadResult(uri, false)
    }
    val downloadTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        vm.downloadResult(uri, true)
    }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.uploadResult(uris)
    }
    fun export(files: List<BrowserFile>) {
        val handle = state.handle ?: return
        if (files.isEmpty() || files.any { it.directory }) return
        vm.prepareDownload(handle, files, files.size > 1)
        try { if (files.size == 1) download.launch(files.single().name) else downloadTree.launch(null) }
        catch (error: Exception) { vm.cancelDownloadPicker(); vm.report(error) }
    }
    fun share(file: BrowserFile, external: Boolean) {
        val original = state.handle ?: return
        vm.execute(listOf(file.path), original) { _, handle ->
            handle.share(file) { copy ->
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", copy, file.name)
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val intent = Intent(if (external) Intent.ACTION_VIEW else Intent.ACTION_SEND).apply {
                    if (external) setDataAndType(uri, mime) else { type = mime; putExtra(Intent.EXTRA_STREAM, uri) }
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, file.name))
            }
            BrowserOperationResult(file.path, BrowserOutcome.SUCCEEDED)
        }
    }

    state.preview?.let { file ->
        val handle = state.handle
        if (handle != null && !state.revoked) {
            RemoteFilePreview(file, handle, vm, onClose = vm::closePreview, onDownload = { export(listOf(file)) }, onShare = { share(file, it) })
            return
        }
    }
    Scaffold(topBar = {
        TopAppBar(expandedHeight = if (titleContent == null) 48.dp else with(LocalDensity.current) { maxOf(48.dp, 36.sp.toDp() + 8.dp) }, title = {
            if (filtering && selected.isEmpty()) {
                val label = stringResource(R.string.remote_workspace_filter)
                val inputFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { inputFocus.requestFocus() }
                BasicTextField(filter, { filter = it },
                    Modifier.fillMaxWidth().focusRequester(inputFocus).semantics { contentDescription = label },
                    singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
                    decorationBox = { input ->
                        Box { if (filter.isEmpty()) Text(label, style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            input() }
                    })
            } else if (selected.isEmpty() && titleContent != null) titleContent()
            else Text(if (selected.isEmpty()) title
                else stringResource(R.string.remote_workspace_selected, selected.size), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
        },
            navigationIcon = { IconButton({ back() }) {
                Icon(if (selected.isEmpty()) HugeIcons.ArrowLeft01 else HugeIcons.Cancel01,
                    stringResource(if (selected.isEmpty()) R.string.back else R.string.common_cancel)) } },
            actions = {
                if (selected.isEmpty() && !filtering) IconButton(vm::retry, enabled = !state.loading && !state.running) { Icon(HugeIcons.Refresh, stringResource(R.string.enterprise_budget_refresh)) }
                if (selected.isEmpty() && filtering) IconButton({ filter = ""; filtering = false; focus.clearFocus(); keyboard?.hide() }) {
                    Icon(HugeIcons.Cancel01, stringResource(R.string.common_cancel))
                }
                if (selected.isNotEmpty()) Tooltip(tooltip = { Text(stringResource(R.string.remote_workspace_download)) }) {
                    IconButton({ export(selectedFiles) }, enabled = !state.running && !state.loading &&
                        selectedFiles.isNotEmpty() && selectedFiles.none { it.directory }) {
                        Icon(HugeIcons.Download01, stringResource(R.string.remote_workspace_download))
                    }
                }
                Box {
                    IconButton({ menu = true }, modifier = Modifier.testTag("remote-browser-menu")) { Icon(HugeIcons.MoreVertical, stringResource(R.string.more_options)) }
                    DropdownMenu(menu, { menu = false }) {
                        if (selected.isNotEmpty()) {
                            for ((label, action) in listOf(R.string.remote_workspace_move to BrowserFileAction.MOVE, R.string.remote_workspace_copy to BrowserFileAction.COPY)) {
                                DropdownMenuItem(text = { Text(stringResource(label)) }, enabled = writable && !state.running && selectedFiles.isNotEmpty() && selectedFiles.all { it.directory || it.versioned },
                                    onClick = { menu = false; state.handle?.let { folder = FolderPrompt(label, it, selectedFiles, action) } })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) }, enabled = writable && !state.running && selectedFiles.isNotEmpty() && selectedFiles.all { it.directory || it.versioned },
                                onClick = { menu = false; state.handle?.let { delete = it to selectedFiles } })
                            HorizontalDivider()
                        }
                        listOf(R.string.remote_workspace_sort_name, R.string.remote_workspace_sort_time, R.string.remote_workspace_sort_size).forEachIndexed { index, label ->
                            DropdownMenuItem(text = { Text(stringResource(label)) }, modifier = Modifier.semantics { this.selected = sort == index }, trailingIcon = {
                                if (sort == index) Icon(HugeIcons.Tick02, null)
                            }, onClick = { sort = index; menu = false })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_hidden)) }, trailingIcon = { Checkbox(hidden, null) }, onClick = { hidden = !hidden; menu = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_select_all)) }, enabled = state.directory != null && !state.loading && !state.running,
                            onClick = { selected = entries.map { it.path }.toSet(); menu = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_details)) }, onClick = { detailFile = null; details = true; menu = false })
                    }
                }
            })
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp).imePadding()) {
            // A short IME viewport cannot fit another touch target below the title-bar filter.
            if (filtering && maxHeight < 48.dp) return@BoxWithConstraints
            val wide = maxWidth >= 600.dp && LocalDensity.current.fontScale < 1.3f
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.revoked) Text(stringResource(R.string.remote_workspace_revoked))
                else if (state.handle == null) {
                    status()
                    Button(vm::retry, enabled = !state.loading) { Text(stringResource(R.string.enterprise_budget_refresh)) }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RemotePathBar(state.path, enabled = !state.running, modifier = Modifier.weight(1f), onPath = vm::browse)
                        if (selected.isEmpty()) {
                            if (!filtering) IconButton({ filtering = true },
                                enabled = state.directory != null && !state.loading) { Icon(HugeIcons.Search01, stringResource(R.string.remote_workspace_filter)) }
                            Box {
                                val addLabel = stringResource(R.string.add)
                                IconButton({ add = true }, enabled = writable && !state.running,
                                    modifier = Modifier.semantics { contentDescription = addLabel }) { Icon(HugeIcons.Add01, null) }
                                DropdownMenu(add, { add = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_upload)) }, onClick = {
                                        add = false; state.handle?.let {
                                            vm.prepareUpload(it, state.path)
                                            try { upload.launch(arrayOf("*/*")) }
                                            catch (error: Exception) { vm.cancelUploadPicker(); vm.report(error) }
                                        }
                                    })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_new_folder)) }, onClick = {
                                        add = false; val path = state.path; val original = state.handle ?: return@DropdownMenuItem
                                        name = NamePrompt(R.string.remote_workspace_new_folder, original) { value ->
                                            val target = original.child(path, value)
                                            vm.execute(listOf(target), original) { _, handle -> handle.createDirectory(target) }
                                        }
                                    })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_new_text)) }, onClick = {
                                        add = false; val path = state.path; val original = state.handle ?: return@DropdownMenuItem
                                        name = NamePrompt(R.string.remote_workspace_new_text, original, "untitled.txt") { value ->
                                            val target = original.child(path, value)
                                            vm.execute(listOf(target), original) { _, handle -> handle.upload(target, null, 0, { byteArrayOf().inputStream() }) }
                                        }
                                    })
                                }
                            }
                        }
                    }
                }
                if (state.loading && state.directory == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.remote_workspace_loading), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(vm::cancel) { Text(stringResource(R.string.common_cancel)) }
                    }
                }
                if (state.running) TransferStatus(state, vm::cancel)
                state.error?.let { net.weero.measix.pilot.ui.components.ui.ErrorNotice(it,
                    title = stringResource(R.string.remote_workspace_operation_failed), onRetry = { vm.retry() },
                    onDismiss = vm::dismissError) }
                PullToRefreshBox(isRefreshing = state.loading, onRefresh = { if (!state.running) vm.retry() }, modifier = Modifier.weight(1f)) {
                    LazyColumn(Modifier.fillMaxSize().testTag("remote-file-list"),
                        state = if (state.directory == null) loadingListState else fileListState,
                        contentPadding = PaddingValues(bottom = 8.dp)) {
                        if (state.directory != null) item {
                            state.directory?.let { directory ->
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
                                    Text(if (filter.isEmpty()) stringResource(R.string.remote_workspace_item_count, entries.size)
                                        else stringResource(R.string.remote_workspace_filtered_count, entries.size, visible.size), style = MaterialTheme.typography.labelSmall)
                                    directory.usedBytes?.let { Text(stringResource(R.string.remote_workspace_used, it.fileSizeToString()), style = MaterialTheme.typography.labelSmall) }
                                    directory.availableBytes?.let { Text(stringResource(R.string.remote_workspace_free, it.fileSizeToString()), style = MaterialTheme.typography.labelSmall) }
                                }
                            }
                        }
                        if ((state.results.isNotEmpty() && !state.running) || (wide && entries.isNotEmpty())) item {
                            if (state.results.isNotEmpty() && !state.running) {
                                val completed = state.results.count { it.outcome == BrowserOutcome.SUCCEEDED }
                                val unknown = state.results.count { it.outcome == BrowserOutcome.UNKNOWN }
                                val problems = state.results.count { it.outcome == BrowserOutcome.FAILED || it.outcome == BrowserOutcome.PARTIAL }
                                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small) {
                                    Column {
                                        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(when {
                                                unknown > 0 -> stringResource(R.string.remote_workspace_verification_count, unknown)
                                                problems > 0 -> stringResource(R.string.remote_workspace_problem_count, problems)
                                                state.results.any { it.outcome == BrowserOutcome.CANCELLED } -> stringResource(R.string.remote_workspace_cancelled)
                                                state.results.any { it.outcome == BrowserOutcome.NOT_STARTED } -> stringResource(R.string.remote_workspace_not_started)
                                                else -> state.resultLabel?.let { stringResource(R.string.file_browser_result_summary, stringResource(it), completed) }
                                                    ?: stringResource(R.string.remote_workspace_completed_count, completed)
                                            }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                                color = if (unknown > 0 || problems > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                                            TextButton({ results = true }) { Text(stringResource(R.string.remote_workspace_details)) }
                                            IconButton(vm::clearResults, enabled = unknown == 0) { Icon(HugeIcons.Cancel01, stringResource(R.string.update_card_close)) }
                                        }
                                        state.resultDirectory?.takeIf { completed > 0 }?.let { destination ->
                                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Text(destination.ifEmpty { "/" }, Modifier.weight(1f), maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                                if (destination != state.path) TextButton({ vm.browse(destination) }) {
                                                    Text(stringResource(R.string.file_browser_open_destination))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (wide && entries.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.width(if (selected.isEmpty()) 40.dp else 48.dp))
                                Text(stringResource(R.string.image_viewer_info_filename), Modifier.weight(1f).padding(horizontal = 12.dp),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                RemoteFileColumns(null)
                                Spacer(Modifier.width(48.dp))
                            }
                        }
                        if (!state.loading && state.directory != null && entries.isEmpty()) item {
                            Text(stringResource(if (filter.isNotBlank()) R.string.search_page_no_results else R.string.remote_workspace_empty), Modifier.padding(vertical = 32.dp))
                        }
                        items(entries, key = { it.path }) { file ->
                            FileRow(file.name, if (wide) "" else listOfNotNull(file.size?.takeUnless { file.directory }?.fileSizeToString(), file.modifiedAt?.let { formatFileTime(it) }).joinToString(" · "),
                                file.directory, file.path in selected, selected.isNotEmpty(),
                                onOpen = { if (!state.running) { if (file.directory) vm.browse(file.path) else vm.openPreview(file) } },
                                onSelect = { if (!state.running) selected = if (file.path in selected) selected - file.path else selected + file.path },
                                compact = true,
                                metadata = if (wide) { { RemoteFileColumns(file) } } else null,
                                thumbnail = if (file.directory) null else { {
                                    Icon(when {
                                        fileType(file.name) == FileType.IMAGE -> HugeIcons.Image01
                                        file.name.endsWith(".pdf", ignoreCase = true) -> HugeIcons.Pdf01
                                        fileTextFormat(file.name) != null -> HugeIcons.FileEdit
                                        else -> HugeIcons.File02
                                    }, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                } },
                            ) { dismiss ->
                                if (!file.directory) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_download)) }, onClick = { dismiss(); export(listOf(file)) }, enabled = !state.running)
                                    DropdownMenuItem(text = { Text(stringResource(R.string.common_share)) }, onClick = { dismiss(); share(file, false) }, enabled = !state.running)
                                }
                                DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_rename)) }, enabled = writable && !state.running && (file.directory || file.versioned), onClick = {
                                    dismiss(); val original = state.handle ?: return@DropdownMenuItem
                                    name = NamePrompt(R.string.remote_workspace_rename, original, file.name) { value ->
                                        val target = original.child(file.path.substringBeforeLast('/', ""), value)
                                        vm.rename(original, file, target)
                                    }
                                })
                                for ((label, action) in listOf(R.string.remote_workspace_move to BrowserFileAction.MOVE, R.string.remote_workspace_copy to BrowserFileAction.COPY)) {
                                    DropdownMenuItem(text = { Text(stringResource(label)) }, enabled = writable && !state.running && (file.directory || file.versioned), onClick = { dismiss(); state.handle?.let { folder = FolderPrompt(label, it, listOf(file), action) } })
                                }
                                DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) }, enabled = writable && !state.running && (file.directory || file.versioned), onClick = { dismiss(); state.handle?.let { delete = it to listOf(file) } })
                                DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_details)) }, onClick = { dismiss(); detailFile = file; details = true })
                                if (!file.directory && !file.versioned) Text(stringResource(R.string.remote_workspace_no_version), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
    name?.let { prompt -> NameDialog(prompt, { name = null }, vm::report) }
    delete?.let { (handle, files) -> ConfirmDialog(stringResource(R.string.common_delete),
        stringResource(R.string.remote_workspace_delete_confirm) + "\n\n" + files.joinToString("\n") { it.name }, { delete = null }) {
        vm.mutate(BrowserFileAction.DELETE, files, original = handle); selected = emptySet(); delete = null
    } }
    if (leave) ConfirmDialog(stringResource(R.string.stop), stringResource(R.string.remote_workspace_stop_leave), { leave = false }) {
        scope.launch { vm.leave(); nav.popBackStack() }
    }
    folder?.let { prompt ->
        FolderDialog(prompt.handle, prompt, { folder = null }) { path, _ ->
            vm.mutate(prompt.action, prompt.files, path, original = prompt.handle)
            folder = null; selected = emptySet()
        }
    }
    state.overwrite?.let { prompt -> ConfirmDialog(stringResource(R.string.remote_workspace_overwrite),
        listOfNotNull(prompt.target.path, prompt.target.modifiedAt?.let { formatFileTime(it, full = true) },
            prompt.target.size?.fileSizeToString()).joinToString("\n"),
        { vm.answerOverwrite(prompt, false) }) {
        vm.answerOverwrite(prompt, true)
    } }
    verification?.let { (handle, path) -> VerifyResultDialog(handle, path, { verification = null }) {
        verification = null; vm.dismissVerifiedResult(handle, path); vm.retry()
    } }
    if (results) AlertDialog(onDismissRequest = { results = false }, title = { Text(stringResource(R.string.remote_workspace_results)) },
        confirmButton = { TextButton({ results = false }) { Text(stringResource(R.string.common_confirm)) } }, text = {
            LazyColumn(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(state.results) { result ->
                    Column {
                        Text(result.path, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(when (result.outcome) {
                            BrowserOutcome.SUCCEEDED -> R.string.remote_workspace_completed
                            BrowserOutcome.FAILED -> R.string.remote_workspace_operation_failed
                            BrowserOutcome.PARTIAL -> R.string.remote_workspace_partial
                            BrowserOutcome.UNKNOWN -> R.string.remote_workspace_unknown
                            BrowserOutcome.NOT_STARTED -> R.string.remote_workspace_not_started
                            BrowserOutcome.CANCELLED -> R.string.remote_workspace_cancelled
                        }), style = MaterialTheme.typography.bodySmall)
                        result.diagnostic?.let { Diagnostic(it) }
                        if (result.outcome == BrowserOutcome.UNKNOWN) TextButton({
                            results = false; state.handle?.let { verification = it to result.path }
                        }, enabled = !state.running) { Text(stringResource(R.string.remote_workspace_read_verify)) }
                    }
                }
            }
        })
    if (details) AlertDialog(onDismissRequest = { details = false }, confirmButton = { TextButton({ details = false }) { Text(stringResource(R.string.common_confirm)) } },
        title = { Text(stringResource(R.string.remote_workspace_details)) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                detailFile?.let { FileMetadata(it) }
                if (detailFile == null) {
                    Text(if (state.path.isEmpty()) stringResource(R.string.remote_workspace_root) else state.path, style = MaterialTheme.typography.titleSmall)
                    state.handle?.let { Text(stringResource(R.string.remote_workspace_owner, it.location)) }
                    status()
                    state.directory?.let { if (it.usedBytes != null || it.availableBytes != null) Text(stringResource(R.string.remote_workspace_capacity,
                        it.usedBytes?.fileSizeToString() ?: "—", it.availableBytes?.fileSizeToString() ?: "—")) }
                    Diagnostic(listOfNotNull(diagnostic, state.handle?.description).joinToString("\n"))
                }
            }
        })
}

/** Fixed metadata widths keep both the header and selected rows aligned. Large text uses stacked rows. */
@Composable
private fun RemoteFileColumns(file: BrowserFile?) {
    val style = if (file == null) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall
    Text(if (file == null) stringResource(R.string.image_viewer_info_modified) else file.modifiedAt?.let { formatFileTime(it) } ?: "—",
        Modifier.width(152.dp), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(if (file == null) stringResource(R.string.image_viewer_info_size) else file.size?.takeUnless { file.directory }?.fileSizeToString() ?: "—",
        Modifier.width(80.dp).padding(end = 12.dp), textAlign = TextAlign.End, style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun RemotePathBar(path: String, enabled: Boolean = true, modifier: Modifier = Modifier, onPath: (String) -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(path, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
        TextButton({ onPath("") }, enabled = enabled) { Text(stringResource(R.string.remote_workspace_root)) }
        var current = ""
        path.split('/').filter(String::isNotEmpty).forEach { segment ->
            current = if (current.isEmpty()) segment else "$current/$segment"
            val target = current
            Text("›"); TextButton({ onPath(target) }, enabled = enabled) { Text(segment, Modifier.widthIn(max = 180.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun Diagnostic(value: String, label: String = stringResource(R.string.remote_workspace_details)) {
    net.weero.measix.pilot.ui.components.ui.DiagnosticDisclosure(value, label)
}

@Composable
private fun ConfirmDialog(title: String, body: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = if (body.isBlank()) null else ({
        SelectionContainer { Text(body, Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) }
    }),
        confirmButton = { TextButton(confirm) { Text(stringResource(R.string.common_confirm)) } },
        dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.common_cancel)) } })
}

@Composable
private fun NameDialog(prompt: NamePrompt, dismiss: () -> Unit, report: (Throwable) -> Unit) {
    var value by remember(prompt) { mutableStateOf(TextFieldValue(prompt.initial, TextRange(0, prompt.initial.length))) }
    val focus = remember { FocusRequester() }
    var error by remember(prompt) { mutableStateOf<String?>(null) }
    var hint by remember(prompt) { mutableStateOf<String?>(null) }
    val invalidName = stringResource(R.string.remote_workspace_invalid_name)
    val newName = stringResource(R.string.remote_workspace_save_as_new_name)
    val enabled = value.text.isNotBlank() && (prompt.title != R.string.remote_workspace_rename || value.text != prompt.initial)
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        fun submit() {
            try { prompt.handle.child("", value.text); prompt.submit(value.text); dismiss() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                error = failure.userVisibleDiagnostic()
                hint = if (failure is IllegalArgumentException) when (failure.message) {
                    "invalid_workspace_name", "invalid_workspace_path", "webdav_name_invalid", "webdav_path_invalid" -> invalidName
                    "workspace_save_as_requires_new_name" -> newName
                    else -> null
                } else null
                keyboard?.hide(); focusManager.clearFocus()
                if (hint == null) report(failure)
            }
        }
        val window = LocalWindowInfo.current
        BoxWithConstraints(Modifier.widthIn(max = 560.dp).fillMaxWidth().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            val compact = maxHeight < 280.dp
            LaunchedEffect(prompt, window.isWindowFocused) {
                if (window.isWindowFocused) {
                    withFrameNanos { }
                    focus.requestFocus()
                }
            }
            val errorHeight = (maxHeight - 112.dp).coerceAtLeast(24.dp)
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 0.dp else 12.dp),
                shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                val buttons: @Composable RowScope.() -> Unit = {
                    TextButton(dismiss) { Text(stringResource(R.string.common_cancel)) }
                    TextButton({ submit() }, enabled = enabled) { Text(stringResource(R.string.common_confirm)) }
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!compact) {
                        Text(stringResource(prompt.title), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    // Only the buttons move: the field keeps its composition position and IME connection.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val label = stringResource(R.string.remote_workspace_name)
                        OutlinedTextField(value, { value = it; error = null; hint = null },
                            Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = label },
                            singleLine = true, label = { Text(stringResource(if (compact) prompt.title else R.string.remote_workspace_name),
                                maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focusManager.clearFocus() }))
                        if (compact) buttons()
                    }
                    error?.let { diagnostic -> Column(Modifier.weight(1f, fill = false).heightIn(max = errorHeight).verticalScroll(rememberScrollState())) {
                        hint?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Diagnostic(diagnostic)
                    } }
                    if (!compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = buttons)
                }
            }
        }
    }
}

@Composable
private fun FolderDialog(handle: FileBrowserSession, prompt: FolderPrompt,
    dismiss: () -> Unit, choose: (String, List<BrowserFile>) -> Unit,
) {
    var path by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var directory by remember { mutableStateOf<BrowserDirectory?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path, revision) {
        directory = null; error = null
        try { directory = handle.list(path) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.userVisibleDiagnostic() }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(prompt.title)) }, text = {
        Column {
            Text(prompt.files.joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            RemotePathBar(path) { path = it }
            error?.let { net.weero.measix.pilot.ui.components.ui.ErrorNotice(it, onRetry = { revision++ }) }
            if (directory == null && error == null) CircularProgressIndicator()
            val folders = directory?.files.orEmpty().filter { it.directory && prompt.files.none { source -> source.directory && source.path == it.path } }.sortedBy { it.name.lowercase() }
            if (directory != null && folders.isEmpty()) Text(stringResource(R.string.remote_workspace_no_subfolders), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 320.dp)) { items(folders) { file ->
                ListItem(headlineContent = { Text(file.name) }, leadingContent = { Icon(HugeIcons.Folder01, null) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable { path = file.path })
            } }
        }
    }, confirmButton = { TextButton({
        try {
            prompt.files.forEach { handle.child(path, it.name) }
            choose(path, requireNotNull(directory).files)
        } catch (failure: IllegalArgumentException) { error = failure.userVisibleDiagnostic() }
    }, enabled = directory != null && prompt.files.none {
        it.path.substringBeforeLast('/', "") == path || it.path == path || (it.directory && path.startsWith(it.path + "/"))
    }) { Text(stringResource(R.string.remote_workspace_select_folder)) } }, dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.common_cancel)) } })
}

@Composable
private fun FileMetadata(file: BrowserFile) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(file.path, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(if (file.directory) R.string.remote_workspace_kind_folder else R.string.remote_workspace_kind_file))
            file.size?.let { Text(stringResource(R.string.remote_workspace_file_size, it.fileSizeToString())) }
            file.modifiedAt?.let { Text(stringResource(R.string.remote_workspace_modified, formatFileTime(it, full = true))) }
        }
    }
    file.etag?.let { Diagnostic(it, "ETag") }
}

/** A fresh read is shown before the user releases the unknown-write guard. It never repeats a write. */
@Composable
private fun VerifyResultDialog(handle: FileBrowserSession, path: String,
    dismiss: () -> Unit, acknowledged: () -> Unit,
) {
    var file by remember(handle, path) { mutableStateOf<BrowserFile?>(null) }
    var complete by remember(handle, path) { mutableStateOf(false) }
    var loading by remember(handle, path) { mutableStateOf(true) }
    var error by remember(handle, path) { mutableStateOf<String?>(null) }
    var text by remember(handle, path) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(handle, path) {
        try {
            file = handle.verifyUnknown(path)
            val current = file
            if (current != null && !current.directory && fileTextFormat(current.name) != null) {
                text = try { handle.readText(current).content.text }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.userVisibleDiagnostic(); null }
            }
            complete = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.userVisibleDiagnostic() }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.remote_workspace_read_verify)) }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(path)
            if (loading) CircularProgressIndicator()
            else if (complete) {
                file?.let { FileMetadata(it) } ?: Text(stringResource(R.string.remote_workspace_target_absent))
                text?.let {
                    if (it.length > 8192) Text(stringResource(R.string.remote_workspace_verify_excerpt))
                    SelectionContainer { Text(it.take(8192), style = MaterialTheme.typography.bodySmall) }
                }
                Text(stringResource(R.string.remote_workspace_verify_explanation))
            }
            error?.let { net.weero.measix.pilot.ui.components.ui.ErrorNotice(it, onDismiss = { error = null }) }
        }
    }, confirmButton = { TextButton({
        loading = true
        scope.launch {
            try { handle.acknowledgeVerified(path); acknowledged() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.userVisibleDiagnostic() }
            finally { loading = false }
        }
    }, enabled = complete && !loading) { Text(stringResource(R.string.remote_workspace_verify)) } },
        dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.common_cancel)) } })
}

private fun formatFileTime(value: String, full: Boolean = false): String = try {
    DateTimeFormatter.ofLocalizedDateTime(if (full) FormatStyle.MEDIUM else FormatStyle.SHORT)
        .withLocale(Locale.getDefault()).format(Instant.parse(value).atZone(ZoneId.systemDefault()))
} catch (_: java.time.DateTimeException) { value }

@Composable
private fun TransferStatus(state: FileBrowserState, cancel: () -> Unit) {
    val total = state.total
    if (total != null && total > 0 && state.progress < total) {
        LinearProgressIndicator(progress = { (state.progress.toDouble() / total).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
    } else LinearProgressIndicator(Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            state.activeIndex?.let { index ->
                state.results.getOrNull(index)?.let { current ->
                    Text(stringResource(R.string.remote_workspace_active_item, index + 1, state.batchSize, current.path.substringAfterLast('/')),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (total != null || state.progress > 0) Text(
                if (total != null && state.progress >= total) stringResource(R.string.remote_workspace_waiting)
                else "${state.progress.fileSizeToString()} / ${total?.fileSizeToString() ?: "—"}", style = MaterialTheme.typography.labelSmall)
        }
        TextButton(cancel) { Text(stringResource(R.string.common_cancel)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteFilePreview(file: BrowserFile, handle: FileBrowserSession, vm: FileBrowserVM,
    onClose: () -> Unit, onDownload: () -> Unit, onShare: (Boolean) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val editorSession = state.editorSession?.takeIf { it.handle === handle && it.path == file.path } ?: return
    val submission = state.save?.takeIf { it.handle === handle && it.document.file.path == file.path }
    val saving = submission != null && submission.result == null
    val result = submission?.result
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val document = editorSession.document
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = androidx.compose.ui.platform.LocalView.current
    var pdf by remember(file, handle) { mutableStateOf<File?>(null) }
    var error by remember(file, handle) { mutableStateOf<String?>(null) }
    var loading by remember(file, handle) { mutableStateOf(document == null) }
    val editing = editorSession.editing
    var discard by remember { mutableStateOf(false) }
    var reread by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var saveAs by remember { mutableStateOf(false) }
    var verification by remember { mutableStateOf<String?>(null) }
    var actions by remember { mutableStateOf(false) }
    val editor = editorSession.editor
    val dirty by remember(editorSession) { derivedStateOf {
        editor.revision
        editorSession.document?.let { editor.snapshot() != it.content.text } == true
    } }
    val type = fileType(file.name)
    val previewState = editorSession.previewState
    var sourceView by previewState::sourceView
    var media by remember(file, handle) { mutableStateOf<MediaPreviewSource?>(null) }
    val image = type == FileType.IMAGE
    val markdown = type == FileType.MARKDOWN
    fun finish() {
        view.findFocus()?.clearFocus()
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(view.windowToken, 0)
        onClose()
    }
    fun close() { if (saving) return else if (dirty) discard = true else finish() }
    BackHandler { close() }
    LaunchedEffect(file, handle, reload) {
        if (reload == 0 && editorSession.hasFreshDocument()) { loading = false; return@LaunchedEffect }
        loading = true; error = null
        try {
            if (reload > 0) handle.refresh()
            when {
                image -> Unit
                type == FileType.PDF -> {
                    val copy = handle.previewCopy(file)
                    try {
                        pdf = copy; loading = false
                        snapshotFlow { pdf }.first { it !== copy }
                    } finally {
                        withContext(kotlinx.coroutines.NonCancellable) { handle.releaseCopy(copy) }
                    }
                }
                type == FileType.VIDEO || type == FileType.AUDIO -> {
                    val source = handle.mediaSource(file)
                    try { media = source; loading = false; kotlinx.coroutines.awaitCancellation() }
                    finally { source.close() }
                }
                fileTextFormat(file.name) != null -> {
                    val fresh = if (reload > 0) handle.readText(file) else handle.previewText(file)
                    editorSession.accept(fresh)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.userVisibleDiagnostic() }
        finally { loading = false }
    }
    LaunchedEffect(result) {
        if (result?.outcome == BrowserOutcome.SUCCEEDED) finish()
    }
    val renderedFile = remember(handle, file) {
        if (isRenderedFile(file.name)) handle.renderedSource(file.path, vm.source.changes) else null
    }
    fun save(destination: String) {
        val original = document ?: return
        if (saving || state.running || loading) return
        vm.save(handle, original, editor.snapshot(), destination)
    }
    media?.let {
        MediaPreview(it, onClose, onExternal = { onShare(true) }, onDownload = onDownload,
            positionState = previewState.mediaPosition,
            speechPlayback = org.koin.compose.koinInject<net.weero.measix.pilot.service.SpeechApplicationService>().playback,
            actionsEnabled = !state.running, status = {
                if (state.running || state.error != null) Surface {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        if (state.running) TransferStatus(state, vm::cancel)
                        state.error?.let { Diagnostic(it) }
                    }
                }
            })
        return
    }
    if (image) {
        val source = remember(file, handle) { handle.imageSource(file) }
        ImagePreviewDialog(listOf(source), onDismissRequest = onClose,
            showSaveAction = true,
            onInfoRetry = { handle.refresh() }, extraActions = listOf(
            net.weero.measix.pilot.ui.components.ui.ImagePreviewAction(HugeIcons.SquareArrowUpRight,
                stringResource(R.string.remote_workspace_open_external)) { _, _ -> if (!state.running) onShare(true) },
        ), overlay = {
            if (state.running) Surface {
                Column(Modifier.padding(8.dp)) {
                    LinearProgressIndicator()
                    TextButton(vm::cancel) { Text(stringResource(R.string.common_cancel)) }
                }
            }
        })
        return
    }
    Scaffold(topBar = { TopAppBar(title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
        navigationIcon = { IconButton({ close() }, enabled = !saving) { Icon(HugeIcons.ArrowLeft01, stringResource(R.string.back)) } },
        actions = {
            if (document != null && !editing && isRenderedFile(file.name)) FilePreviewToggleButton(sourceView) { sourceView = !sourceView }
            if (document != null && !editing) Tooltip(tooltip = { Text(stringResource(R.string.edit)) }) {
                IconButton({ editorSession.editing = true }, enabled = handle.writable && !state.running && !loading) {
                    Icon(HugeIcons.FileEdit, stringResource(R.string.edit))
                }
            }
            if (editing) Tooltip(tooltip = { Text(stringResource(R.string.remote_workspace_save_close)) }) {
                IconButton({ save(file.path) }, enabled = handle.writable && !state.running && !loading && document?.file?.versioned == true && result?.outcome != BrowserOutcome.UNKNOWN) {
                    Icon(HugeIcons.FloppyDisk, stringResource(R.string.remote_workspace_save_close))
                }
            }
            Box {
            IconButton({ actions = true }, enabled = !state.running) { Icon(HugeIcons.MoreVertical, stringResource(R.string.more_options)) }
            DropdownMenu(actions, { actions = false }) {
                if (editing) DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_save_as)) }, enabled = handle.writable && !loading,
                    onClick = { actions = false; saveAs = true })
                if (editing) DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_reread)) }, enabled = !loading,
                    onClick = { actions = false; if (dirty) reread = true else { vm.clearSave(); reload++ } })
                DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_download)) }, onClick = { actions = false; onDownload() })
                DropdownMenuItem(text = { Text(stringResource(R.string.common_share)) }, onClick = { actions = false; onShare(false) })
                DropdownMenuItem(text = { Text(stringResource(R.string.remote_workspace_open_external)) }, onClick = { actions = false; onShare(true) })
            }
        } }, expandedHeight = 48.dp) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .padding(horizontal = if (document != null && (editing || !markdown)) 0.dp else 12.dp, vertical = 0.dp)
            .imePadding(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.running) TransferStatus(state, vm::cancel)
            val unavailable = !loading && document == null && pdf == null
            if (!unavailable) {
                error?.let { net.weero.measix.pilot.ui.components.ui.ErrorNotice(it, onDismiss = { error = null }) }
                state.error?.let { net.weero.measix.pilot.ui.components.ui.ErrorNotice(it, onDismiss = vm::dismissError) }
            }
            result?.takeIf { it.outcome != BrowserOutcome.SUCCEEDED }?.let {
                Text(stringResource(when (it.outcome) {
                    BrowserOutcome.UNKNOWN -> R.string.remote_workspace_unknown
                    BrowserOutcome.CANCELLED -> R.string.remote_workspace_cancelled
                    else -> R.string.remote_workspace_operation_failed
                }))
                it.diagnostic?.let { detail -> Diagnostic(detail) }
                if (it.outcome == BrowserOutcome.UNKNOWN) TextButton({ verification = it.path }, enabled = !state.running) { Text(stringResource(R.string.remote_workspace_read_verify)) }
            }
            if (editing && document?.file?.versioned == false) Text(stringResource(R.string.remote_workspace_no_version))
            when {
                document != null && isRenderedFile(file.name) && !editing && !sourceView -> FileDocumentPreview(
                    requireNotNull(renderedFile), document.content.text, file.name, Modifier.weight(1f).fillMaxWidth(),
                    onFileLink = { path, before -> vm.openLinkedFile(handle, path, before) }, state = previewState)
                document != null -> FileTextEditor(
                    state = editor,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    enabled = !saving && !loading,
                    readOnly = !editing,
                    showNavigation = true,
                    fillViewport = true,
                    fileName = file.name,
                )
                pdf != null -> PdfPreview(requireNotNull(pdf), Modifier.weight(1f), pageState = previewState.pdfPage) {
                    error = it.userVisibleDiagnostic()
                    pdf = null
                }
                !loading -> Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    error?.let { net.weero.measix.pilot.ui.components.ui.ErrorNotice(it, onRetry = { reload++ }) }
                    state.error?.let { net.weero.measix.pilot.ui.components.ui.ErrorNotice(it, onDismiss = vm::dismissError) }
                    if (error == null) Text(stringResource(R.string.remote_workspace_preview_unsupported))
                    FlowRow {
                        if (error != null) TextButton({ reload++ }, enabled = !state.running) { Text(stringResource(R.string.enterprise_budget_refresh)) }
                        TextButton(onDownload, enabled = !state.running) { Text(stringResource(R.string.remote_workspace_download)) }
                        TextButton({ onShare(true) }, enabled = !state.running) { Text(stringResource(R.string.remote_workspace_open_external)) }
                    }
                    FileMetadata(file)
                }
            }
        }
    }
    if (discard) ConfirmDialog(stringResource(R.string.remote_workspace_unsaved), "", { discard = false }) { finish() }
    if (reread) ConfirmDialog(stringResource(R.string.remote_workspace_reread), stringResource(R.string.remote_workspace_reread_confirm), { reread = false }) {
        reread = false; vm.clearSave(); reload++
    }
    verification?.let { path -> VerifyResultDialog(handle, path, { verification = null }) {
        verification = null; vm.clearSave(); vm.dismissVerifiedResult(handle, path)
    } }
    if (saveAs) NameDialog(NamePrompt(R.string.remote_workspace_save_as, handle, "copy-${file.name}") { value ->
        val target = handle.child(file.path.substringBeforeLast('/', ""), value)
        require(target != file.path) { "workspace_save_as_requires_new_name" }
        save(target)
    }, { saveAs = false }, vm::report)
}
