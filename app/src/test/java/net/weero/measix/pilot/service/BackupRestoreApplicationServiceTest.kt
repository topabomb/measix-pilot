package net.weero.measix.pilot.service

import android.content.Context
import android.net.Uri
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.sync.S3Sync
import net.weero.measix.pilot.service.webdav.WebDavBackupItem
import net.weero.measix.pilot.service.webdav.WebDavBackupService
import net.weero.measix.pilot.data.sync.BackupRestorePendingRestartException
import net.weero.measix.pilot.data.sync.BackupArchiveService
import org.junit.Test
import org.junit.Assert.assertFalse

class BackupRestoreApplicationServiceTest {
    @Test
    fun `remote download is serialized before archive staging begins`() = runTest {
        val webDav = mockk<WebDavBackupService>()
        val service = BackupRestoreApplicationService(mockk(relaxed = true), webDav, mockk<S3Sync>(), mockk())
        val first = mockk<WebDavBackupItem>()
        val second = mockk<WebDavBackupItem>()
        val enteredDownload = CompletableDeferred<Unit>()
        val finishDownload = CompletableDeferred<Unit>()
        coEvery { webDav.restore(first) } coAnswers {
            enteredDownload.complete(Unit)
            finishDownload.await()
        }

        val active = async { service.restoreWebDav(first) }
        enteredDownload.await()

        try {
            service.restoreWebDav(second)
            error("Expected restore conflict")
        } catch (_: BackupRestoreOperationInProgressException) {
            // expected
        }
        coVerify(exactly = 0) { webDav.restore(second) }

        finishDownload.complete(Unit)
        active.await()
    }

    @Test
    fun `cancellation after local staging deletes the owned temporary archive`() = runTest {
        val cacheDir = Files.createTempDirectory("restore-owner-test").toFile()
        try {
            val context = mockk<Context>()
            val resolver = mockk<android.content.ContentResolver>()
            val webDav = mockk<WebDavBackupService>()
            val uri = mockk<Uri>()
            val archives = mockk<BackupArchiveService>()
            val staged = slot<java.io.File>()
            val enteredRestore = CompletableDeferred<Unit>()
            every { context.applicationContext } returns context
            every { context.cacheDir } returns cacheDir
            every { context.contentResolver } returns resolver
            every { resolver.openInputStream(uri) } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))
            coEvery { archives.stageRestore(capture(staged), any()) } coAnswers {
                enteredRestore.complete(Unit)
                awaitCancellation()
            }

            val service = BackupRestoreApplicationService(context, webDav, mockk<S3Sync>(), archives)
            val job = launch { service.restoreLocal(uri) }
            enteredRestore.await()
            job.cancelAndJoin()

            assertFalse(staged.captured.exists())
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test(expected = BackupRestorePendingRestartConflictException::class)
    fun `pending restart conflict is translated at the application boundary`() = runTest {
        val webDav = mockk<WebDavBackupService>()
        val item = mockk<WebDavBackupItem>()
        coEvery { webDav.restore(item) } throws BackupRestorePendingRestartException()

        BackupRestoreApplicationService(mockk(relaxed = true), webDav, mockk<S3Sync>(), mockk())
            .restoreWebDav(item)
    }

    @Test
    fun `local restore preserves pending conflict through the application boundary`() = runTest {
        val cacheDir = Files.createTempDirectory("restore-pending-test").toFile()
        try {
            val context = mockk<Context>()
            val resolver = mockk<android.content.ContentResolver>()
            val archiveService = mockk<BackupArchiveService>()
            val uri = mockk<Uri>()
            every { context.applicationContext } returns context
            every { context.cacheDir } returns cacheDir
            every { context.contentResolver } returns resolver
            every { resolver.openInputStream(uri) } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))
            coEvery { archiveService.stageRestore(any(), any()) } throws BackupRestorePendingRestartException()
            val webDav = mockk<WebDavBackupService>()

            try {
                BackupRestoreApplicationService(context, webDav, mockk<S3Sync>(), archiveService)
                    .restoreLocal(uri)
                error("Expected pending restart conflict")
            } catch (_: BackupRestorePendingRestartConflictException) {
                // expected
            }
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
