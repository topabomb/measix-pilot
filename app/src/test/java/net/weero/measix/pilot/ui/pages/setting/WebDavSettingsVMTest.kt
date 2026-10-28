package net.weero.measix.pilot.ui.pages.setting

import androidx.lifecycle.ViewModelStore
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.configuration.WebDavConnection
import net.weero.measix.pilot.data.configuration.WebDavConnectionChange
import net.weero.measix.pilot.data.configuration.WebDavConnectionField
import net.weero.measix.pilot.service.webdav.WebDavApplicationService
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.ConfigurationApplicationService
import net.weero.measix.pilot.service.WebDavConfigurationReadState
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebDavSettingsVMTest {
    @Test fun `explicit retry resubscribes failed configuration in the same realm and retains draft`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val selection = RealmSelection(RealmAccess.Personal, 1)
            val available = WebDavConfigurationReadState.Available(selection, emptyList(), null)
            val failRead = kotlinx.coroutines.CompletableDeferred<Unit>()
            var subscriptions = 0
            val queries = mockk<ConfigurationQueryService> {
                every { observeSelectedRealmSelection() } returns MutableStateFlow(selection)
                every { observeWebDavConfiguration() } returns kotlinx.coroutines.flow.flow<WebDavConfigurationReadState> {
                    subscriptions++
                    emit(available)
                    if (subscriptions == 1) {
                        failRead.await()
                        emit(WebDavConfigurationReadState.Unavailable("IOException: configuration read failed"))
                    }
                }
            }
            val vm = WebDavSettingsVM(queries, mockk(), mockk()).also { store.put("dav", it) }
            runCurrent()
            vm.edit(null)
            vm.updateDraft { it.copy(rootUrl = "https://dav.example/unsaved", password = "unsaved") }
            val draft = requireNotNull(vm.draft.value)
            failRead.complete(Unit)
            runCurrent()
            assertTrue(vm.configuration.value is WebDavConfigurationReadState.Unavailable)
            assertSame(draft, vm.draft.value)
            vm.refresh()
            runCurrent()
            assertEquals(2, subscriptions)
            assertEquals(available, vm.configuration.value)
            assertSame(draft, vm.draft.value)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `invalid fields never reach IO and corrected anonymous connection tests and saves without a name`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val selection = RealmSelection(RealmAccess.Personal, 1)
            val queries = mockk<ConfigurationQueryService> {
                every { observeSelectedRealmSelection() } returns MutableStateFlow(selection)
                every { observeWebDavConfiguration() } returns MutableStateFlow(
                    WebDavConfigurationReadState.Available(selection, emptyList(), null))
            }
            val commands = mockk<ConfigurationApplicationService>()
            val webDav = mockk<WebDavApplicationService>()
            val vm = WebDavSettingsVM(queries, commands, webDav).also { store.put("dav", it) }
            runCurrent()
            vm.edit(null)
            vm.updateDraft { it.copy(rootUrl = "/") }
            vm.test(); vm.save(); runCurrent()
            assertEquals(WebDavConnectionField.ADDRESS, vm.validationError.value?.field)
            assertEquals("webdav_root_url_invalid", vm.validationError.value?.code)
            assertNull(vm.error.value)
            assertFalse(vm.busy.value)
            coVerify(exactly = 0) { webDav.testConnection(any(), any()) }
            coVerify(exactly = 0) { commands.changeWebDavConnection(any(), any()) }

            vm.updateDraft { it.copy(rootUrl = " https://dav.example/root ") }
            assertNull(vm.validationError.value)
            val testedConnection = slot<WebDavConnection>()
            coEvery { webDav.testConnection(selection, capture(testedConnection)) } returns Unit
            vm.test(); runCurrent()
            assertTrue(vm.tested.value)
            assertEquals("dav.example", testedConnection.captured.name)
            assertEquals("https://dav.example/root/", testedConnection.captured.rootUrl)
            assertEquals("", testedConnection.captured.username)
            assertEquals("", testedConnection.captured.password)
            coVerify(exactly = 0) { commands.changeWebDavConnection(any(), any()) }

            vm.updateDraft { it.copy(rootUrl = "https://dav.example/changed") }
            assertFalse(vm.tested.value)
            coEvery { webDav.testConnection(selection, any()) } throws java.io.IOException("DAV test response missing")
            vm.test(); runCurrent()
            assertTrue(requireNotNull(vm.error.value).contains("DAV test response missing"))
            assertNull(vm.validationError.value)
            vm.updateDraft { it.copy(username = "invalid:user") }
            assertNull(vm.error.value)
            vm.test(); runCurrent()
            assertEquals(WebDavConnectionField.USERNAME, vm.validationError.value?.field)
            coVerify(exactly = 2) { webDav.testConnection(any(), any()) }

            vm.updateDraft { it.copy(name = "  ", username = "") }
            assertNull(vm.validationError.value)
            val savedChange = slot<WebDavConnectionChange>()
            coEvery { commands.changeWebDavConnection(selection, capture(savedChange)) } returns Unit
            val draft = requireNotNull(vm.draft.value)
            vm.save(); runCurrent()
            val save = savedChange.captured as WebDavConnectionChange.Save
            assertEquals(draft.connection.id, save.connection.id)
            assertEquals("dav.example", save.connection.name)
            assertEquals("https://dav.example/changed/", save.connection.rootUrl)
            assertEquals("", save.connection.username)
            assertEquals("", save.connection.password)
            assertNull(save.expected)
            assertNull(vm.draft.value)
            coVerify(exactly = 2) { webDav.testConnection(any(), any()) }
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `save failure retains draft during unavailable projection and selection revocation clears it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val selection = RealmSelection(RealmAccess.Personal, 1)
            val selected = MutableStateFlow<RealmSelection?>(selection)
            val configuration = MutableStateFlow<WebDavConfigurationReadState>(
                WebDavConfigurationReadState.Available(selection, emptyList(), null))
            val queries = mockk<ConfigurationQueryService> {
                every { observeSelectedRealmSelection() } returns selected
                every { observeWebDavConfiguration() } returns configuration
            }
            val commands = mockk<ConfigurationApplicationService>()
            val vm = WebDavSettingsVM(queries, commands, mockk()).also { store.put("dav", it) }
            runCurrent()
            vm.edit(null)
            vm.updateDraft { it.copy(rootUrl = "https://dav.example/unsaved", password = "unsaved") }
            val draft = requireNotNull(vm.draft.value)
            configuration.value = WebDavConfigurationReadState.Unavailable("Temporary read error")
            runCurrent()
            assertSame(draft, vm.draft.value)
            coEvery { commands.changeWebDavConnection(selection, any()) } throws java.io.IOException("Save failed")
            vm.save(); runCurrent()
            assertSame(draft, vm.draft.value)
            assertTrue(requireNotNull(vm.error.value).contains("Save failed"))
            coEvery { commands.changeWebDavConnection(selection, any()) } returns Unit
            vm.save(); runCurrent()
            assertNull(vm.draft.value)
            configuration.value = WebDavConfigurationReadState.Available(selection, emptyList(), null)
            runCurrent()
            vm.edit(null)
            assertNotNull(vm.draft.value)
            selected.value = null
            runCurrent()
            assertNull(vm.draft.value)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
