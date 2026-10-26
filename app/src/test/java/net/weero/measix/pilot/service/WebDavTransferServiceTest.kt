package net.weero.measix.pilot.service

import io.mockk.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.configuration.WebDavConnection
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.files.*
import net.weero.measix.pilot.data.webdav.DavFile
import net.weero.measix.pilot.service.webdav.*
import org.junit.Assert.*
import org.junit.Test

class WebDavTransferServiceTest {
    private val access = RealmAccess.Personal
    private val connection = WebDavConnection(name = "Current", rootUrl = "https://dav.example/")
    private val target = WebDavTarget.Personal(connection)
    private val handle = WebDavHandle(access, null, target)
    private val dav = mockk<WebDavApplicationService>(relaxed = true)
    private val artifacts = mockk<ArtifactStore>()
    private val service = WebDavTransferService(dav, artifacts, ApplicationRecoveryGate().apply { ready() })

    init {
        coEvery { dav.captureExpected(access, target) } returns handle
        coEvery { dav.withResource<OwnedArtifact>(handle, any(), any()) } coAnswers { thirdArg<suspend () -> OwnedArtifact>().invoke() }
        coEvery { dav.publishDownload<OwnedArtifact>(handle, any()) } coAnswers { secondArg<suspend () -> OwnedArtifact>().invoke() }
        every { dav.unknownPaths(handle) } returns emptySet()
    }

    private fun uploadSource() {
        coEvery { artifacts.withUploadFile<WebDavTransferResult>(ConfigurationScope.Personal, "/upload/file.bin", any()) } coAnswers {
            "content".byteInputStream().use { thirdArg<suspend (InputStream, Long) -> WebDavTransferResult>().invoke(it, 7L) }
        }
    }

    @Test fun `upload authorizes managed file creates remote parents and preserves raw bytes`() = runTest {
        uploadSource()
        coEvery { dav.stat(handle, any()) } returns null
        coEvery { dav.upload(handle, "a/b/file", 7L, any(), null, any()) } coAnswers {
            assertEquals("content", arg<() -> InputStream>(3)().readBytes().decodeToString())
        }
        assertEquals(WebDavTransferResult(7L, false), service.upload(access, target, "/upload/file.bin", "a/b/file", false))
        coVerifyOrder {
            dav.captureExpected(access, target)
            dav.stat(handle, "a/b/file")
            dav.stat(handle, "a")
            dav.mkdir(handle, "a")
            dav.stat(handle, "a/b")
            dav.mkdir(handle, "a/b")
            dav.upload(handle, "a/b/file", 7L, any(), null, any())
            dav.close(handle)
        }
    }

    @Test fun `missing attachment refuses remote writes and does not use workspace`() = runTest {
        coEvery { artifacts.withUploadFile<WebDavTransferResult>(any(), any(), any()) } returns null
        try { service.upload(access, target, "/upload/missing.zip", "file", false); fail("Missing attachment") }
        catch (failure: WebDavTransferException) { assertEquals("attachment_not_found", failure.reason) }
        coVerify(exactly = 0) { dav.upload(any(), any(), any(), any(), any(), any()) }
        coVerify { dav.close(handle) }
    }

    @Test fun `replacement is explicit and conditional on the captured remote version`() = runTest {
        uploadSource()
        coEvery { dav.stat(handle, "file") } returns DavFile("file", false, 7L, null, "\"original\"")
        try { service.upload(access, target, "/upload/file.bin", "file", false); fail("Must not overwrite") }
        catch (failure: WebDavTransferException) { assertEquals("webdav_file_exists", failure.reason) }
        coVerify(exactly = 0) { dav.upload(any(), any(), any(), any(), any(), any()) }
        assertTrue(service.upload(access, target, "/upload/file.bin", "file", true).overwritten)
        coVerify { dav.upload(handle, "file", 7L, any(), "\"original\"", any()) }
    }

