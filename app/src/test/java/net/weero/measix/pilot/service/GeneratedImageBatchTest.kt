package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import net.weero.measix.pilot.data.enterprise.EnterpriseDataResetStore
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.enterprise.enrollFixture
import net.weero.measix.pilot.data.enterprise.enterpriseTestStore
import net.weero.measix.pilot.data.enterprise.exampleEnterprisePackage
import net.weero.measix.pilot.data.imggen.GeneratedMediaDeleteResult
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeneratedImageBatchTest {
    @Test fun partialDeletionKeepsPerItemCausesAndDeduplicatesOriginalKeys() = runTest {
        val fixture = Fixture()
        try {
            val selection = fixture.personal()
            val keys = (1..4).map { ManagedFileKey.Generated(it, selection) }
            val failure = IOException("database unavailable", IllegalStateException("original detail"))
            val cleanup = IOException("payload cleanup pending")
            coEvery { fixture.store.deleteDetailed(selection.access.scope, 1) } returns GeneratedMediaDeleteResult.Completed
            coEvery { fixture.store.deleteDetailed(selection.access.scope, 2) } returns GeneratedMediaDeleteResult.Failed(failure)
            coEvery { fixture.store.deleteDetailed(selection.access.scope, 3) } returns GeneratedMediaDeleteResult.AlreadyAbsent
            coEvery { fixture.store.deleteDetailed(selection.access.scope, 4) } returns GeneratedMediaDeleteResult.CleanupPending(cleanup)
            val published = mutableListOf<GeneratedImageBatchItemResult>()
            val result = fixture.files.deleteGeneratedBatch(keys + keys.first(), published::add)
            assertEquals(result, published)
            assertEquals(GeneratedImageBatchItemResult.Deleted(keys[0]), result[0])
            assertSame(failure, (result[1] as GeneratedImageBatchItemResult.Failed).cause)
            assertEquals(GeneratedImageBatchItemResult.AlreadyAbsent(keys[2]), result[2])
            assertSame(cleanup, (result[3] as GeneratedImageBatchItemResult.CleanupPending).cause)
            coVerify(exactly = 4) { fixture.store.deleteDetailed(any(), any()) }
        } finally { fixture.close() }
    }

    @Test fun cancellationKeepsPublishedReceiptAndDoesNotStartAnotherItem() = runTest {
        val fixture = Fixture()
        try {
            val selection = fixture.personal()
            coEvery { fixture.store.deleteDetailed(any(), any()) } returns GeneratedMediaDeleteResult.Completed
            val receipts = mutableListOf<GeneratedImageBatchItemResult>()
            val work = async {
                val owner = currentCoroutineContext().job
                fixture.files.deleteGeneratedBatch((1..3).map { ManagedFileKey.Generated(it, selection) }) {
                    receipts += it
                    owner.cancel(CancellationException("gallery closed"))
                }
            }
            assertTrue(runCatching { work.await() }.exceptionOrNull() is CancellationException)
            assertEquals(listOf(1), receipts.map { it.key.mediaId })
            coVerify(exactly = 1) { fixture.store.deleteDetailed(any(), any()) }
        } finally { fixture.close() }
    }

    @Test fun expiredOriginalSelectionProducesFailuresWithoutCallingAnotherOwner() = runTest {
        val fixture = Fixture()
        try {
            val ready = fixture.sessions.enrollFixture(exampleEnterprisePackage())
            val selection = requireNotNull(fixture.sessions.readPresentation().selection)
            coEvery { fixture.store.deleteDetailed(any(), any()) } returns GeneratedMediaDeleteResult.Completed
            val receipts = fixture.files.deleteGeneratedBatch((1..3).map { ManagedFileKey.Generated(it, selection) }) {
                if (it.key.mediaId == 1) fixture.now = ready.manifest.session!!.expiresAtMillis
            }
            assertTrue(receipts[0] is GeneratedImageBatchItemResult.Deleted)
            assertTrue(receipts.drop(1).all { it is GeneratedImageBatchItemResult.Failed })
            assertTrue((receipts[1] as GeneratedImageBatchItemResult.Failed).cause.message!!.contains("enterprise_data_access_unavailable"))
            coVerify(exactly = 1) { fixture.store.deleteDetailed(any(), any()) }
        } finally { fixture.close() }
    }

    @Test fun mixedSelectionBatchIsRejectedBeforeAnyDeletion() = runTest {
        val fixture = Fixture()
        try {
            val selection = fixture.personal()
            val keys = listOf(ManagedFileKey.Generated(1, selection), ManagedFileKey.Generated(2, selection.copy(revision = selection.revision + 1)))
            assertEquals("generated_image_batch_selection_mismatch", runCatching {
                fixture.files.deleteGeneratedBatch(keys) { error("must not publish") }
            }.exceptionOrNull()?.message)
            coVerify(exactly = 0) { fixture.store.deleteDetailed(any(), any()) }
        } finally { fixture.close() }
    }

    @Test fun exportReusesAuthorizedSourceWithoutNestingSessionAndKeepsOriginalFailure() = runTest {
        val fixture = Fixture()
        try {
            val selection = fixture.personal()
            val exporter = mockk<MediaExportService>()
            val context = mockk<android.content.Context>()
            coEvery { fixture.store.requireImageAccess(any(), any()) } returns Unit
            coEvery { fixture.store.readImage(any(), any()) } answers { byteArrayOf(secondArg<Int>().toByte()) }
            val failure = IOException("external gallery write failed")
            coEvery { exporter.saveImage(context, any()) } coAnswers {
                val image = secondArg<ImageSource>()
                image.requireAccess()
                if (image.readBytes()[0] == 2.toByte()) throw failure
                "exported.png"
            }
            val keys = (1..2).map { ManagedFileKey.Generated(it, selection) }
            val result = withTimeout(2_000) { fixture.files.saveGeneratedBatch(keys, context, exporter) {} }
            assertEquals(GeneratedImageBatchItemResult.Saved(keys[0], "exported.png"), result[0])
            assertSame(failure, (result[1] as GeneratedImageBatchItemResult.Failed).cause)
        } finally { fixture.close() }
    }

    private class Fixture : AutoCloseable {
        val root: File = kotlin.io.path.createTempDirectory("gallery-batch").toFile()
        var now = 1_800_000_000_000L
        val sessions = EnterpriseSessionController(enterpriseTestStore(root), EnterpriseDataResetStore(File(root, "reset"))) { now }
        val store = mockk<GeneratedMediaStore>()
        val files = FileManagementApplicationService(mockk(), mockk(), store, ApplicationRecoveryGate().apply { ready() }, sessions)
        suspend fun personal(): RealmSelection {
            sessions.recover()
            return requireNotNull(sessions.readPresentation().selection)
        }
        override fun close() { root.deleteRecursively() }
    }
}
