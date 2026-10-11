package net.weero.measix.pilot.ui.pages.history

import net.weero.measix.pilot.data.datastore.ConversationSortOrder
import androidx.lifecycle.ViewModelStore
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.ConfigurationSelection
import net.weero.measix.pilot.data.configuration.ConfigurationUnavailableReason
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.AssistantCatalogUiModel
import net.weero.measix.pilot.service.AssistantCatalogReadState
import net.weero.measix.pilot.service.ConfigurationQueryService
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.service.ConversationSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import net.weero.measix.pilot.service.ConversationApplicationService
import net.weero.measix.pilot.service.ConversationDeletionReceipt
import net.weero.measix.pilot.service.ConversationContinuation
import net.weero.measix.pilot.service.ConversationOpenRequest
import java.time.Instant
import kotlin.uuid.Uuid

class HistoryVMTest {
    @Test fun sortReadFailureIsDiagnosedAndRetriedByHistoryAndDrawer() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val selection = RealmSelection(RealmAccess.Personal, 1)
            val query = mockk<ConversationQueryService>()
            every { query.observeCurrentSelection() } returns flowOf(selection)
            var failRead = true
            every { query.observeSortOrder() } answers { kotlinx.coroutines.flow.flow {
                if (failRead) throw java.io.IOException("sort read failed", IllegalStateException("damaged preference"))
                emit(ConversationSortOrder.CREATED)
            } }
            every { query.conversationActivities() } returns flowOf(emptyMap())
            every { query.conversationsInRealm(selection, ConversationSortOrder.CREATED) } returns flowOf(Result.success(emptyList()))
            val configuration = mockk<ConfigurationQueryService>()
            every { configuration.observeAssistantCatalog() } returns flowOf(AssistantCatalogReadState.Loading)
            every { configuration.observeWebDavConfiguration() } returns flowOf(net.weero.measix.pilot.service.WebDavConfigurationReadState.Loading)
            val history = HistoryVM(query, configuration, mockk())
            val drawer = net.weero.measix.pilot.ui.pages.chat.ChatDrawerVM(mockk(), configuration, query, mockk(), androidx.lifecycle.SavedStateHandle())
            store.put("history", history)
            store.put("drawer", drawer)
            runCurrent()
            for (diagnostic in listOf(history.readFailure.value, drawer.sortFailure.value)) {
                org.junit.Assert.assertTrue(diagnostic.orEmpty().contains("sort read failed"))
                org.junit.Assert.assertTrue(diagnostic.orEmpty().contains("damaged preference"))
            }
            org.junit.Assert.assertNull(history.sortOrder.value)
            org.junit.Assert.assertNull(drawer.sortOrder.value)
            io.mockk.verify(exactly = 0) { query.conversationsInRealm(any(), any()) }
            failRead = false
            history.retry()
            drawer.retryFolders()
            runCurrent()
            assertEquals(ConversationSortOrder.CREATED, history.sortOrder.value)
            assertEquals(ConversationSortOrder.CREATED, drawer.sortOrder.value)
            org.junit.Assert.assertNull(history.readFailure.value)
            org.junit.Assert.assertNull(drawer.sortFailure.value)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `history waits for persisted sort instead of querying an initial placeholder`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val selection = RealmSelection(RealmAccess.Personal, 1)
            val sort = kotlinx.coroutines.flow.MutableSharedFlow<ConversationSortOrder>()
            val query = mockk<ConversationQueryService>()
            every { query.observeCurrentSelection() } returns flowOf(selection)
            every { query.observeSortOrder() } returns sort
            every { query.conversationsInRealm(selection, ConversationSortOrder.CREATED) } returns flowOf(Result.success(emptyList()))
            val configuration = mockk<ConfigurationQueryService>()
            every { configuration.observeAssistantCatalog() } returns flowOf(AssistantCatalogReadState.Loading)
            val vm = HistoryVM(query, configuration, mockk())
            store.put("history", vm)
            runCurrent()
            org.junit.Assert.assertNull(vm.sortOrder.value)
            io.mockk.verify(exactly = 0) { query.conversationsInRealm(any(), any()) }
            sort.emit(ConversationSortOrder.CREATED)
            runCurrent()
            assertEquals(ConversationSortOrder.CREATED, vm.sortOrder.value)
            io.mockk.verify(exactly = 1) { query.conversationsInRealm(selection, ConversationSortOrder.CREATED) }
            io.mockk.verify(exactly = 0) { query.conversationsInRealm(selection, ConversationSortOrder.UPDATED) }
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `personal history survives missing assistant definitions and sort cancels old query`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val selection = RealmSelection(RealmAccess.Personal, 1)
            val selected = MutableStateFlow<RealmSelection?>(selection)
            val sort = MutableStateFlow(ConversationSortOrder.UPDATED)
            val removedAssistant = ConfigurationReference.random()
            val row = historyRow(selection, removedAssistant)
            val query = mockk<ConversationQueryService>()
            every { query.observeCurrentSelection() } returns selected
            every { query.observeSortOrder() } returns sort
            var oldClosed = false
            every { query.conversationsInRealm(selection, ConversationSortOrder.UPDATED) } returns kotlinx.coroutines.flow.flow {
                try { emit(Result.success(listOf(row))); kotlinx.coroutines.awaitCancellation() }
                finally { oldClosed = true }
            }
            every { query.conversationsInRealm(selection, ConversationSortOrder.CREATED) } returns flowOf(Result.success(listOf(row.copy(title = "created"))))
            val configuration = mockk<ConfigurationQueryService>()
            every { configuration.observeAssistantCatalog() } returns flowOf(AssistantCatalogReadState.Unavailable("definition removed by restore"))
            val vm = HistoryVM(query, configuration, mockk())
            store.put("history", vm)
            runCurrent()
            assertEquals(listOf(row), vm.conversations.value)
            sort.value = ConversationSortOrder.CREATED
            runCurrent()
            org.junit.Assert.assertTrue(oldClosed)
            assertEquals("created", vm.conversations.value.single().title)
            selected.value = null
            runCurrent()
            org.junit.Assert.assertTrue(vm.conversations.value.isEmpty())
            io.mockk.verify(exactly = 0) { query.conversationsOfAssistant(any(), any()) }
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `partial batch failure still repairs committed deleted routes once`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val selection = RealmSelection(RealmAccess.Personal, 1)
            val selected = MutableStateFlow<RealmSelection?>(selection)
            val assistant = ConfigurationReference.random()
            val row = historyRow(selection, assistant)
            val receipt = ConversationDeletionReceipt(row.id, assistant, selection)
            val application = mockk<ConversationApplicationService>()
            coEvery { application.deletionContinuation(receipt) } returns ConversationContinuation(
                ConversationOpenRequest.NewDraft(Uuid.random(), selection.access, assistant))
            coEvery { application.deleteConversations(any(), any()) } coAnswers {
                secondArg<suspend (ConversationDeletionReceipt) -> Unit>()(receipt)
                throw java.io.IOException("second deletion failed")
            }
            val vm = commandVm(selected, application)
            store.put("history", vm)
            vm.deleteConversations(listOf(row, historyRow(selection, assistant)))
            runCurrent()
            assertEquals(1, vm.deletedNavigation.value.size)
            org.junit.Assert.assertTrue(vm.commandFailure.value!!.contains("second deletion failed"))
            var navigations = 0
            val pending = vm.deletedNavigation.value.single()
            vm.applyDeletedNavigation(pending) { navigations++ }
            vm.applyDeletedNavigation(pending) { navigations++ }
            runCurrent()
            assertEquals(1, navigations)
            org.junit.Assert.assertTrue(vm.deletedNavigation.value.isEmpty())
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `late deletion result and failure cannot affect a newly selected realm`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val selection = RealmSelection(RealmAccess.Personal, 1)
            val selected = MutableStateFlow<RealmSelection?>(selection)
            val assistant = ConfigurationReference.random()
            val row = historyRow(selection, assistant)
            val pending = CompletableDeferred<Unit>()
            val application = mockk<ConversationApplicationService>()
            coEvery { application.deleteConversations(any(), any()) } coAnswers {
                pending.await()
                secondArg<suspend (ConversationDeletionReceipt) -> Unit>()(ConversationDeletionReceipt(row.id, assistant, selection))
                throw java.io.IOException("old operation failure")
            }
            val vm = commandVm(selected, application)
            store.put("history", vm)
            runCurrent()
            vm.deleteConversations(listOf(row)); runCurrent()
            selected.value = RealmSelection(RealmAccess.Personal, 2)
            runCurrent(); pending.complete(Unit); runCurrent()
            org.junit.Assert.assertNull(vm.commandFailure.value)
            org.junit.Assert.assertFalse(vm.continuationUnavailable.value)
            org.junit.Assert.assertTrue(vm.deletedNavigation.value.isEmpty())
            coVerify(exactly = 0) { application.deletionContinuation(any()) }
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `undo remains owned until explicit restore or dismissal`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val selection = RealmSelection(RealmAccess.Personal, 1)
            val selected = MutableStateFlow<RealmSelection?>(selection)
            val assistant = ConfigurationReference.random()
            val row = historyRow(selection, assistant)
            val receipt = ConversationDeletionReceipt(row.id, assistant, selection)
            val token = mockk<ConversationApplicationService.RestoreToken>(relaxed = true)
            every { token.selection } returns selection
            every { token.deletion } returns receipt
            val application = mockk<ConversationApplicationService>()
            coEvery { application.deleteForUndo(any()) } returns token
            coEvery { application.deletionContinuation(receipt) } returns ConversationContinuation(null, "assistant unavailable")
            coEvery { application.restore(token) } returns Unit
            val vm = commandVm(selected, application)
            store.put("history", vm)
            vm.deleteForUndo(row); runCurrent()
            assertEquals(token, vm.undo.value)
            org.junit.Assert.assertTrue(vm.continuationUnavailable.value)
            vm.restoreConversation(token); runCurrent()
            vm.restoreConversation(token); runCurrent()
            coVerify(exactly = 1) { application.restore(token) }
            org.junit.Assert.assertNull(vm.undo.value)
            store.clear()
            io.mockk.verify(exactly = 1) { token.close() }
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `accepted undo waits for another deletion without losing either token`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val selection = RealmSelection(RealmAccess.Personal, 1)
            val selected = MutableStateFlow<RealmSelection?>(selection)
            val assistant = ConfigurationReference.random()
            val rows = List(2) { historyRow(selection, assistant) }
            val tokens = rows.map { row ->
                mockk<ConversationApplicationService.RestoreToken>(relaxed = true).also {
                    every { it.selection } returns selection
                    every { it.deletion } returns ConversationDeletionReceipt(row.id, assistant, selection)
                }
            }
            val pending = CompletableDeferred<Unit>()
            val application = mockk<ConversationApplicationService>()
            coEvery { application.deleteForUndo(match { it.conversationId == rows[0].id }) } returns tokens[0]
            coEvery { application.deleteForUndo(match { it.conversationId == rows[1].id }) } coAnswers { pending.await(); tokens[1] }
            coEvery { application.deletionContinuation(any()) } returns ConversationContinuation(null, "assistant unavailable")
            coEvery { application.restore(tokens[0]) } returns Unit
            val vm = commandVm(selected, application)
            store.put("history", vm)
            vm.deleteForUndo(rows[0]); runCurrent()
            vm.deleteForUndo(rows[1]); runCurrent()
            vm.restoreConversation(tokens[0]); vm.restoreConversation(tokens[0]); runCurrent()
            org.junit.Assert.assertNull(vm.undo.value)
            org.junit.Assert.assertTrue(vm.running.value)
            coVerify(exactly = 0) { application.restore(any()) }
            io.mockk.verify(exactly = 0) { tokens[0].close() }
            pending.complete(Unit); runCurrent()
            coVerify(exactly = 1) { application.restore(tokens[0]) }
            io.mockk.verify(exactly = 1) { tokens[0].close() }
            assertEquals(tokens[1], vm.undo.value)
            org.junit.Assert.assertFalse(vm.running.value)
            store.clear()
            io.mockk.verify(exactly = 1) { tokens[1].close() }
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun `queued undo is discarded after a realm switch or page closure`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (closePage in listOf(false, true)) {
                val store = ViewModelStore()
                try {
                    val selection = RealmSelection(RealmAccess.Personal, 1)
                    val selected = MutableStateFlow<RealmSelection?>(selection)
                    val assistant = ConfigurationReference.random()
                    val row = historyRow(selection, assistant)
                    val token = mockk<ConversationApplicationService.RestoreToken>(relaxed = true)
                    every { token.selection } returns selection
                    every { token.deletion } returns ConversationDeletionReceipt(row.id, assistant, selection)
                    val pending = CompletableDeferred<Unit>()
                    val application = mockk<ConversationApplicationService>()
                    coEvery { application.deleteForUndo(any()) } returns token
                    coEvery { application.deletionContinuation(any()) } returns ConversationContinuation(null, "assistant unavailable")
                    coEvery { application.togglePin(any()) } coAnswers { pending.await() }
                    val vm = commandVm(selected, application)
                    store.put("history", vm)
                    vm.deleteForUndo(row); runCurrent()
                    vm.togglePinStatus(row); runCurrent()
                    vm.restoreConversation(token); runCurrent()
                    if (closePage) store.clear() else selected.value = RealmSelection(RealmAccess.Personal, 2)
                    runCurrent(); pending.complete(Unit); runCurrent()
                    coVerify(exactly = 0) { application.restore(any()) }
                    io.mockk.verify(exactly = 1) { token.close() }
                    org.junit.Assert.assertNull(vm.undo.value)
                    org.junit.Assert.assertFalse(vm.running.value)
                } finally { store.clear() }
            }
        } finally { Dispatchers.resetMain() }
    }

    private fun historyRow(selection: RealmSelection, assistant: ConfigurationReference) = ConversationSummary(
        Uuid.random(), assistant, "History", null, false, Instant.EPOCH, Instant.EPOCH, selection)

    private fun commandVm(selected: MutableStateFlow<RealmSelection?>, application: ConversationApplicationService): HistoryVM {
        val query = mockk<ConversationQueryService>()
        every { query.observeSortOrder() } returns flowOf(ConversationSortOrder.UPDATED)
        every { query.conversationsInRealm(any(), any()) } returns flowOf(Result.success(emptyList()))
        every { query.observeCurrentSelection() } returns selected
        val configuration = mockk<ConfigurationQueryService>()
        every { configuration.observeAssistantCatalog() } returns flowOf(AssistantCatalogReadState.Loading)
        return HistoryVM(query, configuration, application)
    }

    @Test fun `missing enterprise configuration reads realm history without choosing a personal assistant`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val scope = ConfigurationScope.Enterprise(EnterpriseAuthority("dep_history"), "user")
            val selection = RealmSelection(RealmAccess.Enterprise(scope, "session"), 1)
            val catalog = MutableStateFlow<AssistantCatalogReadState>(AssistantCatalogReadState.Available(
                AssistantCatalogUiModel(selection, ConfigurationSelection(null, null), emptyMap(), emptyList())))
            val configuration = mockk<ConfigurationQueryService>()
            every { configuration.observeAssistantCatalog() } returns catalog
            val conversations = mockk<ConversationQueryService>()
            every { conversations.observeSortOrder() } returns flowOf(ConversationSortOrder.UPDATED)
            val selected = MutableStateFlow<RealmSelection?>(selection)
            every { conversations.observeCurrentSelection() } returns selected
            val row = mockk<ConversationSummary>()
            every { conversations.conversationsInRealm(selection) } returns flowOf(Result.success(listOf(row)))
            val vm = HistoryVM(conversations, configuration, mockk())
            store.put("history", vm)
            runCurrent()
            assertEquals(listOf(row), vm.conversations.value)
            io.mockk.verify(exactly = 0) { conversations.conversationsOfAssistant(any()) }
            catalog.value = AssistantCatalogReadState.Unavailable("configuration unreadable")
            runCurrent()
            assertEquals(listOf(row), vm.conversations.value)
            selected.value = null
            runCurrent()
            assertEquals(emptyList<ConversationSummary>(), vm.conversations.value)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `history failure exposes cause and retry restores rows without recreating the page`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val assistant = ConfigurationReference.random()
            val selection = RealmSelection(RealmAccess.Personal, 1)
            val configuration = mockk<ConfigurationQueryService>()
            every { configuration.observeAssistantCatalog() } returns flowOf(AssistantCatalogReadState.Available(
                AssistantCatalogUiModel(selection, ConfigurationSelection(assistant, null), emptyMap(), emptyList())))
            val conversations = mockk<ConversationQueryService>()
            every { conversations.observeSortOrder() } returns flowOf(ConversationSortOrder.UPDATED)
            every { conversations.observeCurrentSelection() } returns flowOf(selection)
            val row = mockk<ConversationSummary>()
            var failed = true
            every { conversations.conversationsInRealm(selection) } answers {
                flowOf(if (failed) Result.failure(IllegalStateException("history unreadable", java.io.IOException("disk cause")))
                    else Result.success(listOf(row)))
            }
            val vm = HistoryVM(conversations, configuration, mockk())
            store.put("history", vm)
            runCurrent()
            org.junit.Assert.assertTrue(vm.conversations.value.isEmpty())
            org.junit.Assert.assertTrue(requireNotNull(vm.readFailure.value).contains("IllegalStateException: history unreadable"))
            org.junit.Assert.assertTrue(requireNotNull(vm.readFailure.value).contains("IOException: disk cause"))
            failed = false
            vm.retry()
            runCurrent()
            assertEquals(listOf(row), vm.conversations.value)
            org.junit.Assert.assertNull(vm.readFailure.value)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `unavailable assistant still reads authorized history and revoked realm clears it`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val scope = ConfigurationScope.Enterprise(EnterpriseAuthority("dep_history"), "user")
            val selection = RealmSelection(RealmAccess.Enterprise(scope, "session"), 1)
            val assistant = ConfigurationReference.random()
            val catalog = MutableStateFlow<AssistantCatalogReadState>(AssistantCatalogReadState.Available(AssistantCatalogUiModel(
                selection, ConfigurationSelection(assistant, ConfigurationUnavailableReason.USER_CATEGORY_NOT_ALLOWED),
                emptyMap(), emptyList(),
            )))
            val configuration = mockk<ConfigurationQueryService>()
            every { configuration.observeAssistantCatalog() } returns catalog
            val conversations = mockk<ConversationQueryService>()
            every { conversations.observeSortOrder() } returns flowOf(ConversationSortOrder.UPDATED)
            val selected = MutableStateFlow<RealmSelection?>(selection)
            every { conversations.observeCurrentSelection() } returns selected
            val row = mockk<ConversationSummary>()
            every { conversations.conversationsInRealm(selection) } returns flowOf(Result.success(listOf(row)))
            val vm = HistoryVM(conversations, configuration, mockk())
            store.put("history", vm)
            runCurrent()
            assertEquals(listOf(row), vm.conversations.value)
            catalog.value = AssistantCatalogReadState.Unavailable("revoked")
            selected.value = null
            runCurrent()
            assertEquals(emptyList<ConversationSummary>(), vm.conversations.value)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
