package net.weero.measix.pilot.ui.components.files

import android.content.Context
import net.weero.measix.pilot.R
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.files.*
import net.weero.measix.pilot.ui.components.files.FileEditorState
import net.weero.measix.pilot.utils.userVisibleDiagnostic

internal data class FileBrowserSaveSubmission(
    val handle: FileBrowserSession, val document: BrowserTextDocument, val text: String,
    val destination: String, val result: BrowserOperationResult? = null,
)
internal data class FileBrowserOverwritePrompt(val handle: FileBrowserSession, val target: BrowserFile)
internal data class FileBrowserDownloadPicker(val handle: FileBrowserSession, val files: List<BrowserFile>, val tree: Boolean)
/** One transient native buffer survives view recreation only within its original management session. */
internal class FileBrowserEditorSession(val handle: FileBrowserSession, val path: String) {
    val editor = FileEditorState()
    val previewState = FilePreviewState()
    var document by mutableStateOf<BrowserTextDocument?>(null)
    var editing by mutableStateOf(false)
    private var documentReadAt = 0L
    fun hasFreshDocument() = document != null && (editing || android.os.SystemClock.elapsedRealtime() - documentReadAt < 30_000)
    fun releaseCachedDocument() { document = null; editor.replaceText("") }

    fun accept(document: BrowserTextDocument) {
        require(document.file.path == path)
        if (!android.text.TextUtils.equals(editor.editable, document.content.text)) editor.replaceText(document.content.text)
        this.document = document
        documentReadAt = android.os.SystemClock.elapsedRealtime()
    }
}
internal data class FileBrowserState(
    val handle: FileBrowserSession? = null,
    val path: String = "",
    val directory: BrowserDirectory? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val revoked: Boolean = false,
    val running: Boolean = false,
    val progress: Long = 0,
    val total: Long? = null,
    val activeIndex: Int? = null,
    val batchSize: Int = 0,
    val results: List<BrowserOperationResult> = emptyList(),
    val resultLabel: Int? = null,
    val resultDirectory: String? = null,
    val preview: BrowserFile? = null,
    val editorSession: FileBrowserEditorSession? = null,
    val save: FileBrowserSaveSubmission? = null,
    val overwrite: FileBrowserOverwritePrompt? = null,
)

