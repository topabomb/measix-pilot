package net.weero.measix.pilot.ui.pages.setting

import androidx.lifecycle.ViewModelStore
import io.mockk.every
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.ConfigurationApplicationService
import net.weero.measix.pilot.service.WebDavConfigurationReadState
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebDavSettingsVMTest {
    @Test fun `temporary query failure retains draft but actual selection revocation clears it`() = runTest {
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
            configuration.value = WebDavConfigurationReadState.Available(selection, emptyList(), null)
            runCurrent()
            assertSame(draft, vm.draft.value)
            configuration.value = WebDavConfigurationReadState.Unavailable("Temporary read error")
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
