package net.weero.measix.pilot.ui.pages.assistant

import androidx.lifecycle.ViewModelStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.AssistantDeletionPreview
import net.weero.measix.pilot.service.AssistantDeletionResult
import net.weero.measix.pilot.service.AssistantManagementService
import org.junit.Assert.*
import org.junit.Test

class AssistantDeletionVMTest {
    @Test fun `dismissed approval cannot delete and confirmation passes exact original preview once`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val assistant = Assistant(name = "Shared assistant")
            val settings = mockk<SettingsStore>()
            every { settings.userSettings } returns MutableStateFlow(Settings.dummy())
            val service = mockk<AssistantManagementService>()
            val preview = mockk<AssistantDeletionPreview>()
            every { preview.pendingInputCount } returns 2
            coEvery { service.prepareDeletion(assistant.id) } returns preview
            coEvery { service.deleteAssistant(assistant.id, null, preview) } returns
                Result.success(AssistantDeletionResult(assistant, cleanupPending = true))
            val vm = AssistantVM(settings, mockk(), service, mockk())
            store.put("assistant", vm)
            vm.removeAssistant(assistant)
            runCurrent()
            val abandoned = requireNotNull(vm.pendingDeletion)
            assertEquals(2, abandoned.preview.pendingInputCount)
            coVerify(exactly = 0) { service.deleteAssistant(any(), any(), any()) }
            vm.dismissDeletion()
            vm.confirmDeletion(abandoned)
            runCurrent()
            coVerify(exactly = 0) { service.deleteAssistant(any(), any(), any()) }
            vm.removeAssistant(assistant)
            runCurrent()
            val accepted = requireNotNull(vm.pendingDeletion)
            vm.confirmDeletion(accepted)
            vm.confirmDeletion(accepted)
            runCurrent()
            coVerify(exactly = 1) { service.deleteAssistant(assistant.id, null, preview) }
            assertNull(vm.pendingDeletion)
            assertTrue(vm.deletionCleanupPending)
            assertFalse(vm.deletionBusy)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
