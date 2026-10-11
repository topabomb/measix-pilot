package net.weero.measix.pilot.service.webdav

import android.content.Context
import java.io.File
import java.time.Instant
import kotlinx.coroutines.*
import net.weero.measix.pilot.data.configuration.WebDavBackupPreferences
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.sync.BackupArchiveService
import net.weero.measix.pilot.data.sync.BackupSelection
import net.weero.measix.pilot.data.webdav.WebDavClient
import net.weero.measix.pilot.service.ConfigurationQueryService

/** Transient capability, never reconstructed from a displayed backup filename. */
internal class WebDavBackupTarget internal constructor(
    internal val handle: WebDavHandle,
    val preferences: WebDavBackupPreferences,
)

internal data class WebDavBackupItem(
    internal val target: WebDavBackupTarget,
    val path: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
    internal val etag: String?,
)

internal class WebDavBackupService(
    context: Context,
    private val dav: WebDavApplicationService,
    private val queries: ConfigurationQueryService,
    private val archives: BackupArchiveService,
) {
    private val cache = context.applicationContext.cacheDir

    suspend fun open(selection: RealmSelection, preferences: WebDavBackupPreferences): WebDavBackupTarget {
        preferences.validated()
        queries.requireSelection(selection)
        val configuration = queries.read(selection.access)
        check(configuration.webDavBackup == preferences) { "webdav_backup_settings_changed" }
        val id = requireNotNull(preferences.resourceId) { "webdav_backup_resource_unavailable" }
        return WebDavBackupTarget(dav.open(selection, id), preferences)
    }

    suspend fun close(target: WebDavBackupTarget) = dav.close(target.handle)
    fun closeLater(target: WebDavBackupTarget) = dav.closeLater(target.handle)

    suspend fun test(target: WebDavBackupTarget) = dav.withResource(target.handle) { dav.list(target.handle, depth = 0); Unit }

    suspend fun list(target: WebDavBackupTarget): List<WebDavBackupItem> = dav.withResource(target.handle) {
        val path = target.preferences.path
        if (path.isNotEmpty() && dav.stat(target.handle, path) == null) return@withResource emptyList()
        dav.list(target.handle, path).files.filter { !it.directory &&
            it.path.substringAfterLast('/').let { name -> name.startsWith("backup_") && name.endsWith(".zip") }
        }.map { file ->
            WebDavBackupItem(target, file.path, file.path.substringAfterLast('/'), file.size ?: 0L, file.modified ?: Instant.EPOCH, file.etag)
        }.sortedByDescending { it.lastModified }
    }

    suspend fun backup(target: WebDavBackupTarget) = dav.withResource(target.handle) {
        dav.validate(target.handle, writing = true)
        ownedFile({ archives.prepare(target.preferences.selection()) }) { file ->
            val parts = target.preferences.path.split('/').filter(String::isNotEmpty)
            for (index in parts.indices) {
                val path = parts.take(index + 1).joinToString("/")
                val entry = dav.stat(target.handle, path)
                if (entry == null) dav.mkdir(target.handle, path)
                else check(entry.directory) { "webdav_backup_path_not_directory" }
            }
            val path = listOf(target.preferences.path, file.name).filter(String::isNotEmpty).joinToString("/")
            dav.upload(target.handle, path, file.length(), { file.inputStream() })
        }
    }

    suspend fun restore(item: WebDavBackupItem) = dav.withResource(item.target.handle) {
        ownedFile({ File.createTempFile("webdav-restore-", ".zip", cache) }) { file ->
            file.outputStream().use { output ->
                dav.download(item.target.handle, item.path, output, item.etag?.takeIf(WebDavClient::strongEtag))
            }
            dav.validate(item.target.handle)
            archives.stageRestore(file, item.target.preferences.selection()) { publish ->
                dav.publishDownload(item.target.handle, publish)
            }
        }
    }

    suspend fun delete(item: WebDavBackupItem) = dav.withResource(item.target.handle) {
        val tag = requireNotNull(item.etag) { "webdav_strong_etag_required" }
        WebDavClient.requireStrongEtag(tag)
        dav.mutate(item.target.handle, item.path, "DELETE", sourceEtag = tag)
    }

    private suspend fun <T> ownedFile(create: suspend () -> File, block: suspend (File) -> T): T = withContext(Dispatchers.IO) {
        var file: File? = null
        var failure: Throwable? = null
        try {
            val owned = create().also { file = it }
            ensureActive()
            block(owned)
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            withContext(NonCancellable) {
                try { file?.let { check(!it.exists() || it.delete()) { "webdav_backup_temporary_cleanup_failed: ${it.name}" } } }
                catch (cleanup: Throwable) { failure?.addSuppressed(cleanup) ?: throw cleanup }
            }
        }
    }
}

private fun WebDavBackupPreferences.selection() = BackupSelection(includeDurableAggregate, includeDurableAggregate)