    @Test fun `uncertain upload is reported unknown rather than failed and blindly retried`() = runTest {
        uploadSource()
        coEvery { dav.stat(handle, "file") } returns null
        coEvery { dav.upload(any(), any(), any(), any(), any(), any()) } throws java.io.IOException("connection reset")
        every { dav.unknownPaths(handle) } returns setOf("file")
        try { service.upload(access, target, "/upload/file.bin", "file", false); fail("Unknown write") }
        catch (failure: WebDavTransferException) {
            assertEquals("outcome_unknown", failure.reason); assertTrue(failure.unknown)
            assertTrue(failure.cause is java.io.IOException)
        }
        coVerify(exactly = 1) { dav.upload(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `readonly mapping keeps cancellation and unknown outcome priority and leaves other failures unchanged`() = runTest {
        val unrelated = IllegalStateException("webdav_read_only_extra")
        coEvery { dav.validate(handle, writing = true) } throws unrelated
        try { service.upload(access, target, "/upload/file.bin", "file", false); fail("Must refuse") }
        catch (actual: IllegalStateException) { assertSame(unrelated, actual) }

        val readonly = IllegalStateException("webdav_read_only")
        coEvery { dav.validate(handle, writing = true) } throws readonly
        every { dav.unknownPaths(handle) } returns setOf("file")
        try { service.upload(access, target, "/upload/file.bin", "file", false); fail("Must retain unknown") }
        catch (actual: WebDavTransferException) {
            assertEquals("outcome_unknown", actual.reason)
            assertTrue(actual.unknown)
            assertSame(readonly, actual.cause)
        }

        val cancelled = CancellationException("webdav_read_only")
        coEvery { dav.validate(handle, writing = true) } throws cancelled
        try { service.upload(access, target, "/upload/file.bin", "file", false); fail("Cancellation must propagate") }
        catch (actual: CancellationException) { assertSame(cancelled, actual) }
        coVerify(exactly = 0) { artifacts.withUploadFile<WebDavTransferResult>(any(), any(), any()) }
        coVerify(exactly = 0) { dav.upload(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 3) { dav.close(handle) }
    }

    @Test fun `download streams to artifact owner then guards ownership handoff with original DAV authority`() = runTest {
        val output = ByteArrayOutputStream()
        val owned = mockk<OwnedArtifact>()
        coEvery { dav.stat(handle, "folder/file.zip") } returns DavFile("folder/file.zip", false, 7L, null, "\"version\"")
        coEvery { dav.download(handle, "folder/file.zip", any(), "\"version\"", any(), any(), any()) } coAnswers {
            arg<OutputStream>(2).write("content".toByteArray())
            arg<(String?) -> Unit>(6).invoke("application/zip")
            7L
        }
        coEvery { artifacts.createFromStream(ConfigurationScope.Personal, "file.zip", any(), any()) } coAnswers {
            assertEquals("application/zip", arg<suspend (OutputStream) -> String?>(2).invoke(output))
            arg<(OwnedArtifact) -> Unit>(3).invoke(owned)
            owned
        }
        var accepted = false
        assertSame(owned, service.download(access, target, "folder/file.zip") { assertSame(owned, it); accepted = true })
        assertTrue(accepted)
        assertEquals("content", output.toString())
        coVerifyOrder {
            artifacts.createFromStream(ConfigurationScope.Personal, "file.zip", any(), any())
            dav.publishDownload<OwnedArtifact>(handle, any())
            dav.close(handle)
        }
        coVerify(exactly = 0) { artifacts.discardUnpublished(any()) }
    }

    @Test fun `revocation or failed handoff discards only owned unpublished download`() = runTest {
        val owned = mockk<OwnedArtifact>()
        coEvery { dav.stat(handle, "file") } returns DavFile("file", false, 1L, null, null)
        coEvery { artifacts.createFromStream(any(), any(), any(), any()) } coAnswers {
            arg<(OwnedArtifact) -> Unit>(3).invoke(owned); owned
        }
        coEvery { dav.publishDownload<OwnedArtifact>(handle, any()) } throws IllegalStateException("webdav_connection_changed")
        coEvery { artifacts.discardUnpublished(owned) } returns ArtifactDeleteResult.Completed(1)
        try { service.download(access, target, "file") { fail("Revoked download") }; fail("Must refuse") }
        catch (failure: IllegalStateException) { assertEquals("webdav_connection_changed", failure.message) }
        coVerify(exactly = 1) { artifacts.discardUnpublished(owned) }
        coVerify(exactly = 1) { dav.close(handle) }
    }

    @Test fun `cancelled stream propagates without handoff or fabricated failure`() = runTest {
        coEvery { dav.stat(handle, "file") } returns DavFile("file", false, 1L, null, null)
        coEvery { artifacts.createFromStream(any(), any(), any(), any()) } throws CancellationException("cancelled")
        try { service.download(access, target, "file") { fail("No handoff") }; fail("Cancellation") }
        catch (_: CancellationException) { }
        coVerify(exactly = 0) { dav.publishDownload<OwnedArtifact>(handle, any()) }
        coVerify(exactly = 1) { dav.close(handle) }
    }

    @Test fun `post stream authority failure rolls back before registering a tool lease`() = runTest {
        val owned = mockk<OwnedArtifact>()
        val failure = java.io.IOException("final authority query failed")
        coEvery { dav.stat(handle, "file") } returns DavFile("file", false, 1L, null, null)
        coEvery { artifacts.createFromStream(any(), any(), any(), any()) } coAnswers {
            arg<(OwnedArtifact) -> Unit>(3).invoke(owned); owned
        }
        coEvery { dav.withResource<OwnedArtifact>(handle, any(), any()) } coAnswers {
            thirdArg<suspend () -> OwnedArtifact>().invoke()
            throw failure
        }
        coEvery { artifacts.discardUnpublished(owned) } returns ArtifactDeleteResult.Completed(1)
        try { service.download(access, target, "file") { fail("No lease registration") }; fail("Must throw") }
        catch (actual: Throwable) { assertSame(failure, actual) }
        coVerify(exactly = 0) { dav.publishDownload<OwnedArtifact>(handle, any()) }
        coVerify { artifacts.discardUnpublished(owned) }
    }

    @Test fun `rollback and close failures preserve original failure or cancellation`() = runTest {
        for (primary in listOf(IllegalStateException("revoked"), CancellationException("cancelled"))) {
            val owned = mockk<OwnedArtifact>()
            val rollback = java.io.IOException("rollback failed")
            val close = java.io.IOException("close failed")
            coEvery { dav.stat(handle, "file") } returns DavFile("file", false, 1L, null, null)
            coEvery { artifacts.createFromStream(any(), any(), any(), any()) } coAnswers {
                arg<(OwnedArtifact) -> Unit>(3).invoke(owned); owned
            }
            coEvery { dav.publishDownload<OwnedArtifact>(handle, any()) } throws primary
            coEvery { artifacts.discardUnpublished(owned) } throws rollback
            coEvery { dav.close(handle) } throws close
            try { service.download(access, target, "file") { fail("No handoff") }; fail("Must throw") }
            catch (actual: Throwable) {
                assertSame(primary, actual)
                assertSame(rollback, actual.suppressed.single())
                assertSame(close, rollback.suppressed.single())
            }
        }
        coVerify(exactly = 2) { dav.close(handle) }
    }
}
