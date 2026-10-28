package net.weero.measix.pilot.ui.pages.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.paging.PagingData
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.datastore.ConversationSortOrder
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatDrawerTargetTest {
    @Test fun `current chat supplies drawer target without a saved default and rejects previous folder after assistant change`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val selection = RealmSelection(RealmAccess.Personal, 4)
            val current = ConversationFolderAccess(selection, ConfigurationReference.random())
            val next = current.copy(assistantId = ConfigurationReference.random())
            val configuration = mockk<ConfigurationQueryService>()
            every { configuration.observeAssistantCatalog() } returns MutableStateFlow(AssistantCatalogReadState.Loading)
            every { configuration.observeWebDavConfiguration() } returns flowOf(WebDavConfigurationReadState.Loading)
            val query = mockk<ConversationQueryService>()
            every { query.observeSortOrder() } returns flowOf(ConversationSortOrder.UPDATED)
            every { query.conversationActivities() } returns flowOf(emptyMap())
            every { query.foldersOfAssistant(any()) } answers { flowOf(ConversationFolderDirectory(firstArg(), emptyList())) }
            every { query.unfiledPaging(any(), any()) } returns flowOf(PagingData.empty())
            every { query.folderPaging(any(), any(), any()) } returns flowOf(PagingData.empty())
            val vm = ChatDrawerVM(mockk(), configuration, query, mockk(), SavedStateHandle())
            store.put("drawer", vm)
            val collection = backgroundScope.launch { vm.conversations.collect {} }
            vm.bindTarget(current)
            runCurrent()
            verify { query.unfiledPaging(current, ConversationSortOrder.UPDATED) }
            assertEquals(current, vm.folderDirectory.value?.access)
            val folder = Uuid.random()
            vm.selectFolder(current, folder)
            runCurrent()
            assertEquals(current to folder, vm.draftFolder.value)
            // A loading destination has no target to bind; its same-assistant Ready state keeps the filter.
            vm.bindTarget(current)
            runCurrent()
            assertEquals(current to folder, vm.draftFolder.value)
            vm.bindTarget(next)
            runCurrent()
            assertNull(vm.draftFolder.value)
            assertNull(vm.selectedFolderId.value)
            assertEquals(next, vm.folderDirectory.value?.access)
            vm.selectFolder(current, folder)
            runCurrent()
            assertNull(vm.draftFolder.value)
            verify { query.unfiledPaging(next, ConversationSortOrder.UPDATED) }
            collection.cancel()
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
