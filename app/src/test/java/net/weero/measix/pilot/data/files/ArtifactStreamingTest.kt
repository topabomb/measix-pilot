package net.weero.measix.pilot.data.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
internal class ArtifactStreamingTest : ArtifactStoreLifecycleTestBase() {
    @Test fun `arbitrary streamed files keep original bytes and separate identities and reject unpublished reads`() = runBlocking {
        folders += FileFolders.UPLOAD
        val bytes = ByteArray(256 * 1024 + 17) { (it % 251).toByte() }
        val first = store.createFromStream(ConfigurationScope.Personal, "archive.zip",
            write = { output -> output.write(bytes); "application/zip; charset=binary" }, receive = {})
        val second = store.createFromStream(ConfigurationScope.Personal, "archive.zip",
            write = { output -> output.write(bytes); null }, receive = {})
        assertNotEquals(first.localRef.toolPath(), second.localRef.toolPath())
        assertEquals("archive.zip", first.entity.displayName)
        assertEquals("application/zip", first.entity.mimeType)
        assertEquals(bytes.size.toLong(), first.entity.sizeBytes)
        assertArrayEquals(bytes, File(context.filesDir, first.entity.relativePath).readBytes())
        assertNull(store.withUploadFile(ConfigurationScope.Personal, first.localRef.toolPath()!!) { _, _ -> "unexpected" })
        store.discardUnpublished(first).requireDiscarded("test cleanup")
        store.discardUnpublished(second).requireDiscarded("test cleanup")
        assertFalse(File(context.filesDir, first.entity.relativePath).exists())
    }

    @Test fun `cancelled writer and rejected handoff leave neither payload nor metadata`() = runBlocking {
        folders += FileFolders.UPLOAD
        val writing = CompletableDeferred<Unit>()
        val job = launch {
            store.createFromStream(ConfigurationScope.Personal, "partial.bin", write = { output ->
                output.write(ByteArray(128 * 1024))
                writing.complete(Unit)
                awaitCancellation()
            }, receive = { fail("Cancelled producer cannot hand off") })
        }
        writing.await()
        job.cancelAndJoin()
        assertTrue(payloadStore.listStagingTokens().isEmpty())
        assertTrue(payloadStore.listFinalFiles(FileFolders.UPLOAD).isEmpty())
        val expected = IOException("handoff rejected")
        try {
            store.createFromStream(ConfigurationScope.Personal, "partial.bin", write = { it.write(1); null },
                receive = { throw expected })
            fail("Receiver failed")
        } catch (actual: IOException) { assertSame(expected, actual) }
        assertTrue(payloadStore.listStagingTokens().isEmpty())
        assertTrue(payloadStore.listFinalFiles(FileFolders.UPLOAD).isEmpty())
        assertNull(store.getByRelativePath("upload/000000.bin"))
    }

    @Test fun `stream read authorizes scope and publication then pins outside lifecycle lock`() = runBlocking {
        folders += FileFolders.UPLOAD
        val file = File(context.filesDir, "upload/registered.bin").apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(0, -1, 10, 2))
        }
        val entity = ArtifactEntity(folder = "upload", relativePath = "upload/registered.bin", displayName = "data.bin",
            mimeType = "application/octet-stream", sizeBytes = 4, createdAt = 1, updatedAt = 1, state = ArtifactState.ACTIVE.name)
        val id = database.artifactDao().insert(entity)
        val foreign = ConfigurationScope.Enterprise(me.rerere.common.configuration.EnterpriseAuthority("deployment"), "user")
        assertNull(store.withUploadFile(foreign, "/upload/registered.bin") { _, _ -> "unexpected" })
        assertNull(store.withUploadFile(ConfigurationScope.Personal, "/upload/unregistered.bin") { _, _ -> "unexpected" })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val read = async {
            store.withUploadFile(ConfigurationScope.Personal, "/upload/registered.bin") { input, size ->
                assertEquals(4L, size)
                entered.complete(Unit)
                release.await()
                input.readBytes()
            }
        }
        entered.await()
        val deletion = store.deleteUserRequested(ConfigurationScope.Personal, id) as ArtifactDeleteResult.Rejected
        assertEquals(ArtifactDeleteResult.RejectionReason.IN_PROGRESS, deletion.reason)
        release.complete(Unit)
        assertArrayEquals(file.readBytes(), read.await())
        assertTrue(store.deleteUserRequested(ConfigurationScope.Personal, id) is ArtifactDeleteResult.Completed)
    }
}
