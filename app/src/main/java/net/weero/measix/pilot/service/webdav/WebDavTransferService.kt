package net.weero.measix.pilot.service.webdav

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.files.requireDiscarded
import net.weero.measix.pilot.data.webdav.WebDavClient
import net.weero.measix.pilot.service.ApplicationRecoveryGate

internal data class WebDavTransferResult(val bytes: Long, val overwritten: Boolean)

internal class WebDavTransferException(val reason: String, message: String, val unknown: Boolean = false,
    cause: Throwable? = null) : Exception(message, cause)

/** Coordinates DAV and managed attachments. Neither transfer depends on a local Workspace. */
internal class WebDavTransferService(
    private val dav: WebDavApplicationService,
    private val artifacts: ArtifactStore,
    private val recovery: ApplicationRecoveryGate,
) {
    suspend fun currentTarget(access: RealmAccess, configuration: ResolvedConfiguration) = dav.captureToolTarget(access, configuration)

    suspend fun upload(access: RealmAccess, target: WebDavTarget, filePath: String,
        remotePath: String, overwrite: Boolean): WebDavTransferResult {
        recovery.awaitReady()
        val handle = dav.captureExpected(access, target)
        try {
            dav.validate(handle, writing = true)
            return artifacts.withUploadFile(access.scope, filePath) { input, length ->
                val existing = dav.stat(handle, remotePath)
                if (existing?.directory == true) throw WebDavTransferException("webdav_not_file", "The remote target is a directory.")
                if (existing != null && !overwrite) throw WebDavTransferException("webdav_file_exists", "The remote file exists; use overwrite=true to replace it.")
                if (existing != null && !WebDavClient.strongEtag(existing.etag))
                    throw WebDavTransferException("webdav_version_unavailable", "The server did not provide a strong ETag for safe replacement.")
                ensureParents(handle, remotePath)
                dav.upload(handle, remotePath, length, { input }, existing?.etag)
                WebDavTransferResult(length, overwritten = existing != null)
            } ?: throw WebDavTransferException("attachment_not_found", "The upload file is unavailable in this space.")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (dav.unknownPaths(handle).any { it == remotePath || remotePath.startsWith("$it/") }) throw WebDavTransferException("outcome_unknown",
                "The remote write may have completed. Verify the destination in the file manager before retrying.", true, failure)
            if (failure is IllegalStateException && failure.message == "webdav_read_only") throw WebDavTransferException("webdav_read_only",
                "The current WebDAV is read-only. Ask your administrator for write access before uploading.",
                cause = failure.takeIf { it.cause != null || it.suppressed.isNotEmpty() })
            throw failure
        } finally { withContext(NonCancellable) { dav.close(handle) } }
    }

    suspend fun download(access: RealmAccess, target: WebDavTarget, remotePath: String,
        receive: (OwnedArtifact) -> Unit): OwnedArtifact {
        recovery.awaitReady()
        val handle = dav.captureExpected(access, target)
        var pending: OwnedArtifact? = null
        var failure: Throwable? = null
        try {
            val source = dav.stat(handle, remotePath)
                ?: throw WebDavTransferException("webdav_file_not_found", "The remote file does not exist.")
            if (source.directory) throw WebDavTransferException("webdav_not_file", "The remote source is a directory.")
            dav.withResource(handle) {
                artifacts.createFromStream(access.scope, remotePath.substringAfterLast('/'), write = { output ->
                    var mime: String? = null
                    dav.download(handle, remotePath, output, source.etag?.takeIf(WebDavClient::strongEtag), contentType = { mime = it })
                    mime
                }, receive = { pending = it })
            }
            return dav.publishDownload(handle) {
                val owned = requireNotNull(pending)
                receive(owned)
                pending = null
                owned
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                var cleanupFailure: Throwable? = null
                try { pending?.let { artifacts.discardUnpublished(it).requireDiscarded("WebDAV download rollback") } }
                catch (error: Throwable) { cleanupFailure = error }
                try { dav.close(handle) }
                catch (error: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = error else cleanupFailure.addSuppressed(error)
                }
                cleanupFailure?.let { cleanup ->
                    if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
                }
            }
        }
    }

    fun resource(owned: OwnedArtifact) = artifacts.unpublishedLease(owned)

    private suspend fun ensureParents(handle: WebDavHandle, path: String) {
        val parts = path.split('/').dropLast(1)
        for (index in parts.indices) {
            val parent = parts.take(index + 1).joinToString("/")
            val existing = dav.stat(handle, parent)
            if (existing == null) dav.mkdir(handle, parent)
            else if (!existing.directory) throw WebDavTransferException("webdav_parent_not_directory", "A remote parent is not a directory: $parent")
        }
    }
}
