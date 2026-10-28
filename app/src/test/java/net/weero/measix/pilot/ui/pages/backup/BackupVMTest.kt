package net.weero.measix.pilot.ui.pages.backup

import androidx.lifecycle.ViewModelStore
import io.mockk.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import net.weero.measix.pilot.data.configuration.WebDavBackupPreferences
import net.weero.measix.pilot.data.configuration.WebDavConnection
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.sync.S3BackupItem
import net.weero.measix.pilot.data.sync.S3Sync
import net.weero.measix.pilot.data.sync.s3.S3Config
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.service.webdav.*
import net.weero.measix.pilot.utils.UiState
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackupVMTest {
    @Test fun `accepted S3 restore retains its original bucket while configuration changes`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val finish = CompletableDeferred<Unit>()
        try {
            val first = S3Config(endpoint = "https://s3.example", bucket = "first")
            val second = first.copy(bucket = "second")
            val settings = MutableStateFlow(Settings(s3Config = first))
            val item = S3BackupItem("measix_pilot_backups/backup_shared.zip", "backup_shared.zip", 1, Instant.EPOCH)
            val sync = mockk<S3Sync>(relaxed = true)
            val restores = mockk<BackupRestoreApplicationService>(relaxed = true)
            coEvery { sync.listBackupFiles(first) } returns listOf(item)
            coEvery { restores.restoreS3(first, item) } coAnswers { finish.await() }
            val vm = BackupVM(mockk<SettingsStore> { every { userSettings } returns settings },
                mockk(relaxed = true), sync, restores,
                mockk { every { observeWebDavConfiguration() } returns MutableStateFlow(WebDavConfigurationReadState.Loading) },
                mockk()).also { store.put("backup", it) }
            runCurrent()
            vm.deleteS3BackupFile(item)
            coVerify(exactly = 1) { sync.deleteS3BackupFile(first, item) }
            val restore = launch { vm.restoreFromS3(item) }
            runCurrent()
            settings.value = settings.value.copy(s3Config = second)
            runCurrent()
            assertEquals(UiState.Idle, vm.s3BackupItems.value)
            assertTrue(restore.isActive)
            finish.complete(Unit)
            restore.join()
            coVerify(exactly = 1) { restores.restoreS3(first, item) }
            coVerify(exactly = 0) { restores.restoreS3(second, any()) }
        } finally { finish.complete(Unit); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `late S3 listing cannot replace results from the current bucket`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val delayed = CompletableDeferred<List<S3BackupItem>>()
        try {
            val first = S3Config(endpoint = "https://s3.example", bucket = "first")
            val second = first.copy(bucket = "second")
            val settings = MutableStateFlow(Settings(s3Config = first))
            val oldItem = S3BackupItem("measix_pilot_backups/backup_old.zip", "backup_old.zip", 1, Instant.EPOCH)
            val newItem = S3BackupItem("measix_pilot_backups/backup_new.zip", "backup_new.zip", 2, Instant.EPOCH)
            val sync = mockk<S3Sync>(relaxed = true)
            coEvery { sync.listBackupFiles(first) } coAnswers { withContext(NonCancellable) { delayed.await() } }
            coEvery { sync.listBackupFiles(second) } returns listOf(newItem)
            val vm = BackupVM(mockk<SettingsStore> { every { userSettings } returns settings },
                mockk(relaxed = true), sync, mockk(relaxed = true),
                mockk { every { observeWebDavConfiguration() } returns MutableStateFlow(WebDavConfigurationReadState.Loading) },
                mockk()).also { store.put("backup", it) }
            runCurrent()
            coVerify(atLeast = 1) { sync.listBackupFiles(first) }
            settings.value = settings.value.copy(s3Config = second)
            runCurrent()
            vm.loadS3BackupFileItems()
            runCurrent()
            assertEquals(listOf(newItem), (vm.s3BackupItems.value as UiState.Success).data)
            delayed.complete(listOf(oldItem))
            runCurrent()
            assertEquals("A completed request from the old bucket must not replace the current list",
                listOf(newItem), (vm.s3BackupItems.value as UiState.Success).data)
        } finally { delayed.complete(emptyList()); store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `old S3 items cannot restore or delete an object in a newly selected bucket`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val first = S3Config(endpoint = "https://s3.example", bucket = "first")
            val second = first.copy(bucket = "second")
            val settings = MutableStateFlow(Settings(s3Config = first))
            val oldItem = S3BackupItem("measix_pilot_backups/backup_shared.zip", "backup_shared.zip", 1, Instant.EPOCH)
            val sync = mockk<S3Sync>(relaxed = true)
            val restores = mockk<BackupRestoreApplicationService>(relaxed = true)
            coEvery { sync.listBackupFiles(first) } returns listOf(oldItem)
            val vm = BackupVM(mockk<SettingsStore> { every { userSettings } returns settings },
                mockk(relaxed = true), sync, restores,
                mockk { every { observeWebDavConfiguration() } returns MutableStateFlow(WebDavConfigurationReadState.Loading) },
                mockk()).also { store.put("backup", it) }
            runCurrent()
            assertEquals(listOf(oldItem), (vm.s3BackupItems.value as UiState.Success).data)
            settings.value = settings.value.copy(s3Config = second)
            runCurrent()
            // A row/confirmation callback can outlive a configuration change. A rejection is valid;
            // interpreting the old key against the replacement bucket is never valid.
            try { vm.restoreFromS3(oldItem) } catch (error: IllegalStateException) { }
            try { vm.deleteS3BackupFile(oldItem) } catch (error: IllegalStateException) { }
            coVerify(exactly = 0) { restores.restoreS3(second, oldItem) }
            coVerify(exactly = 0) { sync.deleteS3BackupFile(second, oldItem) }
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `initialization failure can retry without changing saved preferences`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val selection = RealmSelection(RealmAccess.Personal, 1)
            val connection = WebDavConnection(name = "Backup", rootUrl = "https://dav.example/")
            val preferences = WebDavBackupPreferences(connection.id.toString())
            val available = WebDavConfigurationReadState.Available(selection, listOf(connection), connection.id, preferences)
            val target = WebDavBackupTarget(WebDavHandle(selection.access, selection, net.weero.measix.pilot.service.webdav.WebDavTarget.Personal(connection)), preferences)
            val dav = mockk<WebDavBackupService>(relaxed = true)
            coEvery { dav.open(selection, preferences) } throws IllegalStateException("temporary_unavailable")
            val vm = BackupVM(mockk<SettingsStore> { every { userSettings } returns MutableStateFlow(Settings()) },
                dav, mockk(relaxed = true), mockk(),
                mockk { every { observeWebDavConfiguration() } returns MutableStateFlow(available) }, mockk())
                .also { store.put("backup", it) }
            runCurrent()
            assertFalse(vm.webDavReady.value)
            assertEquals("temporary_unavailable", vm.webDavIssue.value?.message)
            coEvery { dav.open(selection, preferences) } returns target
            coEvery { dav.list(target) } returns emptyList()
            vm.retryWebDavConnection()
            runCurrent()
            assertTrue(vm.webDavReady.value)
            assertNull(vm.webDavIssue.value)
            assertSame(target, vm.webDavTargetIdentity.value)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `backup choice survives current DAV switch and stale list cannot retarget restore`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val selection = RealmSelection(RealmAccess.Personal, 1)
            val first = WebDavConnection(name = "First", rootUrl = "https://first.example/")
            val second = WebDavConnection(name = "Second", rootUrl = "https://second.example/")
            val original = WebDavConfigurationReadState.Available(selection, listOf(first, second), first.id,
                WebDavBackupPreferences(first.id.toString()))
            val state = MutableStateFlow<WebDavConfigurationReadState>(original)
            val firstTarget = WebDavBackupTarget(WebDavHandle(selection.access, selection, net.weero.measix.pilot.service.webdav.WebDavTarget.Personal(first)), original.backup)
            val nextPreferences = WebDavBackupPreferences(second.id.toString())
            val secondTarget = WebDavBackupTarget(WebDavHandle(selection.access, selection, net.weero.measix.pilot.service.webdav.WebDavTarget.Personal(second)), nextPreferences)
            val oldItem = WebDavBackupItem(firstTarget, "backup_old.zip", "backup_old.zip", 1, Instant.EPOCH, "\"old\"")
            val newItem = WebDavBackupItem(secondTarget, "backup_new.zip", "backup_new.zip", 1, Instant.EPOCH, "\"new\"")
            val lateList = CompletableDeferred<List<WebDavBackupItem>>()
            val dav = mockk<WebDavBackupService>(relaxed = true)
            coEvery { dav.open(selection, original.backup) } returns firstTarget
            coEvery { dav.open(selection, nextPreferences) } returns secondTarget
            coEvery { dav.list(firstTarget) } coAnswers { withContext(NonCancellable) { lateList.await() } }
            coEvery { dav.list(secondTarget) } returns listOf(newItem)
            val restores = mockk<BackupRestoreApplicationService>(relaxed = true)
            val vm = BackupVM(mockk<SettingsStore> { every { userSettings } returns MutableStateFlow(Settings()) },
                dav, mockk(relaxed = true), restores,
                mockk { every { observeWebDavConfiguration() } returns state }, mockk())
                .also { store.put("backup", it) }
            runCurrent()
            state.value = original.copy(personalCurrentId = second.id)
            runCurrent()
            state.value = original.copy(personalConnections = listOf(first.copy(name = "Renamed"), second), personalCurrentId = second.id)
            runCurrent()
            coVerify(exactly = 1) { dav.open(any(), any()) }
            coVerify(exactly = 0) { dav.close(any()) }
            state.value = original.copy(personalCurrentId = second.id, backup = nextPreferences)
            runCurrent()
            assertEquals(listOf(newItem), (vm.webDavBackupItems.value as UiState.Success).data)
            lateList.complete(listOf(oldItem))
            runCurrent()
            assertEquals(listOf(newItem), (vm.webDavBackupItems.value as UiState.Success).data)
            try { vm.restore(oldItem); fail("Old item must not be reinterpreted against the new target") }
            catch (error: IllegalStateException) { assertEquals("webdav_backup_target_changed", error.message) }
            coVerify(exactly = 0) { restores.restoreWebDav(any()) }
            vm.restore(newItem)
            coVerify(exactly = 1) { restores.restoreWebDav(newItem) }
            state.value = WebDavConfigurationReadState.Unavailable("selection_expired")
            runCurrent()
            assertFalse(vm.webDavReady.value)
            assertEquals(UiState.Idle, vm.webDavBackupItems.value)
            coVerify(exactly = 1) { dav.close(secondTarget) }
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
