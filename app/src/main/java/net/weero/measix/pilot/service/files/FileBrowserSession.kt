package net.weero.measix.pilot.service.files

import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import net.weero.measix.pilot.service.ImageSource

/** One already-bound browser target. No credentials or source switching enter the shared UI. */
internal interface FileBrowserSession {
    val description: String
    val location: String
    val writable: Boolean
    fun path(value: String): String
    fun child(parent: String, name: String): String
    suspend fun refresh()
    suspend fun isValid(): Boolean
    suspend fun close()
    fun closeLater()
    suspend fun list(path: String): BrowserDirectory
    suspend fun stat(path: String): BrowserFile?
    suspend fun readText(file: BrowserFile): BrowserTextDocument
    suspend fun readPreviewResource(path: String, maxBytes: Long): ByteArray
    fun renderedSource(document: String, changes: Flow<*>): net.weero.measix.pilot.service.RenderedFileSource =
        net.weero.measix.pilot.service.RenderedFileSource(path(document), changes,
            verify = { check(isValid()) { "rendered_file_revoked" } }, validatePath = ::path,
            read = ::readPreviewResource)
    suspend fun createDirectory(path: String): BrowserOperationResult
    suspend fun upload(path: String, etag: String?, length: Long?, input: () -> InputStream,
        progress: (Long, Long?) -> Unit = { _, _ -> }): BrowserOperationResult
    suspend fun save(document: BrowserTextDocument, text: String, destination: String = document.file.path): BrowserOperationResult
    suspend fun mutate(action: BrowserFileAction, file: BrowserFile, destination: String? = null,
        overwrite: BrowserFile? = null, recursiveConfirmed: Boolean = false): BrowserOperationResult
    fun outcomeAfterCancellation(path: String): BrowserOutcome
    suspend fun verifyUnknown(path: String): BrowserFile?
    suspend fun acknowledgeVerified(path: String)
    fun imageSource(file: BrowserFile, maxBytes: Long = net.weero.measix.pilot.service.workspace.MAX_WORKSPACE_IMAGE_BYTES.toLong()): ImageSource
    suspend fun mediaSource(file: BrowserFile): MediaPreviewSource
    suspend fun previewCopy(file: BrowserFile, maxBytes: Long = 24L * 1024 * 1024): File
    suspend fun releaseCopy(copy: File)
    suspend fun share(file: BrowserFile, deliver: (File) -> Unit)
    fun acceptExport(file: BrowserFile, destination: BrowserExportDestination,
        progress: (Long, Long?) -> Unit = { _, _ -> }): Deferred<Unit>
    suspend fun exportCreated(file: BrowserFile, create: () -> BrowserExportDestination,
        progress: (Long, Long?) -> Unit = { _, _ -> })
}

internal interface FileBrowserSource {
    val selection: net.weero.measix.pilot.data.enterprise.RealmSelection?
    val changes: Flow<*>
    suspend fun refresh()
    suspend fun open(): FileBrowserSession
    fun discardExport(destination: BrowserExportDestination, onFailure: (Throwable) -> Unit)
}
