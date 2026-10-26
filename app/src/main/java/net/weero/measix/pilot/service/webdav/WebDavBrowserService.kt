package net.weero.measix.pilot.service.webdav

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.enterprise.WorkspaceText
import net.weero.measix.pilot.data.webdav.*
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import net.weero.measix.pilot.service.ImageOrigin
import net.weero.measix.pilot.service.ImageSource
import net.weero.measix.pilot.service.files.*
import net.weero.measix.pilot.service.workspace.validateWorkspaceImage
import net.weero.measix.pilot.service.workspace.workspaceImageGallerySaveSupported
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import kotlin.uuid.Uuid

/** Owns browser requests and unpublished local copies. The DAV service retains remote-access authority. */
internal class WebDavBrowserService(
    private val dav: WebDavApplicationService,
    private val queries: ConfigurationQueryService,
    private val recovery: ApplicationRecoveryGate,
    private val scope: CoroutineScope,
    private val temporaryRoot: File,
) {
    private val cleanupMutex = Mutex()
    private var cleaned = false
    private suspend fun cleanupBeforeOpen() = cleanupMutex.withLock {
        if (cleaned) return@withLock
        recovery.awaitReady()
        withContext(Dispatchers.IO) {
            if (temporaryRoot.exists()) {
                val files = temporaryRoot.listFiles() ?: throw IOException("webdav_temporary_directory_unreadable")
                files.filter { it.name.startsWith("preview-") ||
                    (it.name.startsWith("share-") && System.currentTimeMillis() - it.lastModified() >= 86_400_000) }
                    .forEach { if (!it.delete()) throw IOException("webdav_temporary_cleanup_failed: $it") }
            }
        }
        cleaned = true
    }
    fun source(selection: RealmSelection?) = Source(this, selection)

    // Compose consumes this concrete type in WebDavFileVM. A regular nested class emits the static
    // stability field required by that consumer; an inner class does not.
    class Source internal constructor(
        private val browser: WebDavBrowserService,
        override val selection: RealmSelection?,
    ) : FileBrowserSource {
        private val _targetId = MutableStateFlow<String?>(null)
        val targetId = _targetId.asStateFlow()
        override val changes: Flow<Unit> = merge(browser.queries.observeWebDavConfiguration().map { },
            browser.queries.observeSelectedRealmSelection().map { })
        fun choose(id: String) { _targetId.value = id }
        override suspend fun refresh() { browser.dav.refresh(requireNotNull(selection)) }
        override suspend fun open(): FileBrowserSession {
            browser.cleanupBeforeOpen()
            val original = requireNotNull(selection)
            val handle = browser.dav.open(original, _targetId.value)
            _targetId.value = handle.target.id
            return browser.Session(handle, changes)
        }
        override fun discardExport(destination: BrowserExportDestination, onFailure: (Throwable) -> Unit) {
            browser.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable + Dispatchers.IO) {
                    try { browser.deleteExport(destination) }
                    catch (failure: Exception) { onFailure(failure) }
                }
            }
        }
    }

    private inner class Session(val handle: WebDavHandle, val changes: Flow<*>) : FileBrowserSession {
        private val cache = FileBrowserReadCache()
        override suspend fun browse(path: String) = cache.directory(path, ::checkAccess) { list(path) }
        override suspend fun previewText(file: BrowserFile) = cache.text(file, ::checkAccess) { readText(file) }
        private val paths = WebDavPaths(handle.target.root)
        private val monitor = Any()
        private val closed = AtomicBoolean()
        private val jobs = mutableSetOf<Job>()
        private val copies = mutableSetOf<File>()
        override val description get() = handle.target.name
        override val location get() = handle.target.root
        override val writable get() = handle.writable
        override fun path(value: String) = paths.logical(value)
        override fun child(parent: String, name: String) = paths.child(parent, name)
        override suspend fun refresh() {
            cache.invalidate()
            check(!closed.get()) { "webdav_access_closed" }
            // Explicit retry must be able to replace an earlier uncertain status diagnosis.
            dav.validate(handle)
            checkAccess()
        }
        override suspend fun isValid() = !closed.get() && dav.isValidLocal(handle)

        private suspend fun checkAccess() {
            check(!closed.get()) { "webdav_access_closed" }
            dav.validateLocal(handle)
        }

        private suspend fun <T> operation(onFailure: suspend (Throwable) -> Unit = {}, verifyAfter: Boolean = true,
            action: suspend () -> T): T {
            val job = currentCoroutineContext().job
            synchronized(monitor) { jobs += job }
            try { currentCoroutineContext().ensureActive(); checkAccess(); return action().also { if (verifyAfter) checkAccess() } }
            catch (failure: Throwable) { onFailure(failure); throw failure }
            finally { synchronized(monitor) { jobs -= job } }
        }

        override suspend fun close() {
            cache.invalidate()
            val pending = synchronized(monitor) { closed.set(true); jobs.toList().also { it.forEach(Job::cancel) } }
            // Once closed, all owned jobs and copies must settle even if the leaving UI is cancelled.
            withContext(NonCancellable) {
                pending.joinAll()
                dav.close(handle)
                withContext(Dispatchers.IO) { synchronized(monitor) { copies.toList() }.forEach { releaseCopy(it) } }
            }
        }
        override fun closeLater() {
            cache.invalidate()
            synchronized(monitor) { closed.set(true); jobs.forEach(Job::cancel) }
            scope.launch { close() }
        }

        override suspend fun list(path: String) = operation {
            dav.list(handle, path).let { BrowserDirectory(path, it.files.map(::browserFile), it.usedBytes, it.availableBytes) }
        }
        override suspend fun stat(path: String) = operation { dav.stat(handle, path)?.let(::browserFile) }
        override suspend fun readText(file: BrowserFile) = operation {
            val buffer = ByteArrayOutputStream()
            val length = dav.download(handle, file.path, buffer, file.etag.takeIf { file.versioned }, maxBytes = 2L * 1024 * 1024)
            BrowserTextDocument(file.copy(size = length), WorkspaceText.decode(buffer.toByteArray()))
        }
        override suspend fun readPreviewResource(path: String, maxBytes: Long) = operation {
            paths.logical(path)
            val buffer = ByteArrayOutputStream()
            dav.download(handle, path, buffer, maxBytes = maxBytes)
            buffer.toByteArray()
        }
        override suspend fun createDirectory(path: String) = writeResult(path) { dav.mkdir(handle, path) }
        override suspend fun upload(path: String, etag: String?, length: Long?, input: () -> InputStream,
            progress: (Long, Long?) -> Unit) = writeResult(path) { dav.upload(handle, path, length, input, etag, progress) }
        override suspend fun save(document: BrowserTextDocument, text: String, destination: String): BrowserOperationResult {
            val bytes = document.content.encode(text)
            val etag = if (destination == document.file.path) requireNotNull(document.file.etag).also(WebDavClient::requireStrongEtag) else null
            return upload(destination, etag, bytes.size.toLong(), { bytes.inputStream() })
        }
        override suspend fun mutate(action: BrowserFileAction, file: BrowserFile, destination: String?,
            overwrite: BrowserFile?, recursiveConfirmed: Boolean): BrowserOperationResult {
            require(!file.directory || action != BrowserFileAction.DELETE || recursiveConfirmed) { "webdav_recursive_confirmation_required" }
            if (!file.directory) WebDavClient.requireStrongEtag(requireNotNull(file.etag))
            if (overwrite != null) {
                require(!file.directory && !overwrite.directory && overwrite.path == destination) { "webdav_directory_overwrite_forbidden" }
                WebDavClient.requireStrongEtag(requireNotNull(overwrite.etag))
            }
            return writeResult(destination ?: file.path) {
                dav.mutate(handle, file.path, action.name, destination, file.etag.takeIf { file.versioned }, overwrite?.etag, file.directory)
            }
        }
        private suspend fun writeResult(path: String, action: suspend () -> Unit): BrowserOperationResult = cache.writing { try {
            // The application service owns write acknowledgement and UNKNOWN classification.
            // A later browser access change must not reinterpret an already settled write.
            operation(verifyAfter = false) { action() }
            BrowserOperationResult(path, BrowserOutcome.SUCCEEDED)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            android.util.Log.e("WebDavBrowser", "DAV write failed", failure)
            BrowserOperationResult(path, if (path in dav.unknownPaths(handle)) BrowserOutcome.UNKNOWN else BrowserOutcome.FAILED,
                failure.userVisibleDiagnostic())
        }
        }
        override fun outcomeAfterCancellation(path: String) = if (path in dav.unknownPaths(handle)) BrowserOutcome.UNKNOWN else BrowserOutcome.CANCELLED
        override suspend fun verifyUnknown(path: String) = operation { dav.verifyUnknown(handle, path)?.let(::browserFile) }
        override suspend fun acknowledgeVerified(path: String) = operation { dav.acknowledgeUnknown(handle, setOf(path)) }

        override fun imageSource(file: BrowserFile, maxBytes: Long) = ImageSource(
            cacheIdentity = "webdav:${handle.target.cacheIdentity}:${file.path}:${file.etag ?: Uuid.random()}",
            origin = ImageOrigin.NETWORK, displayName = file.name,
            gallerySaveSupported = workspaceImageGallerySaveSupported(file.name),
            mimeHint = if (file.name.endsWith(".svg", true)) "image/svg+xml" else null,
            verifyAccess = { checkAccess() },
            readPayload = { operation {
                val output = ByteArrayOutputStream()
                dav.download(handle, file.path, output, file.etag.takeIf { file.versioned }, maxBytes = maxBytes)
                output.toByteArray().also { validateWorkspaceImage(it, file.name) }
            } },
        )
        override suspend fun mediaSource(file: BrowserFile) = operation {
            val metadata = dav.mediaMetadata(handle, file.path)
            MediaPreviewSource(file.name, metadata.length, changes, verifyAccess = { checkAccess() },
                readRange = { position, length -> operation { dav.mediaRange(handle, file.path, metadata, position, length) } },
                maxBytes = Long.MAX_VALUE)
        }
        override suspend fun previewCopy(file: BrowserFile, maxBytes: Long): File = operation {
            ownedCopy(file, maxBytes) { it }
        }
        override suspend fun releaseCopy(copy: File) = withContext(Dispatchers.IO) {
            require(copy.parentFile?.canonicalFile == temporaryRoot.canonicalFile &&
                (copy.name.startsWith("preview-") || copy.name.startsWith("share-")))
            if (copy.exists() && !copy.delete()) throw IOException("webdav_temporary_cleanup_failed: $copy")
            synchronized(monitor) { copies -= copy }
            Unit
        }
        override suspend fun share(file: BrowserFile, deliver: (File) -> Unit) = operation {
            ownedCopy(file, Long.MAX_VALUE, sharing = true) { copy ->
                // The system handoff is committed under the original selected-domain identity.
                checkAccess()
                dav.publishDownload(handle) {
                    check(!closed.get() && !handle.closed.get()) { "webdav_access_closed" }
                    deliver(copy)
                    synchronized(monitor) { copies -= copy }
                }
            }
        }
        private suspend fun <T> ownedCopy(file: BrowserFile, maxBytes: Long, sharing: Boolean = false, accept: suspend (File) -> T): T {
            var owned: File? = null
            try {
                withContext(Dispatchers.IO) {
                    check(temporaryRoot.isDirectory || temporaryRoot.mkdirs()) { "webdav_temporary_directory_failed" }
                    val extension = file.name.substringAfterLast('.', "").takeIf { it.matches(Regex("[a-zA-Z0-9]{1,16}")) }
                    owned = File(temporaryRoot, "${if (sharing) "share" else "preview"}-${Uuid.random()}${extension?.let { ".$it" }.orEmpty()}")
                    val copy = requireNotNull(owned)
                    synchronized(monitor) { copies += copy }
                    copy.outputStream().use { dav.download(handle, file.path, it, file.etag.takeIf { file.versioned }, maxBytes = maxBytes) }
                }
                checkAccess()
                return accept(requireNotNull(owned))
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    owned?.takeIf { synchronized(monitor) { it in copies } }?.let {
                        try { releaseCopy(it) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                    }
                }
                throw failure
            }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        override fun acceptExport(file: BrowserFile, destination: BrowserExportDestination,
            progress: (Long, Long?) -> Unit): Deferred<Unit> = synchronized(monitor) {
            // Accept the created document now; even pre-dispatch cancellation must perform its rollback.
            val task = scope.async(start = CoroutineStart.ATOMIC) { export(file, destination, { destination }, progress) }
            jobs += task
            task.invokeOnCompletion { synchronized(monitor) { jobs -= task } }
            if (closed.get()) task.cancel()
            task
        }
        override suspend fun exportCreated(file: BrowserFile, create: () -> BrowserExportDestination,
            progress: (Long, Long?) -> Unit) = export(file, null, create, progress)

        private suspend fun export(file: BrowserFile, initial: BrowserExportDestination?, create: () -> BrowserExportDestination,
            progress: (Long, Long?) -> Unit) {
            var owned = initial
            operation(onFailure = { failure ->
                withContext(NonCancellable + Dispatchers.IO) {
                    owned?.let { try { deleteExport(it) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) } }
                }
            }, verifyAfter = false) {
                withContext(Dispatchers.IO) {
                    if (owned == null) owned = create()
                    ensureActive()
                    checkAccess()
                    requireNotNull(owned).open().use {
                        dav.download(handle, file.path, it, file.etag.takeIf { file.versioned }, progress)
                    }
                }
                dav.publishDownload(handle) {
                    check(!closed.get() && !handle.closed.get()) { "webdav_access_closed" }
                    owned = null
                }
            }
        }
    }

    private fun browserFile(file: DavFile) = BrowserFile(file.path, file.directory, file.size, file.modified?.toString(), file.etag,
        versioned = file.etag?.let { tag -> try { WebDavClient.requireStrongEtag(tag); true } catch (_: IllegalArgumentException) { false } } == true)
    private fun deleteExport(destination: BrowserExportDestination) {
        check(destination.delete()) { "webdav_incomplete_export_remains: ${destination.description}" }
    }
}
