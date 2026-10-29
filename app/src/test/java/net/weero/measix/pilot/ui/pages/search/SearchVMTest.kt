package net.weero.measix.pilot.ui.pages.search

import android.app.Application
import androidx.lifecycle.ViewModelStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.service.SearchIndexMaintenanceService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchVMTest {
    @Test
    fun `query and rebuild retain diagnostics while retry and cancellation clear failures`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner = ViewModelStore()
        try {
            val queries = mockk<ConversationQueryService>()
            every { queries.observeCurrentAccess() } returns MutableStateFlow<RealmAccess?>(RealmAccess.Personal)
            val maintenance = mockk<SearchIndexMaintenanceService>()
            val vm = SearchVM(mockk<Application>(relaxed = true), queries, maintenance)
            owner.put("search", vm)
            runCurrent()

            coEvery { queries.searchMessages(any(), any(), any()) } throws
                IOException("query read failed", IllegalStateException("index unavailable"))
            vm.onQueryChange("gear")
            advanceTimeBy(300)
            runCurrent()
            assertTrue(requireNotNull(vm.failure).detail.contains("IOException: query read failed"))
            assertTrue(requireNotNull(vm.failure).detail.contains("IllegalStateException: index unavailable"))
            assertFalse(vm.isLoading)

            coEvery { queries.searchMessages(any(), any(), any()) } returns emptyList()
            vm.search()
            runCurrent()
            assertNull(vm.failure)

            coEvery { maintenance.rebuild(any()) } throws IOException("rebuild write failed")
            vm.rebuildIndex()
            runCurrent()
            assertTrue(requireNotNull(vm.failure).detail.contains("IOException: rebuild write failed"))
            assertFalse(vm.isRebuilding)

            coEvery { maintenance.rebuild(any()) } throws CancellationException("cancel rebuild")
            vm.rebuildIndex()
            runCurrent()
            assertNull(vm.failure)
            assertFalse(vm.isRebuilding)

            coEvery { queries.searchMessages(any(), any(), any()) } throws CancellationException("cancel query")
            vm.search()
            runCurrent()
            assertNull(vm.failure)
            assertFalse(vm.isLoading)
        } finally {
            owner.clear()
            Dispatchers.resetMain()
        }
    }
}
