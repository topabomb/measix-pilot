package net.weero.measix.pilot.service

import net.weero.measix.pilot.data.enterprise.toCandidate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.configuration.ConfigurationResolver
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.datastore.DEFAULT_ASSISTANT_ID
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.UserSettingsDocument
import net.weero.measix.pilot.data.db.dao.ConversationDAO
import net.weero.measix.pilot.data.db.dao.FolderDAO
import net.weero.measix.pilot.data.db.entity.FolderEntity
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.FolderRepository
import net.weero.measix.pilot.service.runtime.*
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationFolderAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `queue projection exposes current item phases capabilities and pause reasons without persisting`() = runTest {
        fixture { f ->
            val access = f.directory().access
            val view = f.openRuntime(access, draft = false)
            val runtime = f.registry.requireRuntime(view.conversationId)
            val parts = listOf(me.rerere.ai.ui.UIMessagePart.Text("queued request"))
            val item = runtime.enqueueInput(access.selection, access.assistantId, parts, true, null)
            var latest: ConversationInputQueueUiModel? = null
            val observe = backgroundScope.launch { f.query.observeInputQueue(view).collect { latest = it } }
            val worker = kotlinx.coroutines.Job()
            val turnId = Uuid.random()
            try {
                runCurrent()
                assertEquals(1, requireNotNull(latest).queueCount)
                val waiting = requireNotNull(latest).items.single()
                assertEquals(item.id, waiting.id)
                assertEquals(item.version, waiting.version)
                assertEquals(parts, waiting.parts)
                assertEquals(QueuedInputUiPhase.WAITING, waiting.phase)
                assertTrue(waiting.canEdit)
                assertTrue(waiting.canRemove)
                assertTrue(f.query.queuedInputPreviews(view, item.id, item.version).isEmpty())
                for ((reason, displayed) in listOf(
                    InputQueuePauseReason.USER_STOP to InputQueuePauseUiReason.USER_STOP,
                    InputQueuePauseReason.REALM_CHANGED to InputQueuePauseUiReason.REALM_CHANGED,
                    InputQueuePauseReason.CONTEXT_CHANGED to InputQueuePauseUiReason.CONTEXT_CHANGED,
                    InputQueuePauseReason.REQUEST_FAILED to InputQueuePauseUiReason.REQUEST_FAILED,
                )) {
                    runtime.pauseInputQueue(reason)
                    runCurrent()
                    assertEquals(displayed, requireNotNull(latest).pauseReason)
                }
                val editing = runtime.beginQueuedInputEdit(item.id, item.version)
                runCurrent()
                assertEquals(QueuedInputUiPhase.EDITING, requireNotNull(latest).items.single().phase)
                assertFalse(requireNotNull(latest).items.single().canEdit)
                assertTrue(requireNotNull(latest).items.single().canRemove)
                runtime.cancelQueuedInputEdit(editing.id, editing.version, requireNotNull(editing.editToken))
                runtime.resumeInputQueue(access.selection, access.assistantId)
                val dispatch = requireNotNull(runtime.nextQueuedInputDispatch())
                assertNotNull(runtime.installQueuedInputWorker(dispatch, access.selection, turnId, worker))
                runCurrent()
                val submitting = requireNotNull(latest).items.single()
                assertEquals(QueuedInputUiPhase.SUBMITTING, submitting.phase)
                assertFalse(submitting.canEdit)
                assertFalse(submitting.canRemove)
                assertNull(requireNotNull(latest).pauseReason)
                runtime.settleQueuedInputSubmission(dispatch, committed = false)
                runCurrent()
                assertEquals(QueuedInputUiPhase.WAITING, requireNotNull(latest).items.single().phase)
                assertEquals(InputQueuePauseUiReason.REQUEST_FAILED, requireNotNull(latest).pauseReason)
                coVerify(exactly = 0) { f.repository.commit(any()) }
            } finally {
                worker.cancel()
                runtime.releaseTurnWorker(turnId, worker)
                runtime.clearInputQueueAfterStop()
                observe.cancel()
                view.close()
            }
        }
    }

    @Test fun `queue projection clears and cannot publish again through an old view after realm round trip`() = runTest {
        fixture { f ->
            val access = f.directory().access
            val view = f.openRuntime(access, draft = false)
            val runtime = f.registry.requireRuntime(view.conversationId)
            runtime.enqueueInput(access.selection, access.assistantId, listOf(me.rerere.ai.ui.UIMessagePart.Text("private")), true, null)
            var latest: ConversationInputQueueUiModel? = null
            val job = backgroundScope.launch { f.query.observeInputQueue(view).collect { latest = it } }
            try {
                runCurrent()
                assertEquals(1, requireNotNull(latest).queueCount)
                f.sessions.selectPersonalFixture()
                f.sessions.selectEnterpriseFixture()
                runCurrent()
                assertNull(latest)
                assertTrue(view.closed.value)
                runtime.pauseInputQueue(InputQueuePauseReason.USER_STOP)
                runCurrent()
                assertNull(latest)
                assertTrue(job.isActive)
            } finally { job.cancel(); runtime.clearInputQueueAfterStop(); view.close() }
        }
    }

    @Test fun `queued preview capability rejects removal version change and closed originating view`() = runTest {
        fixture { f ->
            val access = f.directory().access
            for (change in listOf("remove", "version", "close")) {
                val view = f.openRuntime(access, draft = false)
                val runtime = f.registry.requireRuntime(view.conversationId)
                val submission = mockk<ArtifactSubmission>(relaxed = true)
                val parts = listOf(me.rerere.ai.ui.UIMessagePart.Text("input $change"))
                val item = runtime.enqueueInput(access.selection, access.assistantId, parts, true, submission)
                var verifier: (suspend () -> Unit)? = null
                val expected = mapOf("attachment" to ArtifactInputPreview("image.png", null))
                coEvery { submission.describeInputs(parts, any()) } coAnswers {
                    verifier = secondArg()
                    requireNotNull(verifier).invoke()
                    expected
                }
                try {
                    val result = kotlinx.coroutines.withTimeout(2_000) { f.query.queuedInputPreviews(view, item.id, item.version) }
                    assertEquals(expected, result)
                    requireNotNull(verifier).invoke()
                    when (change) {
                        "remove" -> runtime.removeQueuedInput(item.id, item.version)
                        "version" -> runtime.beginQueuedInputEdit(item.id, item.version)
                        "close" -> view.close()
                    }
                    rejects<IllegalStateException> { requireNotNull(verifier).invoke() }
                    coVerify(exactly = 1) { submission.describeInputs(parts, any()) }
                } finally { runtime.clearInputQueueAfterStop(); view.close() }
            }
        }
    }

    @Test fun `queued preview refuses a result when item changes during the owner read`() = runTest {
        fixture { f ->
            val access = f.directory().access
            val view = f.openRuntime(access, draft = false)
            val runtime = f.registry.requireRuntime(view.conversationId)
            val submission = mockk<ArtifactSubmission>(relaxed = true)
            val parts = listOf(me.rerere.ai.ui.UIMessagePart.Text("input"))
            val item = runtime.enqueueInput(access.selection, access.assistantId, parts, true, submission)
            coEvery { submission.describeInputs(parts, any()) } coAnswers {
                secondArg<suspend () -> Unit>().invoke()
                runtime.beginQueuedInputEdit(item.id, item.version)
                mapOf("attachment" to ArtifactInputPreview("obsolete.png", null))
            }
            try {
                rejects<IllegalStateException> {
                    kotlinx.coroutines.withTimeout(2_000) { f.query.queuedInputPreviews(view, item.id, item.version) }
                }
            } finally { runtime.clearInputQueueAfterStop(); view.close() }
        }
    }

    @Test fun `a new view cannot expose queued input from a different enterprise session with the same scope`() = runTest {
        fixture { f ->
            val access = f.directory().access
            val view = f.openRuntime(access, draft = false)
            val runtime = f.registry.requireRuntime(view.conversationId)
            val enterprise = access.selection.access as RealmAccess.Enterprise
            val foreign = RealmSelection(RealmAccess.Enterprise(enterprise.scope, "another-session"), access.selection.revision)
            val submission = mockk<ArtifactSubmission>(relaxed = true)
            val item = runtime.enqueueInput(foreign, access.assistantId,
                listOf(me.rerere.ai.ui.UIMessagePart.Text("other session input")), true, submission)
            var exposed: ConversationInputQueueUiModel? = null
            try {
                rejects<IllegalStateException> {
                    f.query.observeInputQueue(view).map { exposed = it; it }.first { it != null }
                }
                assertNull(exposed)
                rejects<IllegalStateException> { f.query.queuedInputPreviews(view, item.id, item.version) }
                coVerify(exactly = 0) { submission.describeInputs(any(), any()) }
            } finally { runtime.clearInputQueueAfterStop(); view.close() }
        }
    }

    @Test fun `draft location can be replaced or cleared without persisting or replacing its messages`() = runTest {
        fixture { f ->
            val access = f.directory().access
            f.application.createFolder(access, "first")
            f.application.createFolder(access, "replacement")
            val first = Uuid.parse(f.folders.value.first().id)
            val replacement = Uuid.parse(f.folders.value.last().id)
            val view = f.openRuntime(access, draft = true, folderId = first)
            try {
                val runtime = f.registry.requireRuntime(view.conversationId)
                val nodes = runtime.durable.nodes
                val target = ConversationAssistantTarget(view.commandTarget, access.assistantId)
                // The originally selected folder disappeared while the input was still a Draft.
                f.folderDao.deleteById(first.toString())
                f.application.updateDraftFolder(target, access, replacement)
                assertEquals(replacement, runtime.durable.header.folderId)
                assertEquals(nodes, runtime.durable.nodes)
                assertTrue(runtime.durable.header.newConversation)
                assertTrue(f.registry.isDraft(view.conversationId))
                assertFalse(f.headers.containsKey(view.conversationId))
                f.application.updateDraftFolder(target, access, null)
                assertNull(runtime.durable.header.folderId)
                assertEquals(nodes, runtime.durable.nodes)
                coVerify(exactly = 0) { f.repository.commit(any()) }
                coVerify(exactly = 0) { f.repository.getConversationSnapshotById(any()) }
            } finally { view.close() }
        }
    }

    @Test fun `draft location rejects ready conversation changed assistant and old selection without mutation`() = runTest {
        fixture { f ->
            val access = f.directory().access
            f.application.createFolder(access, "original")
            val folderId = Uuid.parse(f.folders.value.single().id)
            val ready = f.openRuntime(access, draft = false, folderId = folderId)
            val draft = f.openRuntime(access, draft = true, folderId = folderId)
            try {
                rejects<IllegalStateException> {
                    f.application.updateDraftFolder(ConversationAssistantTarget(ready.commandTarget, access.assistantId), access, null)
                }
                val staleAssistant = ConfigurationReference.random()
                rejects<IllegalStateException> {
                    f.application.updateDraftFolder(ConversationAssistantTarget(draft.commandTarget, staleAssistant),
                        ConversationFolderAccess(access.selection, staleAssistant), null)
                }
                rejects<IllegalStateException> {
                    f.application.updateDraftFolder(ConversationAssistantTarget(draft.commandTarget, access.assistantId),
                        ConversationFolderAccess(access.selection, staleAssistant), null)
                }
                f.sessions.selectPersonalFixture()
                f.sessions.selectEnterpriseFixture()
                rejects<EnterpriseConfigurationException> {
                    f.application.updateDraftFolder(ConversationAssistantTarget(draft.commandTarget, access.assistantId), access, null)
                }
                assertEquals(folderId, f.registry.requireRuntime(ready.conversationId).durable.header.folderId)
                assertEquals(folderId, f.registry.requireRuntime(draft.conversationId).durable.header.folderId)
                coVerify(exactly = 0) { f.repository.commit(any()) }
            } finally { draft.close(); ready.close() }
        }
    }

    @Test fun `draft folder query retains original directory authority and clears after realm round trip`() = runTest {
        fixture { f ->
            val access = f.directory().access
            f.application.createFolder(access, "visible")
            val view = f.openRuntime(access, draft = true)
            var latest: ConversationFolderDirectory? = null
            val job = backgroundScope.launch { f.query.foldersOfDraft(view).collect { latest = it } }
            try {
                runCurrent()
                assertEquals(access, requireNotNull(latest).access)
                assertEquals("visible", requireNotNull(latest).folders.single().name)
                f.sessions.selectPersonalFixture()
                f.sessions.selectEnterpriseFixture()
                runCurrent()
                assertNull(latest)
                assertTrue(view.closed.value)
                assertTrue(job.isActive)
            } finally { job.cancel(); view.close() }
        }
    }

    @Test fun `ready conversation has no draft folder directory`() = runTest {
        fixture { f ->
            val access = f.directory().access
            val view = f.openRuntime(access, draft = false)
            var latest: ConversationFolderDirectory? = ConversationFolderDirectory(access, emptyList())
            val job = backgroundScope.launch { f.query.foldersOfDraft(view).collect { latest = it } }
            try {
                runCurrent()
                assertNull(latest)
            } finally { job.cancel(); view.close() }
        }
    }

    @Test fun `draft directory read failure can retry on the same view without replacing input`() = runTest {
        fixture { f ->
            val access = f.directory().access
            val view = f.openRuntime(access, draft = true)
            val original = f.registry.requireRuntime(view.conversationId).durable
            try {
                every { f.folderDao.getFoldersOfAssistant(any(), any()) } returns kotlinx.coroutines.flow.flow {
                    throw IOException("draft folder read failed")
                }
                val failed = f.query.foldersOfDraft(view).first { it?.diagnostic != null }
                assertTrue(requireNotNull(failed?.diagnostic).contains("draft folder read failed"))
                every { f.folderDao.getFoldersOfAssistant(any(), any()) } returns kotlinx.coroutines.flow.flowOf(emptyList())
                val retried = f.query.foldersOfDraft(view).first { it != null }
                assertNull(requireNotNull(retried).diagnostic)
                assertEquals(access, retried.access)
                assertEquals(original, f.registry.requireRuntime(view.conversationId).durable)
                assertFalse(view.closed.value)
                coVerify(exactly = 0) { f.repository.commit(any()) }
            } finally { view.close() }
        }
    }

    @Test fun `create rename move and delete preserve enterprise ownership through actual command and repository paths`() = runTest {
        fixture { f ->
            val access = f.directory().access
            f.application.createFolder(access, "  work  ")
            val folder = f.folders.value.single()
            assertEquals(access.selection.access.scope, folder.scope)
            assertEquals(DEFAULT_ASSISTANT_ID.toString(), folder.assistantId)
            assertEquals("work", folder.name)
            val id = Uuid.parse(folder.id)
            f.application.renameFolder(access, id, "updated")
            val conversation = f.addConversation(access.selection.access.scope)
            f.application.moveToFolder(access, f.summary(conversation, access), id)
            assertEquals(id, f.headers.getValue(conversation).folderId)
            f.application.deleteFolder(access, id)
            assertNull(f.headers.getValue(conversation).folderId)
            assertTrue(f.folders.value.isEmpty())
            coVerify(exactly = 0) { f.repository.getConversationSnapshotById(any()) }
        }
    }

    @Test fun `old directory cannot act after an unobserved round trip or same identity re-enrollment`() = runTest {
        fixture { f ->
            val old = f.directory().access
            f.application.createFolder(old, "original")
            val id = Uuid.parse(f.folders.value.single().id)
            val conversation = f.addConversation(old.selection.access.scope)
            f.sessions.selectPersonalFixture()
            f.sessions.selectEnterpriseFixture()
            suspend fun rejectsAll() {
                rejects<EnterpriseConfigurationException> { f.application.createFolder(old, "new") }
                rejects<EnterpriseConfigurationException> { f.application.renameFolder(old, id, "changed") }
                rejects<EnterpriseConfigurationException> { f.application.moveToFolder(old, f.summary(conversation, old), id) }
                rejects<EnterpriseConfigurationException> { f.application.deleteFolder(old, id) }
            }
            rejectsAll()
            f.sessions.finishExit(f.sessions.beginExit(requireNotNull(f.sessions.captureExitRequest())))
            f.sessions.enrollFixture(exampleEnterprisePackage())
            rejectsAll()
            assertEquals("original", f.folders.value.single().name)
            assertNull(f.headers.getValue(conversation).folderId)
            coVerify(exactly = 0) { f.repository.commit(any()) }
        }
    }

    @Test fun `folder and conversation targets reject foreign principals assistants and child roots before writes`() = runTest {
        fixture { f ->
            val access = f.directory().access
            f.application.createFolder(access, "owned")
            val id = Uuid.parse(f.folders.value.single().id)
            val original = f.folders.value.single()
            for (foreign in listOf(
                original.copy(scope = ConfigurationScope.Personal),
                original.copy(assistantId = ConfigurationReference.random().toString()),
            )) {
                f.folders.value = listOf(foreign)
                rejects<IllegalStateException> { f.application.renameFolder(access, id, "changed") }
                rejects<IllegalStateException> { f.application.deleteFolder(access, id) }
                rejects<IllegalStateException> { f.application.moveToFolder(access, f.summary(f.addConversation(access.selection.access.scope), access), id) }
            }
            f.folders.value = listOf(original)
            val conversation = f.addConversation(access.selection.access.scope)
            val header = f.headers.getValue(conversation)
            for (foreign in listOf(
                header.copy(scope = ConfigurationScope.Personal),
                header.copy(assistantId = ConfigurationReference.random()),
                header.copy(parentConversationId = Uuid.random()),
            )) {
                f.headers[conversation] = foreign
                rejects<IllegalStateException> { f.application.moveToFolder(access, f.summary(conversation, access), id) }
            }
            coVerify(exactly = 0) { f.repository.commit(any()) }
            coVerify(exactly = 0) { f.repository.getConversationSnapshotById(any()) }
        }
    }

    @Test fun `failed detach keeps folder and retry processes remaining members without touching another realm`() = runTest {
        fixture { f ->
            val access = f.directory().access
            f.application.createFolder(access, "retry")
            val id = Uuid.parse(f.folders.value.single().id)
            val first = f.addConversation(access.selection.access.scope, id)
            val second = f.addConversation(access.selection.access.scope, id)
            val personal = f.addConversation(ConfigurationScope.Personal)
            f.failCommit = second
            rejects<IOException> { f.application.deleteFolder(access, id) }
            assertNull(f.headers.getValue(first).folderId)
            assertEquals(id, f.headers.getValue(second).folderId)
            assertEquals(1, f.folders.value.size)
            f.failCommit = null
            f.application.deleteFolder(access, id)
            assertNull(f.headers.getValue(second).folderId)
            assertTrue(f.folders.value.isEmpty())
            coVerify(exactly = 0) { f.repository.getConversationHeader(personal) }
        }
    }

    @Test fun `authorization covers the repository commit and a queued old command is rejected after switch`() = runTest {
        fixture { f ->
            val access = f.directory().access
            f.application.createFolder(access, "locked")
            val id = Uuid.parse(f.folders.value.single().id)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { f.folderDao.rename(id.toString(), any()) } coAnswers {
                entered.complete(Unit)
                release.await()
            }
            val rename = launch { f.application.renameFolder(access, id, "held") }
            entered.await()
            var switched = false
            val switch = launch { f.sessions.selectPersonalFixture(); switched = true }
            runCurrent()
            assertFalse(switched)
            release.complete(Unit)
            rename.join()
            switch.join()
            assertTrue(switched)
            rejects<EnterpriseConfigurationException> { f.application.deleteFolder(access, id) }
        }
    }

    @Test fun `folder query failure retains diagnosis clears on switch and reopening retries`() = runTest {
        fixture { f ->
            val target = f.directory().access
            val failure = IOException("folder database unreadable", IllegalStateException("folder cause"))
            every { f.folderDao.getFoldersOfAssistant(any(), any()) } returns kotlinx.coroutines.flow.flow { throw failure }
            var latest: ConversationFolderDirectory? = null
            val job = backgroundScope.launch { f.query.foldersOfAssistant(target).collect { latest = it } }
            runCurrent()
            assertTrue(requireNotNull(latest?.diagnostic).contains("IOException: folder database unreadable"))
            assertTrue(requireNotNull(latest?.diagnostic).contains("IllegalStateException: folder cause"))
            assertSame(failure, org.robolectric.shadows.ShadowLog.getLogsForTag("ConversationQuery").last().throwable)
            assertTrue(job.isActive)
            f.sessions.selectPersonalFixture()
            runCurrent()
            assertNull(latest)
            assertTrue(job.isActive)
            every { f.folderDao.getFoldersOfAssistant(any(), any()) } returns kotlinx.coroutines.flow.flowOf(emptyList())
            assertNull(f.directory().diagnostic)
            job.cancel()
        }
    }

    @Test fun `directory requires a new target after conflated round trip and does not revive old emissions`() = runTest {
        fixture { f ->
            var latest: ConversationFolderDirectory? = null
            val target = f.directory().access
            val job = backgroundScope.launch { f.query.foldersOfAssistant(target).collect { latest = it } }
            runCurrent()
            val original = requireNotNull(latest).access
            f.sessions.selectPersonalFixture()
            f.sessions.selectEnterpriseFixture()
            runCurrent()
            assertNull(latest)
            val newer = f.directory().access
            assertNotEquals(original, newer)
            rejects<EnterpriseConfigurationException> { f.application.createFolder(original, "stale") }
            f.application.createFolder(newer, "current")
            runCurrent()
            assertEquals("current", f.directory().folders.single().name)
            job.cancel()
        }
    }

    @Test fun `new directory cannot authorize an old conversation row`() = runTest {
        fixture { f ->
            val original = f.directory().access
            val id = f.addConversation(original.selection.access.scope)
            val row = f.summary(id, original)
            f.sessions.selectPersonalFixture()
            f.sessions.selectEnterpriseFixture()
            val newer = f.directory().access
            rejects<IllegalStateException> { f.application.moveToFolder(newer, row, null) }
            coVerify(exactly = 0) { f.repository.getConversationHeader(any()) }
            coVerify(exactly = 0) { f.repository.commit(any()) }
        }
    }

    @Test fun `active member prevents every detach even when preceded by an idle member`() = runTest {
        fixture { f ->
            val access = f.directory().access
            f.application.createFolder(access, "busy")
            val id = Uuid.parse(f.folders.value.single().id)
            val idle = f.addConversation(access.selection.access.scope, id)
            val busy = f.addConversation(access.selection.access.scope, id)
            val conversation = Conversation.ofId(busy, DEFAULT_ASSISTANT_ID).copy(scope = access.selection.access.scope, folderId = id)
            val runtime = f.registry.registerSnapshot(conversation.toSnapshot())
            val worker = kotlinx.coroutines.Job()
            try {
                runtime.installTurnWorker(Uuid.random(), worker)
                rejects<ConversationFolderBusyException> { f.application.deleteFolder(access, id) }
                assertEquals(id, f.headers.getValue(idle).folderId)
                assertEquals(1, f.folders.value.size)
                coVerify(exactly = 0) { f.repository.commit(any()) }
            } finally { worker.cancel() }
        }
    }

    @Test fun `policy revocation blocks creating a folder for the previously available user assistant`() = runTest {
        fixture { f ->
            val original = f.directory().access
            val packet = exampleEnterprisePackage()
            f.sessions.synchronize(original.selection.access as RealmAccess.Enterprise, packet.copy(configuration = packet.configuration.copy(
                generation = packet.configuration.generation + 1,
                policy = packet.configuration.policy.copy(allowLocalAssistants = false),
            )).toCandidate())
            rejects<IllegalStateException> { f.application.createFolder(original, "not allowed") }
            assertTrue(f.folders.value.isEmpty())
        }
    }

    private suspend fun TestScope.fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(this)
        try {
            f.sessions.enrollFixture(exampleEnterprisePackage())
            block(f)
        } finally { f.appScope.cancel() }
    }

    private inner class Fixture(test: TestScope) {
        val appScope = AppScope(StandardTestDispatcher(test.testScheduler))
        val sessions = EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.enterpriseTestStore(temporary.newFolder()), net.weero.measix.pilot.data.enterprise.EnterpriseDataResetStore(java.io.File(System.getProperty("java.io.tmpdir"), "enterprise-reset-${java.util.UUID.randomUUID()}")))
        val folderDao = mockk<FolderDAO>()
        val conversationDao = mockk<ConversationDAO>()
        val repository = mockk<ConversationRepository>()
        val settings = mockk<SettingsStore>()
        val folders = MutableStateFlow<List<FolderEntity>>(emptyList())
        val headers = linkedMapOf<Uuid, ConversationHeader>()
        var failCommit: Uuid? = null
        val folderRepository = FolderRepository(folderDao, conversationDao)
        val locks = ConversationOperationLocks()
        val registry = ConversationRuntimeRegistry(appScope, repository, locks)
        val gate = ApplicationRecoveryGate().apply { ready() }
        val coordinator = ConversationCommandCoordinator(registry, repository, gate, locks)
        val application = ConversationApplicationService(
            settings, repository, folderRepository, registry, coordinator, gate, mockk(), mockk(), mockk(),
            mockk(), mockk(), JsonInstant, mockk(), mockk(), sessions, mockk(),
        )
        val query = ConversationQueryService(repository, registry, folderRepository, mockk(), mockk(), sessions, gate, mockk(), mockk(), mockk())

        init {
            every { folderDao.getFoldersOfAssistant(any(), any()) } answers {
                val scope = firstArg<ConfigurationScope>()
                val assistant = secondArg<String>()
                folders.map { rows -> rows.filter { it.scope == scope && it.assistantId == assistant } }
            }
            coEvery { folderDao.getFolderById(any()) } answers { folders.value.find { it.id == firstArg<String>() } }
            coEvery { folderDao.insert(any()) } answers { folders.value += firstArg<FolderEntity>() }
            coEvery { folderDao.rename(any(), any()) } answers {
                val id = firstArg<String>()
                val name = secondArg<String>()
                folders.value = folders.value.map { if (it.id == id) it.copy(name = name) else it }
            }
            coEvery { folderDao.deleteById(any()) } answers { folders.value = folders.value.filterNot { it.id == firstArg<String>() } }
            coEvery { conversationDao.getIdsByFolder(any()) } answers {
                val id = firstArg<String>()
                headers.values.filter { it.folderId?.toString() == id }.map { it.id.toString() }
            }
            coEvery { repository.getConversationHeader(any()) } answers { headers[firstArg()] }
            coEvery { repository.commit(any()) } answers {
                val mutation = (firstArg<ConversationWrite>() as ConversationWrite.Mutate).mutation
                if (mutation.conversationId == failCommit) throw IOException("injected detach failure")
                val old = headers.getValue(mutation.conversationId)
                val folderId = when (val value = requireNotNull(mutation.headerPatch).folderId) {
                    OptionalFolderId.Clear -> null
                    OptionalFolderId.Keep -> old.folderId
                    is OptionalFolderId.SetTo -> value.id
                }
                headers[old.id] = old.copy(folderId = folderId)
                true
            }
            coEvery { settings.withResolvedConfiguration<Unit>(any(), any(), any()) } coAnswers {
                val configuration = ConfigurationResolver.resolve(UserSettingsDocument.empty(), firstArg(), secondArg())
                thirdArg<suspend (ResolvedConfiguration) -> Unit>()(configuration)
            }
        }

        fun summary(id: Uuid, access: ConversationFolderAccess): ConversationSummary {
            val header = headers.getValue(id)
            return ConversationSummary(id, header.assistantId, header.title, header.folderId, header.isPinned,
                java.time.Instant.ofEpochMilli(header.createAt), java.time.Instant.ofEpochMilli(header.updateAt), access.selection)
        }

        suspend fun directory(): ConversationFolderDirectory = query.foldersOfAssistant(ConversationFolderAccess(requireNotNull(sessions.observeSelectedRealmSelection().first()), DEFAULT_ASSISTANT_ID)).first { it != null }!!

        suspend fun openRuntime(access: ConversationFolderAccess, draft: Boolean, folderId: Uuid? = null): ConversationViewLease {
            val conversation = Conversation.ofId(Uuid.random(), access.assistantId, newConversation = draft)
                .copy(scope = access.selection.access.scope, folderId = folderId)
                .updateCurrentMessages(listOf(me.rerere.ai.ui.UIMessage.user("Keep the original input")))
            val runtime = if (draft) registry.installDraft(conversation) else {
                headers[conversation.id] = conversation.toSnapshot().header
                registry.registerSnapshot(conversation.toSnapshot())
            }
            val runtimeLease = registry.acquireRegisteredRuntime(conversation.id, runtime)
            return ConversationViewLease(conversation.id, access.selection.access, access.selection.revision,
                closeAction = runtimeLease::close)
        }

        fun addConversation(scope: ConfigurationScope, folderId: Uuid? = null): Uuid {
            val conversation = Conversation.ofId(Uuid.random(), DEFAULT_ASSISTANT_ID).copy(scope = scope, folderId = folderId)
            headers[conversation.id] = conversation.toSnapshot().header
            return conversation.id
        }
    }

    private suspend inline fun <reified T : Throwable> rejects(block: suspend () -> Unit) {
        try { block(); fail("Expected ${T::class.simpleName}") }
        catch (error: Throwable) { if (error !is T) throw error }
    }
}
