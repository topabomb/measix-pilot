package net.weero.measix.pilot.service.remoteworkspace

import net.weero.measix.pilot.service.files.*

import java.io.File
import java.io.InputStream
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.enterprise.WorkspaceFileRules
import net.weero.measix.pilot.service.files.FileBrowserSession
import net.weero.measix.pilot.service.files.FileBrowserSource

/** Adapts the existing Files owner to the shared file-management UI. */
internal class RemoteWorkspaceBrowserSource(
    private val service: RemoteWorkspaceService,
    override val selection: RealmSelection?,
) : FileBrowserSource {
    override val changes = service.summary
    override suspend fun refresh() { selection?.let { service.refresh(it) } }
    override suspend fun open(): FileBrowserSession = Session(service.open(requireNotNull(selection)))
    override fun discardExport(destination: BrowserExportDestination, onFailure: (Throwable) -> Unit) =
        service.discardExport(destination, onFailure)

    private inner class Session(private val handle: RemoteWorkspaceHandle) : FileBrowserSession {
        override val description get() = handle.space
        override val location get() = handle.connection.origin
        override val writable = true
        override fun path(value: String) = WorkspaceFileRules.path(value, rootAllowed = true)
        override fun child(parent: String, name: String) = WorkspaceFileRules.child(parent, name)
        override suspend fun refresh() = service.refresh(handle.selection)
        override suspend fun isValid() = service.isValid(handle)
        override suspend fun close() = service.close(handle)
        override fun closeLater() = service.closeLater(handle)
        override suspend fun list(path: String) = service.list(handle, path)
        override suspend fun stat(path: String) = service.list(handle, path.substringBeforeLast('/', "")).files.find { it.path == path }
        override suspend fun readText(file: BrowserFile) = service.readText(handle, file)
        override suspend fun readPreviewResource(path: String, maxBytes: Long) = service.readPreviewResource(handle, path, maxBytes)
        override suspend fun createDirectory(path: String) = service.createDirectory(handle, path)
        override suspend fun upload(path: String, etag: String?, length: Long?, input: () -> InputStream,
            progress: (Long, Long?) -> Unit) = service.upload(handle, path, etag, length, input, progress)
        override suspend fun save(document: BrowserTextDocument, text: String, destination: String) = service.save(handle, document, text, destination)
        override suspend fun mutate(action: BrowserFileAction, file: BrowserFile, destination: String?, overwrite: BrowserFile?,
            recursiveConfirmed: Boolean) = service.mutate(handle, action, file, destination, overwrite, recursiveConfirmed)
        override fun outcomeAfterCancellation(path: String) = service.outcomeAfterCancellation(handle, path)
        override suspend fun verifyUnknown(path: String) = service.verifyUnknown(handle, path)
        override suspend fun acknowledgeVerified(path: String) = service.acknowledgeVerified(handle, path)
        override fun imageSource(file: BrowserFile, maxBytes: Long) = service.imageSource(handle, file, maxBytes)
        override suspend fun mediaSource(file: BrowserFile) = service.mediaSource(handle, file)
        override suspend fun previewCopy(file: BrowserFile, maxBytes: Long) = service.previewCopy(handle, file, maxBytes)
        override suspend fun releaseCopy(copy: File) = service.releaseCopy(copy)
        override suspend fun share(file: BrowserFile, deliver: (File) -> Unit) = service.share(handle, file, deliver)
        override fun acceptExport(file: BrowserFile, destination: BrowserExportDestination,
            progress: (Long, Long?) -> Unit) = service.acceptExport(handle, file, destination, progress)
        override suspend fun exportCreated(file: BrowserFile, create: () -> BrowserExportDestination,
            progress: (Long, Long?) -> Unit) = service.exportCreated(handle, file, create, progress)
    }
}
