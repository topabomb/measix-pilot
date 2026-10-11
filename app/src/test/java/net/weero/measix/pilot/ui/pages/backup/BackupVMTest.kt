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
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.service.webdav.*
import net.weero.measix.pilot.utils.UiState
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackupVMTest {
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
