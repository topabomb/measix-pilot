package net.weero.measix.pilot.service

import io.mockk.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import net.weero.measix.pilot.data.configuration.WebDavConnection
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.webdav.DavFile
import net.weero.measix.pilot.service.files.*
import net.weero.measix.pilot.service.webdav.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebDavBrowserServiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val selection = RealmSelection(RealmAccess.Personal, 1)
    private val connection = WebDavConnection(name = "Files", rootUrl = "https://dav.example/root/")
    private val file = BrowserFile("note.txt", false, 4, null, "\"v1\"")

    private inner class Fixture(scope: CoroutineScope, val root: File = temporary.newFolder()) {
        val handle = WebDavHandle(selection.access, selection, net.weero.measix.pilot.service.webdav.WebDavTarget.Personal(connection))
        val dav = mockk<WebDavApplicationService> {
            coEvery { publishDownload<Unit>(handle, any()) } coAnswers { secondArg<suspend () -> Unit>().invoke() }
            coEvery { open(selection, any()) } returns handle
            coEvery { validate(handle) } coAnswers { check(!handle.closed.get()) }
            coEvery { isValid(handle) } coAnswers { !handle.closed.get() }
            coEvery { close(handle) } coAnswers { handle.closed.set(true) }
        }
        val queries = mockk<ConfigurationQueryService> {
            every { observeWebDavConfiguration() } returns flowOf(WebDavConfigurationReadState.Available(selection, listOf(connection), connection.id))
            every { observeSelectedRealmSelection() } returns flowOf(selection)
            coEvery { requireSelection(selection) } returns Unit

        }
        val recovery = mockk<ApplicationRecoveryGate> { coEvery { awaitReady() } returns Unit }
        val service = WebDavBrowserService(dav, queries, recovery, scope, root)
        val source = service.source(selection)
    }

    @Test fun `accepted export cancelled before dispatch deletes only the document it owns`() = runTest {
        val f = Fixture(backgroundScope)
        val session = f.source.open()
        var deleted = false
        val target = object : BrowserExportDestination {
            override val description = "created document"
            override fun open(): ByteArrayOutputStream = error("Must not open after cancellation")
            override fun delete(): Boolean { deleted = true; return true }
        }
        val transfer = session.acceptExport(file, target)
        transfer.cancel()
        session.close()
        assertTrue(transfer.isCompleted)
        assertTrue(deleted)
        coVerify(exactly = 0) { f.dav.download(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `failed export reports cleanup failure and closes before returning`() = runTest {
        val f = Fixture(backgroundScope)
        val session = f.source.open()
        var outputClosed = false
        var deletes = 0
        val target = object : BrowserExportDestination {
            override val description = "only owned document"
            override fun open() = object : ByteArrayOutputStream() { override fun close() { outputClosed = true } }
            override fun delete(): Boolean { deletes++; return false }
        }
        coEvery { f.dav.download(any(), any(), any(), any(), any(), any()) } throws IOException("read interrupted")
        try {
            session.exportCreated(file, { target })
            fail("Must report read failure")
        } catch (failure: IOException) {
            assertEquals("read interrupted", failure.message)
            assertTrue(failure.stackTraceToString(), failure.stackTraceToString().contains("only owned document"))
        }
        assertTrue(outputClosed)
        assertEquals(1, deletes)
        session.close()
    }

    @Test fun `startup cleans abandoned previews and expired shares while new share survives closing`() = runTest {
        val root = temporary.newFolder()
        val abandoned = File(root, "preview-old").apply { writeText("abandoned") }
        val expired = File(root, "share-old").apply { writeText("expired"); setLastModified(System.currentTimeMillis() - 90_000_000) }
        val retained = File(root, "share-recent").apply { writeText("recent") }
        val unrelated = File(root, "unrelated").apply { writeText("keep") }
        val f = Fixture(backgroundScope, root)
        coEvery { f.dav.download(any(), any(), any(), any(), any(), any()) } coAnswers {
            thirdArg<java.io.OutputStream>().write("text".toByteArray()); 4L
        }
        val session = f.source.open()
        assertFalse(abandoned.exists())
        assertFalse(expired.exists())
        assertTrue(retained.exists())
        assertTrue(unrelated.exists())
        val preview = session.previewCopy(file)
        var shared: File? = null
        session.share(file) { shared = it }
        assertTrue(requireNotNull(shared).name.startsWith("share-"))
        session.close()
        assertFalse(preview.exists())
        assertEquals("text", requireNotNull(shared).readText())
        coVerify(exactly = 1) { f.dav.publishDownload<Unit>(f.handle, any()) }
    }

    @Test fun `late rejected handoff deletes prepared share and never delivers it`() = runTest {
        val f = Fixture(backgroundScope)
        coEvery { f.dav.download(any(), any(), any(), any(), any(), any()) } coAnswers {
            thirdArg<java.io.OutputStream>().write("text".toByteArray()); 4L
        }
        coEvery { f.dav.publishDownload<Unit>(f.handle, any()) } throws IllegalStateException("webdav_connection_changed")
        val session = f.source.open()
        var delivered = false
        try { session.share(file) { delivered = true }; fail("Changed connection must not deliver") }
        catch (failure: IllegalStateException) { assertEquals("webdav_connection_changed", failure.message) }
        assertFalse(delivered)
        assertTrue(f.root.listFiles()!!.isEmpty())
        session.close()
    }

    @Test fun `cancellation at successful system handoff preserves the already delivered share`() = runTest {
        val f = Fixture(backgroundScope)
        coEvery { f.dav.download(any(), any(), any(), any(), any(), any()) } coAnswers {
            thirdArg<java.io.OutputStream>().write("text".toByteArray()); 4L
        }
        val session = f.source.open()
        var shared: File? = null
        val task = launch {
            val job = currentCoroutineContext().job
            session.share(file) { shared = it; job.cancel() }
        }
        task.join()
        assertTrue(task.isCancelled)
        session.close()
        assertEquals("text", requireNotNull(shared).readText())
    }

    @Test fun `failed startup cleanup can be retried after the obstructing file is repaired`() = runTest {
        val root = temporary.newFolder()
        val obstruction = File(root, "preview-obstructed").apply { mkdir() }
        val child = File(obstruction, "child").apply { writeText("blocks nonrecursive deletion") }
        val f = Fixture(backgroundScope, root)
        try { f.source.open(); fail("Unfinished cleanup must be reported") }
        catch (failure: IOException) { assertTrue(failure.message!!.contains("webdav_temporary_cleanup_failed")) }
        assertTrue(child.delete())
        val session = f.source.open()
        assertFalse(obstruction.exists())
        session.close()
    }

    @Test fun `browser choice changes factory target without modifying the captured session`() = runTest {
        val f = Fixture(backgroundScope)
        val session = f.source.open()
        val other = kotlin.uuid.Uuid.random().toString()
        f.source.choose(other)
        coEvery { f.dav.stat(f.handle, file.path) } returns DavFile(file.path, false, 4, null, file.etag)
        assertEquals(file, session.stat(file.path))
        assertEquals(other, f.source.targetId.value)
        coVerify(exactly = 1) { f.dav.open(selection, null) }
        session.close()
    }
}