internal open class FileBrowserVM(
    val source: FileBrowserSource,
    private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(FileBrowserState(revoked = source.selection == null))
    val state = _state.asStateFlow()
    private var readJob: Job? = null
    private var readRevision = 0L
    private var readPath: String? = null
    private var work: Job? = null
    private var overwriteAnswer: CompletableDeferred<Boolean>? = null
    private var leaving = false
    private var exitRequested = false
    private val transition = Mutex()
    // Picker requests survive Activity recreation, but are never serialized or rebound to another handle.
    private var pendingDownload: FileBrowserDownloadPicker? = null
    private var pendingUpload: Pair<FileBrowserSession, String>? = null

    init {
        retry()
        viewModelScope.launch {
            source.changes.collect {
                val handle = _state.value.handle
                val valid = try { handle?.isValid() ?: true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (_state.value.handle === handle) report(error)
                    // A failed authority query is not proof that the original identity was revoked.
                    return@collect
                }
                if (handle != null && !valid) {
                    // Validation may suspend while a server switch opens a different session.
                    if (_state.value.handle === handle) {
                        overwriteAnswer?.cancel()
                        previewHistory.clear()
                        _state.update { it.copy(handle = null, directory = null, revoked = true, error = null,
                            preview = null, editorSession = null, save = null, overwrite = null, results = emptyList(), progress = 0, total = null, activeIndex = null) }
                    }
                    handle.close()
                }
            }
        }
    }

    fun retry() = loadDirectory(force = false, refresh = true)

    fun resume() {
        if (_state.value.revoked) return
        val handle = _state.value.handle ?: return
        loadDirectory(force = false, refresh = false, resumeHandle = handle)
    }

    private fun loadDirectory(force: Boolean, refresh: Boolean = true, resumeHandle: FileBrowserSession? = null) {
        if (source.selection == null || _state.value.running || leaving) return
        val path = _state.value.path
        if (!force && readJob?.isActive == true && readPath == path) return
        readJob?.cancel()
        readPath = path
        val revision = ++readRevision
        readJob = viewModelScope.launch {
            var acquired: FileBrowserSession? = null
            _state.update { it.copy(loading = true, error = null) }
            try {
                if (resumeHandle != null) {
                    source.refresh()
                    if (!resumeHandle.isValid() || _state.value.revoked || _state.value.handle !== resumeHandle) return@launch
                }
                if (refresh) _state.value.handle?.refresh()
                val handle = resumeHandle ?: _state.value.handle ?: source.open().also { acquired = it }
                ensureActive()
                if (revision != readRevision) return@launch
                _state.update { it.copy(handle = handle, revoked = false) }
                acquired = null
                val directory = if (refresh) handle.list(path) else handle.browse(path)
                if (revision == readRevision && _state.value.handle === handle) _state.update { it.copy(directory = directory) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (revision == readRevision) report(error) }
            finally {
                acquired?.closeLater()
                if (revision == readRevision) _state.update { it.copy(loading = false) }
            }
        }
    }

    fun browse(path: String) {
        if (_state.value.running || leaving) return
        _state.value.handle?.path(path) ?: return
        _state.update { it.copy(path = path, directory = null) }
        loadDirectory(force = false, refresh = false)
    }

    private val previewHistory = java.util.ArrayDeque<Pair<BrowserFile, FileBrowserEditorSession>>()

    fun openPreview(file: BrowserFile) {
        if (leaving) return
        val handle = _state.value.handle ?: return
        previewHistory.clear()
        if (!_state.value.running) _state.update {
            it.copy(preview = file, editorSession = FileBrowserEditorSession(handle, file.path), save = null)
        }
    }

    suspend fun openLinkedFile(handle: FileBrowserSession, path: String, beforeNavigation: () -> Unit = {}) {
        val before = _state.value
        check(!before.running && before.handle === handle && before.editorSession?.editing != true) { "workspace_navigation_unavailable" }
        val file = requireNotNull(handle.stat(path)) { "workspace_file_missing: $path" }
        check(handle.isValid() && _state.value.handle === handle && _state.value.preview == before.preview) { "rendered_file_revoked" }
        check(!leaving && !_state.value.running && _state.value.editorSession === before.editorSession &&
            before.editorSession?.editing != true) { "workspace_navigation_unavailable" }
        beforeNavigation()
        if (file.directory) {
            previewHistory.clear()
            _state.update { it.copy(preview = null, editorSession = null, save = null) }
            browse(file.path)
        } else {
            if (before.preview != null && before.editorSession != null) previewHistory.addLast(before.preview to before.editorSession)
            while (previewHistory.size > 16) previewHistory.removeFirst()
            // History retains lightweight positions after dropping old text, never native viewers.
            var textBytes = previewHistory.sumOf { (it.second.document?.content?.text?.length ?: 0).toLong() * 4 }
            for ((_, session) in previewHistory) {
                if (textBytes <= 8L * 1024 * 1024) break
                textBytes -= (session.document?.content?.text?.length ?: 0).toLong() * 4
                session.releaseCachedDocument()
            }
            _state.update { it.copy(preview = file, editorSession = FileBrowserEditorSession(handle, file.path), save = null) }
        }
    }

    fun closePreview() {
        if (_state.value.save?.result == null && _state.value.save != null) return
        val previous = previewHistory.pollLast()
        _state.update { it.copy(preview = previous?.first, editorSession = previous?.second, save = null) }
    }

    fun clearSave() { if (!_state.value.running) _state.update { it.copy(save = null) } }
    fun cancel() { readJob?.cancel(); work?.cancel(); overwriteAnswer?.cancel() }
    fun clearResults() { if (!_state.value.running) _state.update { state -> state.copy(results = state.results.filter { it.outcome == BrowserOutcome.UNKNOWN }) } }
    fun dismissVerifiedResult(handle: FileBrowserSession, path: String) {
        _state.update { if (it.running || it.handle !== handle) it else it.copy(results = it.results.filterNot { result -> result.path == path }) }
    }

    fun dismissError() { _state.update { it.copy(error = null) } }

    fun report(error: Throwable) {
        android.util.Log.e("RemoteWorkspacePage", "Remote file operation failed", error)
        _state.update { it.copy(error = error.userVisibleDiagnostic()) }
    }

    /** All write closures capture their original handle before a dialog or picker opens. */
    fun execute(paths: List<String>, original: FileBrowserSession? = _state.value.handle,
        resultLabel: Int? = null, resultDirectory: String? = null,
        action: suspend (Int, FileBrowserSession) -> BrowserOperationResult,
    ) {
        if (leaving || work?.isActive == true || original == null || _state.value.handle !== original) return
        val unverified = _state.value.results.filter { it.outcome == BrowserOutcome.UNKNOWN }.distinctBy { it.path }
        val results = paths.map { BrowserOperationResult(it, BrowserOutcome.NOT_STARTED) }
        _state.update { it.copy(running = true, results = retainUnverified(results, unverified), error = null,
            resultLabel = resultLabel, resultDirectory = resultDirectory,
            progress = 0, total = null, activeIndex = null, batchSize = paths.size) }
        work = viewModelScope.launch {
            try {
                paths.indices.forEach { index ->
                    ensureActive()
                    _state.update { it.copy(progress = 0, total = null, activeIndex = index) }
                    val result = try { action(index, original) }
                    catch (cancelled: CancellationException) {
                        _state.update { state -> if (state.handle !== original) state else state.copy(results = state.results.toMutableList().also {
                            it[index] = BrowserOperationResult(it[index].path, original.outcomeAfterCancellation(it[index].path),
                                cancelled.takeIf { it.suppressed.isNotEmpty() }?.userVisibleDiagnostic())
                        }) }
                        throw cancelled
                    } catch (error: Exception) {
                        android.util.Log.e("RemoteWorkspacePage", "Batch item failed", error)
                        BrowserOperationResult(paths[index], BrowserOutcome.FAILED, error.userVisibleDiagnostic())
                    }
                    _state.update { state -> if (state.handle !== original) state else state.copy(results = state.results.toMutableList().also { it[index] = result }) }
                    if (!original.isValid()) return@launch
                }
            } finally {
                overwriteAnswer?.cancel(); overwriteAnswer = null
                // A completed or cancelled batch may have changed rows; retire them before allowing new actions.
                _state.update { if (it.handle !== original) it.copy(running = false, overwrite = null, activeIndex = null)
                    else it.copy(running = false, overwrite = null, activeIndex = null, directory = null,
                        results = retainUnverified(it.results.take(paths.size), unverified)) }
                if (isActive && _state.value.handle === original) loadDirectory(force = true)
            }
        }
    }

    /** Current batch indexes stay at the front; earlier uncertain results remain reviewable until acknowledged. */
    private fun retainUnverified(batch: List<BrowserOperationResult>, unverified: List<BrowserOperationResult>) =
        batch + unverified.filter { previous -> batch.none { it.path == previous.path && it.outcome == BrowserOutcome.UNKNOWN } }

    fun save(handle: FileBrowserSession, document: BrowserTextDocument, text: String, destination: String) {
        if (leaving || _state.value.running || _state.value.handle !== handle) return
        val editor = _state.value.editorSession?.takeIf { it.handle === handle && it.path == document.file.path } ?: return
        if (editor.document == null) editor.accept(document)
        if (editor.editor.snapshot() != text) editor.editor.replaceText(text)
        editor.editing = true
        val submission = FileBrowserSaveSubmission(handle, document, text, destination)
        _state.update { it.copy(save = submission) }
        execute(listOf(destination), handle, R.string.remote_workspace_save_close, destination.substringBeforeLast('/', "")) { _, _ ->
            val result = try { handle.save(document, text, destination) }
            catch (cancelled: CancellationException) {
                _state.update { if (it.save === submission) it.copy(save = submission.copy(
                    result = BrowserOperationResult(destination, handle.outcomeAfterCancellation(destination)))) else it }
                throw cancelled
            } catch (error: Exception) {
                BrowserOperationResult(destination, BrowserOutcome.FAILED, error.userVisibleDiagnostic())
            }
            _state.update { if (it.save === submission) it.copy(save = submission.copy(result = result)) else it }
            result
        }
    }

    private suspend fun confirmOverwrite(handle: FileBrowserSession, target: BrowserFile): Boolean {
        check(!target.directory && target.versioned) { "workspace_strong_etag_required" }
        check(handle.isValid()) { "workspace_access_revoked" }
        val answer = CompletableDeferred<Boolean>()
        overwriteAnswer = answer
        _state.update { it.copy(overwrite = FileBrowserOverwritePrompt(handle, target)) }
        return try { answer.await().also { check(handle.isValid()) { "workspace_access_revoked" } } }
        finally {
            if (overwriteAnswer === answer) {
                overwriteAnswer = null
                _state.update { it.copy(overwrite = null) }
            }
        }
    }

    fun answerOverwrite(prompt: FileBrowserOverwritePrompt, confirmed: Boolean) {
        if (_state.value.overwrite === prompt && _state.value.handle === prompt.handle) overwriteAnswer?.complete(confirmed)
    }

    private suspend fun mutateOne(handle: FileBrowserSession, action: BrowserFileAction, file: BrowserFile, target: String?): BrowserOperationResult {
        if (action == BrowserFileAction.DELETE) return handle.mutate(action, file, recursiveConfirmed = true)
        requireNotNull(target)
        require(target != file.path && (!file.directory || !target.startsWith(file.path + "/"))) { "workspace_invalid_destination" }
        val existing = handle.stat(target)
        if (existing != null) {
            require(!file.directory && !existing.directory) { "workspace_directory_overwrite_forbidden" }
            if (!confirmOverwrite(handle, existing)) return BrowserOperationResult(target, BrowserOutcome.NOT_STARTED)
        }
        return handle.mutate(action, file, target, existing)
    }

    fun rename(handle: FileBrowserSession, file: BrowserFile, target: String) {
        execute(listOf(target), handle, R.string.remote_workspace_rename, target.substringBeforeLast('/', "")) { _, _ -> mutateOne(handle, BrowserFileAction.MOVE, file, target) }
    }

    fun mutate(action: BrowserFileAction, files: List<BrowserFile>, destination: String? = null,
        original: FileBrowserSession? = _state.value.handle,
    ) {
        val roots = files.filter { child -> files.none { parent -> parent.directory && child.path.startsWith(parent.path + "/") } }
        val paths = try { roots.map { destination?.let { path -> requireNotNull(original).child(path, it.name) } ?: it.path } }
        catch (error: IllegalArgumentException) { report(error); return }
        execute(paths, original, when (action) {
            BrowserFileAction.COPY -> R.string.remote_workspace_copy
            BrowserFileAction.MOVE -> R.string.remote_workspace_move
            BrowserFileAction.DELETE -> R.string.delete
        }, destination) { index, handle ->
            val file = roots[index]
            mutateOne(handle, action, file, destination?.let { handle.child(it, file.name) })
        }
    }

    fun prepareUpload(handle: FileBrowserSession, directory: String) { pendingUpload = handle to directory }
    fun uploadResult(uris: List<Uri>) {
        val request = pendingUpload; pendingUpload = null
        if (uris.isNotEmpty() && request != null) upload(uris, request.first, request.second)
    }
    fun cancelUploadPicker() { pendingUpload = null }

    private fun upload(uris: List<Uri>, original: FileBrowserSession, directory: String) {
        if (_state.value.handle !== original) return
        execute(uris.map(Uri::toString), original, R.string.remote_workspace_upload, directory) { index, handle ->
            val uri = uris[index]
            val metadata = withContext(Dispatchers.IO) {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                    check(it.moveToFirst()) { "workspace_upload_metadata_unavailable" }
                    it.getString(0) to if (it.isNull(1)) null else it.getLong(1)
                } ?: error("workspace_upload_metadata_unavailable")
            }
            val path = handle.child(directory, metadata.first)
            _state.update { state -> if (state.handle !== handle) state else state.copy(results = state.results.toMutableList().also {
                it[index] = BrowserOperationResult(path, BrowserOutcome.NOT_STARTED)
            }) }
            // Resolve every queued destination when it starts, then freeze its confirmed version.
            val existing = handle.stat(path)
            if (existing != null) {
                require(!existing.directory) { "workspace_directory_overwrite_forbidden" }
                if (!confirmOverwrite(handle, existing)) return@execute BrowserOperationResult(path, BrowserOutcome.NOT_STARTED)
            }
            handle.upload(path, existing?.etag, metadata.second,
                { requireNotNull(context.contentResolver.openInputStream(uri)) { "workspace_upload_open_failed" } }, ::progress)
        }
    }

    fun prepareDownload(handle: FileBrowserSession, files: List<BrowserFile>, tree: Boolean) {
        pendingDownload = FileBrowserDownloadPicker(handle, files.toList(), tree)
    }
    fun cancelDownloadPicker() { pendingDownload = null }
    fun downloadResult(destination: Uri?, tree: Boolean) {
        val request = pendingDownload; pendingDownload = null
        if (destination == null) return
        if (request == null || request.tree != tree || _state.value.handle !== request.handle || _state.value.running) {
            if (!tree) cleanupCreatedDocument(destination)
            return
        }
        if (tree) download(request.files, request.handle, destination)
        else downloadSingle(request.files.single(), request.handle, destination)
    }

    private fun destination(uri: Uri) = object : BrowserExportDestination {
        override val description = uri.toString()
        override fun open() = requireNotNull(context.contentResolver.openOutputStream(uri, "w"))
        override fun delete() = DocumentsContract.deleteDocument(context.contentResolver, uri)
    }

    private fun cleanupCreatedDocument(uri: Uri) = source.discardExport(destination(uri), ::report)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun downloadSingle(file: BrowserFile, original: FileBrowserSession, uri: Uri) {
        val transfer = original.acceptExport(file, destination(uri), ::progress)
        val unverified = _state.value.results.filter { it.outcome == BrowserOutcome.UNKNOWN }.distinctBy { it.path }
        _state.update { it.copy(running = true, results = retainUnverified(listOf(BrowserOperationResult(file.path, BrowserOutcome.NOT_STARTED)), unverified), error = null,
            progress = 0, total = null, activeIndex = 0, batchSize = 1,
            resultLabel = R.string.remote_workspace_download, resultDirectory = null) }
        work = viewModelScope.launch(start = CoroutineStart.ATOMIC) {
            var result = BrowserOperationResult(file.path, BrowserOutcome.CANCELLED)
            try {
                ensureActive(); transfer.await()
                result = BrowserOperationResult(file.path, BrowserOutcome.SUCCEEDED)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                report(error); result = BrowserOperationResult(file.path, BrowserOutcome.FAILED, error.userVisibleDiagnostic())
            } finally {
                withContext(NonCancellable) { if (!transfer.isCompleted) transfer.cancelAndJoin() }
                val cleanup = transfer.getCompletionExceptionOrNull()?.takeIf { it.suppressed.isNotEmpty() }
                if (cleanup != null) { report(cleanup); result = result.copy(diagnostic = cleanup.userVisibleDiagnostic()) }
                _state.update { if (it.handle !== original) it.copy(running = false, activeIndex = null) else it.copy(running = false, activeIndex = null,
                    results = retainUnverified(listOf(result), unverified)) }
            }
        }
    }

    private fun download(files: List<BrowserFile>, original: FileBrowserSession, tree: Uri) {
        execute(files.map { it.path }, original, R.string.remote_workspace_download) { index, handle ->
            val file = files[index]
            handle.exportCreated(file, {
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                destination(requireNotNull(DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", file.name)))
            }, ::progress)
            BrowserOperationResult(file.path, BrowserOutcome.SUCCEEDED)
        }
    }

    private fun progress(bytes: Long, total: Long?) { _state.update { it.copy(progress = bytes, total = total) } }

    suspend fun leave() {
        exitRequested = true
        leaving = true
        transition.withLock { closeTarget() }
    }

    private suspend fun closeTarget() {
        leaving = true
        previewHistory.clear()
        ++readRevision
        pendingUpload = null
        pendingDownload = null
        overwriteAnswer?.cancel()
        readJob?.cancelAndJoin(); work?.cancelAndJoin()
        _state.value.handle?.let { it.close() }
        _state.update { it.copy(handle = null, directory = null, preview = null, editorSession = null, save = null, overwrite = null) }
    }

    protected suspend fun resetTarget(change: () -> Unit) = transition.withLock {
        if (exitRequested) return@withLock
        closeTarget()
        if (exitRequested) return@withLock
        change()
        _state.value = FileBrowserState(revoked = source.selection == null)
        readPath = null
        leaving = false
        retry()
    }

    override fun onCleared() {
        previewHistory.clear()
        _state.value.handle?.closeLater()
        _state.update { it.copy(preview = null, editorSession = null, save = null) }
    }
}
