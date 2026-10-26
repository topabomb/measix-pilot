package net.weero.measix.pilot.service

import android.content.Context
import io.mockk.*
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.configuration.WebDavBackupPreferences
import net.weero.measix.pilot.data.configuration.WebDavConnection
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.sync.BackupArchiveService
import net.weero.measix.pilot.data.webdav.DavDirectory
import net.weero.measix.pilot.data.webdav.DavFile
import net.weero.measix.pilot.service.webdav.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class WebDavBackupServiceTest {
    private val cache = Files.createTempDirectory("dav-backup-test").toFile()
    private val context = mockk<Context> {
        every { applicationContext } returns this
        every { cacheDir } returns cache
    }
    private val connection = WebDavConnection(name = "Backup", rootUrl = "https://dav.example/")
    private val current = WebDavConnection(name = "Agent", rootUrl = "https://other.example/")
    private val selection = RealmSelection(RealmAccess.Personal, 1)
    private val preferences = WebDavBackupPreferences(connection.id.toString())
    private val handle = WebDavHandle(selection.access, selection, net.weero.measix.pilot.service.webdav.WebDavTarget.Personal(connection))
    private val target = WebDavBackupTarget(handle, preferences)
    private val dav = mockk<WebDavApplicationService>(relaxed = true)
    private val queries = mockk<ConfigurationQueryService>()
    private val archives = mockk<BackupArchiveService>()
    private val service = WebDavBackupService(context, dav, queries, archives)

    init {
        coEvery { queries.requireSelection(selection) } returns Unit
        coEvery { queries.read(selection.access) } returns mockk<ResolvedConfiguration> {
            every { webDavBackup } returns preferences
            every { personalWebDavConnections } returns listOf(current, connection)
            every { currentPersonalWebDavConnectionId } returns current.id
        }
        coEvery { dav.open(selection, connection.id.toString()) } returns handle
        coEvery { dav.withResource<Any?>(handle, any(), any()) } coAnswers { thirdArg<suspend () -> Any?>().invoke() }
    }

    @After fun cleanUp() { cache.deleteRecursively() }

    @Test fun `backup selects its own resource and malformed preferences acquire no handle`() = runTest {
        assertSame(handle, service.open(selection, preferences).handle)
        coVerify(exactly = 1) { dav.open(selection, connection.id.toString()) }
        coVerify(exactly = 0) { dav.open(selection, current.id.toString()) }
        clearMocks(dav, answers = false)
        try {
            service.open(selection, preferences.copy(path = "../invalid"))
            fail("Invalid path must be rejected before opening")
        } catch (_: IllegalArgumentException) { }
        coVerify(exactly = 0) { dav.open(any(), any()) }
    }

    @Test fun `missing backup directory is an empty read without remote writes`() = runTest {
        coEvery { dav.stat(handle, preferences.path) } returns null
        assertTrue(service.list(target).isEmpty())
        coVerify(exactly = 0) { dav.mkdir(any(), any()) }
        coVerify(exactly = 0) { dav.upload(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `listed backup retains original target and delete pins original version`() = runTest {
        coEvery { dav.stat(handle, preferences.path) } returns DavFile(preferences.path, true, null, null, null)
        coEvery { dav.list(handle, preferences.path, 1) } returns DavDirectory(files = listOf(
            DavFile("${preferences.path}/backup_one.zip", false, 100, Instant.EPOCH, "\"v1\""),
            DavFile("${preferences.path}/other.zip", false, 100, null, null),
        ), self = null, usedBytes = null, availableBytes = null)
        val item = service.list(target).single()
        assertSame(target, item.target)
        service.delete(item)
        coVerify { dav.mutate(handle, item.path, "DELETE", sourceEtag = "\"v1\"") }
    }

    @Test fun `restore publication requires original authority and always removes temporary archive`() = runTest {
        val item = WebDavBackupItem(target, "backup.zip", "backup.zip", 1, Instant.EPOCH, "\"v1\"")
        var staged: File? = null
        var published = false
        coEvery { archives.stageRestore(any(), any(), any()) } coAnswers {
            staged = firstArg<File>()
            assertTrue(staged!!.exists())
            thirdArg<suspend (suspend () -> Unit) -> Unit>().invoke { published = true }
        }
        coEvery { dav.publishDownload<Unit>(handle, any()) } throws IllegalStateException("webdav_connection_changed")
        try { service.restore(item); fail("Revoked target must not publish") }
        catch (error: IllegalStateException) { assertEquals("webdav_connection_changed", error.message) }
        assertFalse(published)
        assertFalse(staged!!.exists())
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun `cancelled upload cleans its archive and does not retry`() = runTest {
        val archive = File(cache, "backup_owned.zip").apply { writeText("data") }
        coEvery { archives.prepare(any()) } returns archive
        coEvery { dav.stat(handle, preferences.path) } returns null
        coEvery { dav.upload(handle, "${preferences.path}/${archive.name}", any(), any(), any(), any()) } throws CancellationException("cancelled")
        try { service.backup(target); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertFalse(archive.exists())
        coVerify(exactly = 1) { dav.upload(any(), any(), any(), any(), any(), any()) }
    }
}
