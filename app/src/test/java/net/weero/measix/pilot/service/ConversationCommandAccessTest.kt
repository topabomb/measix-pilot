package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.configuration.ConfigurationResolver
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.DEFAULT_ASSISTANT_ID
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.UserSettingsDocument
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.files.ArtifactRetentionLease
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.ContextMessageLocator
import net.weero.measix.pilot.data.model.ConversationContextSource
import net.weero.measix.pilot.data.model.HistorySummaryCheckpoint
import net.weero.measix.pilot.data.model.HistorySummaryCoveredMessage
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.model.historySummaryFingerprint
import net.weero.measix.pilot.data.model.messageOriginEntry
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.*
import net.weero.measix.pilot.service.subassistant.SubAssistantLifecycle
import net.weero.measix.pilot.service.turn.TurnFinalizer
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
class ConversationCommandAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `invalid summary limits reject before starting work or changing history`() = runTest {
        fixture { f ->
            val before = f.runtime.durable
            for ((tokens, retained) in listOf(100 to -1, 0 to 0)) {
                try {
                    f.application.compress(f.page.commandTarget, "", tokens, retained)
                    fail("Expected invalid summary limits")
                } catch (error: IllegalArgumentException) {
                    assertEquals(if (retained < 0) "invalid_history_summary_retention" else "invalid_history_summary_token_target", error.message)
                }
            }
            assertEquals(before, f.runtime.durable)
            io.mockk.verify(exactly = 0) { f.effects.launchCompression(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { f.repository.commit(any()) }
        }
    }

    @Test fun `regenerating a history summary rejects before truncating later history`() = runTest {
        fixture { f ->
            val base = f.runtime.durable
            val locators = base.nodes.map { ContextMessageLocator(it.id, it.currentMessage.id) }
            val summary = MessageNode.of(UIMessage.user("summary"))
            val checkpoint = HistorySummaryCheckpoint(covered = base.currentMessages().zip(locators).map { (message, locator) ->
                HistorySummaryCoveredMessage(locator, historySummaryFingerprint(message, base.modelContextEntries, locators))
            })
            val origin = messageOriginEntry(summary, ConversationContextSource.HistorySummary(
                model = null, prompt = "fixture", checkpoint = checkpoint,
            ))
            f.coordinator.executeOrThrow(f.rootId, InsertHistorySummaryCheckpoint(
                base.nodes, base.modelContextEntries, summary, origin, base.nodes.size,
            ))
            f.coordinator.executeOrThrow(f.rootId, AppendUserMessage(UIMessage.user("must remain after summary")))
            val before = f.runtime.durable
            f.turns.regenerateAtMessage(f.page.commandTarget, summary.currentMessage)
            f.runtime.awaitCurrentWorker()
            assertEquals(before.nodes, f.runtime.durable.nodes)
            assertEquals(before.modelContextEntries, f.runtime.durable.modelContextEntries)
            assertEquals(before.contextAdmissions, f.runtime.durable.contextAdmissions)
            assertEquals("must remain after summary", f.runtime.durable.currentMessages().last().toText())
            assertTrue(f.errors.errors.value.single().detail.contains("summary_cannot_be_sent_as_user_input"))
            coVerify(exactly = 0) { f.runner.run(any()) }
            assertNull(f.runtime.currentWorker())
        }
    }

    @Test fun `initial enterprise route consumes configured assistant and uses space entry when absent without persisting a draft`() = runTest {
        fixture { f ->
            coEvery { f.settings.lastConversation(f.scope) } returns null
            val packet = exampleEnterprisePackage()
            val initial = f.application.initialRequest(true) as InitialConversationRequest.Open
            assertEquals(packet.identity.reference(requireNotNull(packet.configuration.defaults.assistantId)),
                (initial.request as ConversationOpenRequest.NewDraft).assistantId)
            val access = f.sessions.captureSelectedRealmAccess() as RealmAccess.Enterprise
            f.sessions.synchronize(access, packet.copy(configuration = packet.configuration.copy(
                generation = packet.configuration.generation + 1,
                defaults = packet.configuration.defaults.copy(assistantId = null),
            )).toCandidate())
            assertEquals(InitialConversationRequest.SelectEnterpriseAssistant, f.application.initialRequest(true))
            coEvery { f.settings.lastConversation(f.scope) } returns f.rootId
            val resumed = (f.application.initialRequest(false) as InitialConversationRequest.Open).request as ConversationOpenRequest.OpenExisting
            assertEquals(f.rootId, resumed.id)
            assertEquals(access, resumed.access)
            assertEquals(f.rows.getValue(f.rootId).header.assistantId, resumed.startupResume?.assistantId)
            coVerify(exactly = 0) { f.repository.commit(any()) }
        }
    }

    @Test fun `committed deletion preserves its original assistant when preference cleanup fails`() = runTest {
        fixture { f ->
            val cleanup = java.io.IOException("preferences write failed")
            coEvery { f.settings.forgetConversation(f.scope, f.rootId) } throws cleanup
            val originalAssistant = f.rows.getValue(f.rootId).header.assistantId
            val receipt = f.application.delete(f.page.commandTarget)
            assertFalse(f.rows.containsKey(f.rootId))
            assertSame(cleanup, receipt.maintenanceFailure)
            assertEquals(originalAssistant, receipt.assistantId)
            val next = requireNotNull(f.application.deletionContinuation(receipt).request)
            assertEquals(originalAssistant, next.assistantId)
            assertEquals(f.page.access, next.access)
            assertNotEquals(f.rootId, next.id)
            assertFalse(f.rows.containsKey(next.id))
            coVerify(exactly = 1) { f.repository.deleteConversation(f.rootId) }
            coVerify(exactly = 0) { f.repository.insertConversationSnapshot(any()) }
        }
    }

    @Test fun `missing recent pointer falls back but a foreign or child pointer is rejected`() = runTest {
        fixture { f ->
            coEvery { f.settings.lastConversation(f.scope) } returns Uuid.random()
            assertTrue((f.application.initialRequest(false) as InitialConversationRequest.Open).request is ConversationOpenRequest.NewDraft)
            val foreign = f.put(ConfigurationScope.Personal)
            coEvery { f.settings.lastConversation(f.scope) } returns foreign
            rejects<IllegalStateException> { f.application.initialRequest(false) }
            val child = f.put(f.scope, f.rootId)
            coEvery { f.settings.lastConversation(f.scope) } returns child
            rejects<IllegalStateException> { f.application.initialRequest(false) }
            coEvery { f.settings.lastConversation(f.scope) } throws java.io.IOException("read failed")
            rejects<java.io.IOException> { f.application.initialRequest(false) }
        }
    }

    @Test fun `startup deletion race may resume only its verified original context`() = runTest {
        fixture { f ->
            coEvery { f.settings.lastConversation(f.scope) } returns f.rootId
            val request = (f.application.initialRequest(false) as InitialConversationRequest.Open).request as ConversationOpenRequest.OpenExisting
            f.application.delete(f.page.commandTarget)
            rejects<ConversationNotFoundException> { f.application.initialize(request) }
            val next = requireNotNull(f.application.startupContinuation(request).request)
            assertEquals(request.startupResume!!.assistantId, next.assistantId)
            assertEquals(request.access, next.access)
            rejects<IllegalArgumentException> { f.application.startupContinuation(request.copy(startupResume = null)) }
            f.sessions.switchRealm(RealmSwitchRequest(f.page.commandTarget.selection, RealmAccess.Personal)) {}
            rejects<EnterpriseConfigurationException> { f.application.startupContinuation(request) }
        }
    }

    @Test fun `restored request keeps its old Session while the same scope has valid replacement access`() = runTest {
        fixture { f ->
            val original = ConversationOpenRequest.OpenExisting(f.rootId, f.page.access)
            val restored = Json.decodeFromString<ConversationOpenRequest>(
                Json.encodeToString<ConversationOpenRequest>(original),
            )
            f.sessions.finishExit(f.sessions.beginExit(requireNotNull(f.sessions.captureExitRequest())))
            f.sessions.enrollFixture(exampleEnterprisePackage())
            val current = f.sessions.captureSelectedRealmAccess() as RealmAccess.Enterprise
            assertEquals(original.access.scope, current.scope)
            assertNotEquals(original.access, current)
            assertEquals(EnterpriseSessionPhase.READY, (f.sessions.state.value as EnterpriseState.Available).manifest.phase)
            assertTrue(f.sessions.observeRealmAccess(current).first())
            repeat(2) {
                try {
                    f.application.initialize(restored).close()
                    fail("The restored request must not acquire the replacement Session")
                } catch (error: EnterpriseConfigurationException) {
                    assertEquals("enterprise_data_access_unavailable", error.reason)
                }
                assertEquals(current, f.sessions.captureSelectedRealmAccess())
                assertEquals(original, restored)
            }
            val fresh = f.application.initialize(ConversationOpenRequest.OpenExisting(f.rootId, current))
            try {
                assertEquals(f.rootId, fresh.conversationId)
                assertEquals(current, fresh.access)
            } finally { fresh.close() }
            coEvery { f.settings.lastConversation(f.scope) } returns null
            val next = (f.application.initialRequest(true) as InitialConversationRequest.Open).request
            assertEquals(current, next.access)
            assertNotEquals(original.id, next.id)
            assertEquals(1, f.rows.size)
            coVerify(exactly = 0) { f.repository.commit(any()) }
            coVerify(exactly = 0) { f.artifactStore.materializeConfigurationMessages(any(), any(), any()) }
        }
    }

    @Test fun `personal request remains rejected after joining a valid selected enterprise space`() = runTest {
        fixture { f ->
            val personal = ConversationOpenRequest.NewDraft(Uuid.random(), RealmAccess.Personal, DEFAULT_ASSISTANT_ID)
            val current = f.sessions.captureSelectedRealmAccess()
            try {
                f.application.initialize(personal).close()
                fail("The personal request must not acquire enterprise access")
            } catch (error: EnterpriseConfigurationException) {
                assertEquals("enterprise_data_access_unavailable", error.reason)
            }
            assertEquals(current, f.sessions.captureSelectedRealmAccess())
            assertTrue(f.sessions.observeRealmAccess(current as RealmAccess.Enterprise).first())
            assertFalse(f.rows.containsKey(personal.id))
            coVerify(exactly = 0) { f.repository.commit(any()) }
            coVerify(exactly = 0) { f.artifactStore.materializeConfigurationMessages(any(), any(), any()) }
        }
    }

    @Test fun `saved assistant wins over enterprise default and invalid saved choice opens selection without rewriting it`() = runTest {
        fixture { f ->
            coEvery { f.settings.lastConversation(f.scope) } returns null
            val packet = exampleEnterprisePackage()
            var selected: ConfigurationReference = DEFAULT_ASSISTANT_ID
            coEvery { f.settings.withResolvedConfiguration<Any?>(any(), any(), any()) } coAnswers {
                val document = UserSettingsDocument.empty().withPersonalSettings(f.settings.userSettings.value)
                val preferences = document.preferences.withSelections(f.scope,
                    net.weero.measix.pilot.data.datastore.ResourceSelections(assistantId = selected))
                thirdArg<suspend (ResolvedConfiguration) -> Any?>()(ConfigurationResolver.resolve(
                    document.copy(preferences = preferences), f.scope, f.sessions.state.value))
            }
            val initial = f.application.initialRequest(true) as InitialConversationRequest.Open
            assertEquals(selected, (initial.request as ConversationOpenRequest.NewDraft).assistantId)
            selected = packet.identity.reference("asd_missing")
            assertEquals(InitialConversationRequest.SelectEnterpriseAssistant, f.application.initialRequest(true))
            coVerify(exactly = 0) { f.repository.commit(any()) }
        }
    }

    @Test fun `old page commands reject a conflated selection round trip before reading or writing data`() = runTest {
        fixture { f ->
            val target = f.page.commandTarget
            f.sessions.selectPersonalFixture()
            f.sessions.selectEnterpriseFixture()
            val commands: List<suspend () -> Unit> = listOf(
                { f.application.updateTitle(target, "changed") },
                { f.application.generateTitle(target, true) },
                { f.application.compress(target, "", 100, 0) },
                { f.application.updateCustomSystemPrompt(ConversationAssistantTarget(target, DEFAULT_ASSISTANT_ID), "changed") },
                { f.application.updateModeInjectionIds(ConversationAssistantTarget(target, DEFAULT_ASSISTANT_ID), emptySet()) },
                { f.application.updateWorkspaceCwd(ConversationAssistantTarget(target, DEFAULT_ASSISTANT_ID), null, "changed") },
                { f.application.selectAssistantRequest(target.selection, DEFAULT_ASSISTANT_ID, false) },
                { f.application.togglePin(target) },
                { f.application.selectNode(target, Uuid.random(), 0) },
                { f.application.editMessage(target, Uuid.random(), listOf(UIMessagePart.Text("changed"))) },
                { f.application.moveToAssistant(ConversationAssistantTarget(target, DEFAULT_ASSISTANT_ID), DEFAULT_ASSISTANT_ID, false) },
                { f.application.stopGeneration(target) },
                { f.application.delete(target) },
                { f.application.deleteForUndo(target) },
                { f.application.deleteMessage(target, UIMessage.user("changed")) },
                { f.application.forkAtMessage(target, Uuid.random()) },
            )
            commands.forEach { rejects<EnterpriseConfigurationException>(it) }
            coVerify(exactly = 0) { f.repository.getConversationHeader(any()) }
            coVerify(exactly = 0) { f.repository.getConversationSnapshotById(any()) }
            coVerify(exactly = 0) { f.repository.commit(any()) }
            assertEquals(1, f.rows.size)
        }
    }

    @Test fun `manual header and message edits use the authorized root and close with their page`() = runTest {
        fixture { f ->
            f.configureAssistant(net.weero.measix.pilot.data.model.Assistant(id = DEFAULT_ASSISTANT_ID, allowConversationSystemPrompt = true))
            val target = f.page.commandTarget
            f.application.updateTitle(target, "renamed")
            f.application.updateCustomSystemPrompt(ConversationAssistantTarget(target, DEFAULT_ASSISTANT_ID), "system")
            f.application.updateWorkspaceCwd(ConversationAssistantTarget(target, DEFAULT_ASSISTANT_ID), null, "workspace")
            f.application.togglePin(target)
            val message = f.runtime.durable.nodes.single().currentMessage
            f.application.editMessage(target, message.id, listOf(UIMessagePart.Text("edited")))
            assertEquals("renamed", f.runtime.durable.header.title)
            assertEquals("system", f.runtime.durable.header.customSystemPrompt)
            assertEquals("workspace", f.runtime.durable.header.workspaceCwd)
            assertTrue(f.runtime.durable.header.isPinned)
            assertEquals("edited", f.runtime.durable.currentMessages().single().toText())
            f.page.close()
            rejects<IllegalStateException> { f.application.togglePin(target) }
            assertTrue(f.runtime.durable.header.isPinned)
        }
    }

    @Test fun `editing generated text preserves committed model identity`() = runTest {
        fixture { f ->
            f.configureAssistant(net.weero.measix.pilot.data.model.Assistant(id = DEFAULT_ASSISTANT_ID))
            val info = me.rerere.ai.ui.MessageModelInfo(ConfigurationReference.random(), "original-wire", "Original name")
            val turnId = Uuid.random()
            val worker = Job()
            f.runtime.installTurnWorker(turnId, worker)
            val handle = f.coordinator.startTurn(f.rootId, TurnTransition.buildStartTurnCommand(
                f.runtime.durable, turnId = turnId,
            ).copy(modelInfo = info))
            f.coordinator.executeOrThrow(f.rootId, FinalizeTurn(handle, null,
                TurnExecutionStatus.CANCELLED, "user_stop"))
            worker.complete()
            f.runtime.releaseTurnWorker(turnId, worker)
            val original = f.runtime.durable.nodes.last().currentMessage
            f.application.editMessage(f.page.commandTarget, original.id, listOf(UIMessagePart.Text("edited answer")))
            val node = f.runtime.durable.nodes.last()
            assertEquals(original, node.messages.first())
            assertEquals(info, node.currentMessage.modelInfo)
            assertEquals(info.id, node.currentMessage.modelId)
            assertEquals("edited answer", node.currentMessage.toText())
        }
    }

    @Test fun `assistant-dependent commands reject the former assistant without changing the current conversation`() = runTest {
        fixture { f ->
            val stale = ConversationAssistantTarget(f.page.commandTarget, ConfigurationReference.random())
            val before = f.runtime.durable
            rejects<IllegalStateException> { f.application.updateWorkspaceCwd(stale, null, "stale-directory") }
            rejects<IllegalStateException> { f.application.updateModeInjectionIds(stale, emptySet()) }
            rejects<IllegalStateException> { f.application.moveToAssistant(stale, DEFAULT_ASSISTANT_ID, false) }
            assertEquals(before, f.runtime.durable)
            coVerify(exactly = 0) { f.repository.commit(any()) }
        }
    }

    @Test fun `directory from a former workspace cannot overwrite the current workspace root`() = runTest {
        fixture { f ->
            val oldWorkspace = Uuid.random()
            val currentWorkspace = Uuid.random()
            val target = ConversationAssistantTarget(f.page.commandTarget, DEFAULT_ASSISTANT_ID)
            f.configureAssistant(net.weero.measix.pilot.data.model.Assistant(id = DEFAULT_ASSISTANT_ID, workspaceId = currentWorkspace))
            rejects<IllegalStateException> { f.application.updateWorkspaceCwd(target, oldWorkspace, "/workspace/old") }
            assertNull(f.runtime.durable.header.workspaceCwd)
            coVerify(exactly = 0) { f.repository.commit(any()) }
            f.application.updateWorkspaceCwd(target, currentWorkspace, "/workspace/current")
            assertEquals("/workspace/current", f.runtime.durable.header.workspaceCwd)
        }
    }

    @Test fun `closing conversation prompt permissions rejects late edits without replacing saved values`() = runTest {
        fixture { f ->
            val assistant = net.weero.measix.pilot.data.model.Assistant(id = DEFAULT_ASSISTANT_ID,
                allowConversationSystemPrompt = true, allowConversationPromptInjection = true)
            f.configureAssistant(assistant)
            val target = ConversationAssistantTarget(f.page.commandTarget, assistant.id)
            val selected = setOf(ConfigurationReference.random())
            f.application.updateCustomSystemPrompt(target, "accepted")
            f.application.updateModeInjectionIds(target, selected)
            f.configureAssistant(assistant.copy(allowConversationSystemPrompt = false, allowConversationPromptInjection = false))
            rejects<IllegalStateException> { f.application.updateCustomSystemPrompt(target, "late") }
            rejects<IllegalStateException> { f.application.updateModeInjectionIds(target, emptySet()) }
            assertEquals("accepted", f.runtime.durable.header.customSystemPrompt)
            assertEquals(selected, f.runtime.durable.header.modeInjectionIds)
        }
    }

    @Test fun `page closure while queued on the conversation lock prevents writes and stop capture`() = runTest {
        for (stopCommand in listOf(false, true)) fixture { f ->
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = launch {
                f.locks.withLock(f.rootId) { held.complete(Unit); release.await() }
            }
            held.await()
            val worker = Job()
            f.runtime.installTurnWorker(Uuid.random(), worker)
            try {
                val command = async {
                    rejects<IllegalStateException> {
                        if (stopCommand) f.application.stopGeneration(f.page.commandTarget)
                        else f.application.updateTitle(f.page.commandTarget, "closed")
                    }
                }
                runCurrent()
                f.page.close()
                release.complete(Unit)
                command.await()
                assertTrue(worker.isActive)
                coVerify(exactly = 0) { f.repository.commit(any()) }
                assertEquals(f.rows.getValue(f.rootId).header.title, f.runtime.durable.header.title)
            } finally { release.complete(Unit); worker.cancel(); holder.join() }
        }
    }

    @Test fun `foreign and child headers cannot be used as ordinary roots`() = runTest {
        fixture { f ->
            val foreign = f.put(ConfigurationScope.Personal)
            val child = f.put(f.scope, f.rootId)
            for (id in listOf(foreign, child)) {
                val target = ConversationCommandTarget(id, f.page.commandTarget.selection) {}
                rejects<IllegalStateException> { f.application.updateTitle(target, "changed") }
                rejects<IllegalStateException> { f.application.delete(target) }
            }
            coVerify(exactly = 0) { f.repository.getConversationSnapshotById(any()) }
            coVerify(exactly = 0) { f.repository.commit(any()) }
        }
    }

    @Test fun `managed prompt and unavailable assistant changes are rejected by the command owner`() = runTest {
        fixture { f ->
            val header = f.runtime.durable.header.copy(assistantId = ConfigurationReference.Enterprise(f.scope.authority, "ast_fixed"))
            f.registry.evictRuntime(f.rootId)
            f.rows[f.rootId] = f.rows.getValue(f.rootId).copy(header = header)
            f.runtime = f.registry.registerSnapshot(f.rows.getValue(f.rootId))
            rejects<IllegalStateException> { f.application.updateCustomSystemPrompt(ConversationAssistantTarget(f.page.commandTarget, f.runtime.durable.header.assistantId), "override") }
            rejects<IllegalStateException> { f.application.moveToAssistant(ConversationAssistantTarget(f.page.commandTarget, DEFAULT_ASSISTANT_ID), ConfigurationReference.random(), false) }
            coVerify(exactly = 0) { f.repository.commit(any()) }
        }
    }

    @Test fun `cancelled stop releases authorization locks while its captured worker finishes`() = runTest {
        fixture { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val worker = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
            }
            f.runtime.installTurnWorker(Uuid.random(), worker)
            val stop = launch { f.application.stopGeneration(f.page.commandTarget) }
            entered.await()
            f.sessions.selectPersonalFixture()
            stop.cancel()
            runCurrent()
            assertFalse(stop.isCompleted)
            release.complete(Unit)
            stop.join()
            assertTrue(stop.isCancelled)
            assertNull(f.runtime.currentWorker())
        }
    }

    @Test fun `stop pauses queued input and awaits a worker already retired from active ownership`() = runTest {
        fixture { f ->
            val item = f.runtime.enqueueInput(f.page.commandTarget.selection, DEFAULT_ASSISTANT_ID,
                listOf(UIMessagePart.Text("next")), true, null)
            val turnId = Uuid.random()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val worker = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) {
                    f.runtime.releaseTurnWorker(turnId)
                    entered.complete(Unit)
                    release.await()
                } }
            }
            f.runtime.installTurnWorker(turnId, worker)
            worker.cancel()
            entered.await()
            assertNull(f.runtime.currentWorker())
            val stop = async { f.application.stopGeneration(f.page.commandTarget) }
            runCurrent()
            assertFalse(stop.isCompleted)
            assertEquals(InputQueuePauseReason.USER_STOP, f.runtime.inputQueue.value.pauseReason)
            assertEquals(listOf(item), f.runtime.inputQueue.value.items)
            release.complete(Unit)
            stop.await()
            assertTrue(worker.isCompleted)
            assertEquals(listOf(item), f.runtime.inputQueue.value.items)
        }
    }

    @Test fun `realm switch pauses only original realm queue inside the session boundary`() = runTest {
        fixture { f ->
            f.runtime.enqueueInput(f.page.commandTarget.selection, DEFAULT_ASSISTANT_ID,
                listOf(UIMessagePart.Text("enterprise pending")), true, null)
            val personalId = f.put(ConfigurationScope.Personal)
            val personal = f.registry.registerSnapshot(f.rows.getValue(personalId))
            personal.enqueueInput(RealmSelection(RealmAccess.Personal, f.page.commandTarget.selection.revision),
                DEFAULT_ASSISTANT_ID, listOf(UIMessagePart.Text("personal pending")), true, null)
            f.sessions.switchRealm(RealmSwitchRequest(f.page.commandTarget.selection, RealmAccess.Personal)) { previous ->
                f.application.pauseInputQueuesForRealmSwitch(previous)
            }
            assertEquals(InputQueuePauseReason.REALM_CHANGED, f.runtime.inputQueue.value.pauseReason)
            assertEquals(1, f.runtime.inputQueue.value.items.size)
            assertNull(personal.inputQueue.value.pauseReason)
        }
    }

    @Test fun `reset stops residual enterprise worker before releasing queued resources and preserves personal queue`() = runTest {
        fixture { f ->
            val selection = f.page.commandTarget.selection
            val submission = mockk<ArtifactSubmission>(relaxed = true)
            f.runtime.enqueueInput(selection, DEFAULT_ASSISTANT_ID, listOf(UIMessagePart.Text("enterprise")), true, submission)
            val personalId = f.put(ConfigurationScope.Personal)
            val personal = f.registry.registerSnapshot(f.rows.getValue(personalId))
            personal.enqueueInput(RealmSelection(RealmAccess.Personal, selection.revision), DEFAULT_ASSISTANT_ID,
                listOf(UIMessagePart.Text("personal")), true, null)
            val token = f.sessions.beginExit(requireNotNull(f.sessions.captureExitRequest()))
            f.sessions.finishExit(token)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val worker = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
            }
            f.runtime.installTurnWorker(Uuid.random(), worker)
            val clearing = async { f.application.clearEnterpriseInputQueuesAfterResetStop() }
            entered.await()
            assertFalse(clearing.isCompleted)
            io.mockk.verify(exactly = 0) { submission.close() }
            release.complete(Unit)
            clearing.await()
            io.mockk.verify(exactly = 1) { submission.close() }
            assertTrue(f.runtime.inputQueue.value.items.isEmpty())
            assertEquals(1, personal.inputQueue.value.items.size)
            assertTrue(f.rows.containsKey(f.rootId))
        }
    }

    @Test fun `reset preview includes residual enterprise queues but discloses only original selected scope`() = runTest {
        fixture { f ->
            val selection = f.page.commandTarget.selection
            val item = f.runtime.enqueueInput(selection, DEFAULT_ASSISTANT_ID, listOf(UIMessagePart.Text("enterprise")), true, null)
            var preview: EnterpriseResetPreview? = null
            f.sessions.captureLocalDataResetAdmission { admission ->
                preview = EnterpriseResetPreview(EnterpriseDataResetRequest(Uuid.random(), EnterpriseDataResetMode.KEEP_HISTORY),
                    admission, f.application.captureEnterpriseResetInputs())
            }
            assertEquals(1, requireNotNull(preview).pendingInputCount)
            f.sessions.selectPersonalFixture()
            f.sessions.captureLocalDataResetAdmission { admission ->
                preview = EnterpriseResetPreview(EnterpriseDataResetRequest(Uuid.random(), EnterpriseDataResetMode.KEEP_HISTORY),
                    admission, f.application.captureEnterpriseResetInputs())
            }
            val personalPreview = requireNotNull(preview)
            assertNull(personalPreview.pendingInputCount)
            assertEquals(1, personalPreview.queues.values.sumOf { it.size })
            val editing = f.runtime.beginQueuedInputEdit(item.id, item.version)
            f.runtime.replaceQueuedInputContents(editing.id, editing.version, requireNotNull(editing.editToken),
                listOf(UIMessagePart.Text("new content")), null)
            assertEquals("enterprise_pending_inputs_changed", runCatching {
                f.sessions.acceptLocalDataResetAdmission(personalPreview.admission) {
                    f.application.requireEnterpriseResetInputs(personalPreview)
                }
            }.exceptionOrNull()?.message)
        }
    }

    @Test fun `shared assistant deletion counts only current realm but confirms every affected queue`() = runTest {
        fixture { f ->
            val selection = f.page.commandTarget.selection
            f.runtime.enqueueInput(selection, DEFAULT_ASSISTANT_ID, listOf(UIMessagePart.Text("enterprise")), true, null)
            val personalId = f.put(ConfigurationScope.Personal)
            val personal = f.registry.registerSnapshot(f.rows.getValue(personalId))
            val personalSelection = RealmSelection(RealmAccess.Personal, selection.revision)
            personal.enqueueInput(personalSelection, DEFAULT_ASSISTANT_ID, listOf(UIMessagePart.Text("personal")), true, null)
            val preview = f.sessions.withSelectedRealmSelection(selection) {
                AssistantDeletionPreview(DEFAULT_ASSISTANT_ID, selection,
                    f.application.captureAssistantDeletionInputs(DEFAULT_ASSISTANT_ID))
            }
            assertEquals(1, preview.pendingInputCount)
            assertEquals(2, preview.queues.values.sumOf { it.size })
            assertEquals("assistant_pending_inputs_confirmation_required", runCatching {
                f.sessions.withSelectedRealmSelection(selection) {
                    f.application.requireAssistantDeletionInputs(DEFAULT_ASSISTANT_ID, null)
                }
            }.exceptionOrNull()?.message)
            personal.enqueueInput(personalSelection, DEFAULT_ASSISTANT_ID, listOf(UIMessagePart.Text("new hidden input")), true, null)
            assertEquals("assistant_pending_inputs_changed", runCatching {
                f.sessions.withSelectedRealmSelection(selection) {
                    f.application.requireAssistantDeletionInputs(DEFAULT_ASSISTANT_ID, preview)
                }
            }.exceptionOrNull()?.message)
            assertEquals(1, preview.pendingInputCount)
        }
    }

    @Test fun `shared assistant deletion rejects edited contents and accepts withdrawn items`() = runTest {
        fixture { f ->
            val selection = f.page.commandTarget.selection
            val item = f.runtime.enqueueInput(selection, DEFAULT_ASSISTANT_ID, listOf(UIMessagePart.Text("before")), true, null)
            val preview = f.sessions.withSelectedRealmSelection(selection) {
                AssistantDeletionPreview(DEFAULT_ASSISTANT_ID, selection,
                    f.application.captureAssistantDeletionInputs(DEFAULT_ASSISTANT_ID))
            }
            val editing = f.runtime.beginQueuedInputEdit(item.id, item.version)
            f.runtime.replaceQueuedInputContents(editing.id, editing.version, requireNotNull(editing.editToken),
                listOf(UIMessagePart.Text("after")), null)
            assertEquals("assistant_pending_inputs_changed", runCatching {
                f.sessions.withSelectedRealmSelection(selection) {
                    f.application.requireAssistantDeletionInputs(DEFAULT_ASSISTANT_ID, preview)
                }
            }.exceptionOrNull()?.message)
            val replaced = f.runtime.inputQueue.value.items.single()
            f.runtime.removeQueuedInput(replaced.id, replaced.version)
            f.sessions.withSelectedRealmSelection(selection) {
                f.application.requireAssistantDeletionInputs(DEFAULT_ASSISTANT_ID, preview)
            }
            assertTrue(f.runtime.inputQueue.value.items.isEmpty())
        }
    }

    @Test fun `enterprise exit preview discloses only selected enterprise queue count`() = runTest {
        fixture { f ->
            f.runtime.enqueueInput(f.page.commandTarget.selection, DEFAULT_ASSISTANT_ID,
                listOf(UIMessagePart.Text("enterprise pending")), true, null)
            var preview: EnterpriseExitPreview? = null
            f.sessions.captureExitRequest { request ->
                preview = EnterpriseExitPreview(request, f.application.captureEnterpriseExitInputs(request.access))
            }
            assertEquals(1, requireNotNull(preview).pendingInputCount)
            f.sessions.selectPersonalFixture()
            f.sessions.captureExitRequest { request ->
                preview = EnterpriseExitPreview(request, f.application.captureEnterpriseExitInputs(request.access))
            }
            assertNull(requireNotNull(preview).pendingInputCount)
            assertEquals(1, requireNotNull(preview).queues.values.sumOf { it.size })
        }
    }

    @Test fun `enterprise exit confirmation rejects added or edited input before closing`() = runTest {
        for (edit in listOf(false, true)) fixture { f ->
            val item = f.runtime.enqueueInput(f.page.commandTarget.selection, DEFAULT_ASSISTANT_ID,
                listOf(UIMessagePart.Text("reviewed")), true, null)
            var preview: EnterpriseExitPreview? = null
            f.sessions.captureExitRequest { request ->
                preview = EnterpriseExitPreview(request, f.application.captureEnterpriseExitInputs(request.access))
            }
            if (edit) {
                val editing = f.runtime.beginQueuedInputEdit(item.id, item.version)
                f.runtime.replaceQueuedInputContents(editing.id, editing.version, requireNotNull(editing.editToken),
                    listOf(UIMessagePart.Text("new content")), null)
            } else f.runtime.enqueueInput(f.page.commandTarget.selection, DEFAULT_ASSISTANT_ID,
                listOf(UIMessagePart.Text("new item")), true, null)
            val approved = requireNotNull(preview)
            assertEquals("enterprise_pending_inputs_changed", runCatching {
                f.sessions.beginExit(approved.request) { f.application.requireEnterpriseExitInputs(approved) }
            }.exceptionOrNull()?.message)
            assertEquals(EnterpriseSessionPhase.READY,
                (f.sessions.state.value as EnterpriseState.Available).manifest.phase)
            assertTrue(f.runtime.inputQueue.value.items.isNotEmpty())
        }
    }

    @Test fun `enterprise exit approval may narrow after pending input withdrawal`() = runTest {
        fixture { f ->
            val item = f.runtime.enqueueInput(f.page.commandTarget.selection, DEFAULT_ASSISTANT_ID,
                listOf(UIMessagePart.Text("reviewed")), true, null)
            var preview: EnterpriseExitPreview? = null
            f.sessions.captureExitRequest { request ->
                preview = EnterpriseExitPreview(request, f.application.captureEnterpriseExitInputs(request.access))
            }
            f.runtime.removeQueuedInput(item.id, item.version)
            val approved = requireNotNull(preview)
            f.sessions.beginExit(approved.request) { f.application.requireEnterpriseExitInputs(approved) }
            assertEquals(EnterpriseSessionPhase.CLOSING,
                (f.sessions.state.value as EnterpriseState.Available).manifest.phase)
        }
    }

    @Test fun `enterprise stop releases queued attachments only after request worker exits`() = runTest {
        fixture { f ->
            val submission = mockk<ArtifactSubmission>(relaxed = true)
            f.runtime.enqueueInput(f.page.commandTarget.selection, DEFAULT_ASSISTANT_ID,
                listOf(UIMessagePart.Text("queued")), true, submission)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val worker = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
            }
            f.runtime.installTurnWorker(Uuid.random(), worker)
            val token = f.sessions.beginExit(requireNotNull(f.sessions.captureExitRequest()))
            val stop = async { f.application.stopEnterpriseWork(token) }
            entered.await()
            io.mockk.verify(exactly = 0) { submission.close() }
            assertFalse(stop.isCompleted)
            release.complete(Unit)
            stop.await()
            io.mockk.verify(exactly = 1) { submission.close() }
            assertTrue(f.runtime.inputQueue.value.items.isEmpty())
            f.application.requireEnterpriseStopped(token)
        }
    }

    @Test fun `deleting idle conversation closes its pending queue before runtime eviction`() = runTest {
        fixture { f ->
            val submission = mockk<ArtifactSubmission>(relaxed = true)
            f.runtime.enqueueInput(f.page.commandTarget.selection, DEFAULT_ASSISTANT_ID,
                listOf(UIMessagePart.Text("not sent")), true, submission)
            val preview = f.application.prepareDeletion(listOf(f.page.commandTarget))
            assertEquals(1, preview.pendingInputCount)
            f.application.delete(f.page.commandTarget, preview)
            io.mockk.verify(exactly = 1) { submission.close() }
            assertTrue(f.runtime.inputQueue.value.items.isEmpty())
            assertNull(f.registry.findRuntime(f.rootId))
            assertFalse(f.rows.containsKey(f.rootId))
        }
    }

    @Test fun `pending deletion requires confirmation before stopping an active request`() = runTest {
        fixture { f ->
            val submission = mockk<ArtifactSubmission>(relaxed = true)
            f.runtime.enqueueInput(f.page.commandTarget.selection, DEFAULT_ASSISTANT_ID,
                listOf(UIMessagePart.Text("pending")), true, submission)
            val before = f.runtime.inputQueue.value
            val worker = Job()
            f.runtime.installTurnWorker(Uuid.random(), worker)
            try {
                assertEquals("conversation_pending_inputs_confirmation_required", runCatching {
                    f.application.delete(f.page.commandTarget)
                }.exceptionOrNull()?.message)
                assertFalse(worker.isCancelled)
                assertEquals(before, f.runtime.inputQueue.value)
                io.mockk.verify(exactly = 0) { submission.close() }
                coVerify(exactly = 0) { f.repository.deleteConversation(any()) }
            } finally { worker.cancelAndJoin() }
        }
    }

    @Test fun `confirmation cannot consume newly queued or edited input`() = runTest {
        for (edit in listOf(false, true)) fixture { f ->
            val item = f.runtime.enqueueInput(f.page.commandTarget.selection, DEFAULT_ASSISTANT_ID,
                listOf(UIMessagePart.Text("reviewed")), true, null)
            val preview = f.application.prepareDeletion(listOf(f.page.commandTarget))
            if (edit) {
                val editing = f.runtime.beginQueuedInputEdit(item.id, item.version)
                f.runtime.replaceQueuedInputContents(editing.id, editing.version, requireNotNull(editing.editToken),
                    listOf(UIMessagePart.Text("not reviewed")), null)
            } else f.runtime.enqueueInput(f.page.commandTarget.selection, DEFAULT_ASSISTANT_ID,
                listOf(UIMessagePart.Text("not reviewed")), true, null)
            val before = f.runtime.inputQueue.value
            assertEquals("conversation_pending_inputs_changed", runCatching {
                f.application.delete(f.page.commandTarget, preview)
            }.exceptionOrNull()?.message)
            assertEquals(before, f.runtime.inputQueue.value)
            assertTrue(f.rows.containsKey(f.rootId))
        }
    }

    @Test fun `new input while deletion awaits stopped worker rejects final deletion`() = runTest {
        fixture { f ->
            f.runtime.enqueueInput(f.page.commandTarget.selection, DEFAULT_ASSISTANT_ID,
                listOf(UIMessagePart.Text("reviewed")), true, null)
            val preview = f.application.prepareDeletion(listOf(f.page.commandTarget))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val worker = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
            }
            f.runtime.installTurnWorker(Uuid.random(), worker)
            val deleting = async { runCatching { f.application.delete(f.page.commandTarget, preview) } }
            entered.await()
            try {
                f.runtime.enqueueInput(f.page.commandTarget.selection, DEFAULT_ASSISTANT_ID,
                    listOf(UIMessagePart.Text("arrived while joining")), true, null)
            } finally { release.complete(Unit) }
            assertEquals("conversation_pending_inputs_changed", deleting.await().exceptionOrNull()?.message)
            assertTrue(f.rows.containsKey(f.rootId))
            assertEquals(2, f.runtime.inputQueue.value.items.size)
            coVerify(exactly = 0) { f.repository.deleteConversation(any()) }
        }
    }

    @Test fun `withdrawal narrows deletion confirmation and same conversation new target cannot reuse it`() = runTest {
        fixture { f ->
            val item = f.runtime.enqueueInput(f.page.commandTarget.selection, DEFAULT_ASSISTANT_ID,
                listOf(UIMessagePart.Text("reviewed")), true, null)
            val preview = f.application.prepareDeletion(listOf(f.page.commandTarget))
            val reopened = ConversationViewLease(f.rootId, f.page.access, f.page.selectionRevision) {}
            try {
                assertEquals("conversation_deletion_preview_mismatch", runCatching {
                    f.application.delete(reopened.commandTarget, preview)
                }.exceptionOrNull()?.message)
            } finally { reopened.close() }
            f.runtime.removeQueuedInput(item.id, item.version)
            f.application.delete(f.page.commandTarget, preview)
            assertFalse(f.rows.containsKey(f.rootId))
        }
    }

    @Test fun `batch rejects unconfirmed later target before stopping or deleting earlier target`() = runTest {
        fixture { f ->
            val otherId = f.put(f.scope)
            val other = f.registry.registerSnapshot(f.rows.getValue(otherId))
            val otherPage = ConversationViewLease(otherId, f.page.access, f.page.selectionRevision) {}
            other.enqueueInput(otherPage.commandTarget.selection, DEFAULT_ASSISTANT_ID,
                listOf(UIMessagePart.Text("pending in second chat")), true, null)
            val worker = Job()
            f.runtime.installTurnWorker(Uuid.random(), worker)
            try {
                assertEquals("conversation_pending_inputs_confirmation_required", runCatching {
                    f.application.deleteConversations(listOf(f.page.commandTarget, otherPage.commandTarget))
                }.exceptionOrNull()?.message)
                assertFalse(worker.isCancelled)
                assertTrue(f.rows.keys.containsAll(listOf(f.rootId, otherId)))
                coVerify(exactly = 0) { f.repository.deleteConversation(any()) }
            } finally { otherPage.close(); worker.cancelAndJoin() }
        }
    }

    @Test fun `queued USER already committed is not counted as unsubmitted input`() = runTest {
        fixture { f ->
            val item = f.runtime.enqueueInput(f.page.commandTarget.selection, DEFAULT_ASSISTANT_ID,
                listOf(UIMessagePart.Text("already submitted")), false, null)
            f.coordinator.executeOrThrow(f.rootId, AppendUserMessage(UIMessage(
                id = item.userMessageId, role = me.rerere.ai.core.MessageRole.USER, parts = item.parts)))
            val preview = f.application.prepareDeletion(listOf(f.page.commandTarget))
            assertEquals(0, preview.pendingInputCount)
            f.application.delete(f.page.commandTarget)
            assertFalse(f.rows.containsKey(f.rootId))
        }
    }

    @Test fun `undo deletion also requires reviewed pending versions and never restores the queue`() = runTest {
        fixture { f ->
            f.runtime.enqueueInput(f.page.commandTarget.selection, DEFAULT_ASSISTANT_ID,
                listOf(UIMessagePart.Text("unsent")), true, null)
            assertEquals("conversation_pending_inputs_confirmation_required", runCatching {
                f.application.deleteForUndo(f.page.commandTarget)
            }.exceptionOrNull()?.message)
            val preview = f.application.prepareDeletion(listOf(f.page.commandTarget))
            val undo = f.application.deleteForUndo(f.page.commandTarget, preview)
            assertTrue(f.runtime.inputQueue.value.items.isEmpty())
            assertFalse(f.rows.containsKey(f.rootId))
            f.application.restore(undo)
            assertTrue(f.rows.containsKey(f.rootId))
            assertTrue(f.registry.findRuntime(f.rootId)?.inputQueue?.value?.items.orEmpty().isEmpty())
        }
    }

    @Test fun `failed deletion or undo retention preserves paused pending input and attachment ownership`() = runTest {
        for (undo in listOf(false, true)) fixture { f ->
            val submission = mockk<ArtifactSubmission>(relaxed = true)
            val queued = f.runtime.enqueueInput(f.page.commandTarget.selection, DEFAULT_ASSISTANT_ID,
                listOf(UIMessagePart.Text("retain on failure")), true, submission)
            val preview = f.application.prepareDeletion(listOf(f.page.commandTarget))
            val failure = java.io.IOException(if (undo) "retention failed" else "delete transaction failed")
            if (undo) coEvery { f.artifactStore.retainNodesForUndo(any(), any()) } throws failure
            else coEvery { f.repository.deleteConversation(any()) } throws failure
            val rejected = runCatching {
                if (undo) f.application.deleteForUndo(f.page.commandTarget, preview)
                else f.application.delete(f.page.commandTarget, preview)
            }.exceptionOrNull()
            assertEquals(failure.message, rejected?.message)
            assertTrue(f.rows.containsKey(f.rootId))
            assertSame(f.runtime, f.registry.findRuntime(f.rootId))
            assertEquals(listOf(queued), f.runtime.inputQueue.value.items)
            assertEquals(InputQueuePauseReason.USER_STOP, f.runtime.inputQueue.value.pauseReason)
            io.mockk.verify(exactly = 0) { submission.close() }
        }
    }

    @Test fun `undo transaction failure releases only undo retention and keeps queued attachments`() = runTest {
        fixture { f ->
            val submission = mockk<ArtifactSubmission>(relaxed = true)
            val queued = f.runtime.enqueueInput(f.page.commandTarget.selection, DEFAULT_ASSISTANT_ID,
                listOf(UIMessagePart.Text("still pending")), true, submission)
            val preview = f.application.prepareDeletion(listOf(f.page.commandTarget))
            coEvery { f.repository.deleteConversation(any()) } throws java.io.IOException("delete failed")
            assertTrue(runCatching { f.application.deleteForUndo(f.page.commandTarget, preview) }.isFailure)
            assertEquals(1, f.retentionReleased)
            assertEquals(listOf(queued), f.runtime.inputQueue.value.items)
            assertTrue(f.rows.containsKey(f.rootId))
            io.mockk.verify(exactly = 0) { submission.close() }
        }
    }

    @Test fun `deletion preview rejects mixed original realm selections without exposing counts`() = runTest {
        fixture { f ->
            val personalId = f.put(ConfigurationScope.Personal)
            val personal = ConversationViewLease(personalId, RealmAccess.Personal, f.page.selectionRevision) {}
            try {
                assertEquals("conversation_selection_mismatch", runCatching {
                    f.application.prepareDeletion(listOf(f.page.commandTarget, personal.commandTarget))
                }.exceptionOrNull()?.message)
            } finally { personal.close() }
        }
    }

    @Test fun `delete rechecks original authorization after joining and leaves the conversation when revoked`() = runTest {
        fixture { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val worker = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
            }
            f.runtime.installTurnWorker(Uuid.random(), worker)
            val deleting = async { rejects<EnterpriseConfigurationException> { f.application.delete(f.page.commandTarget) } }
            entered.await()
            f.sessions.selectPersonalFixture()
            release.complete(Unit)
            deleting.await()
            assertTrue(f.rows.containsKey(f.rootId))
            assertNull(f.runtime.currentWorker())
            coVerify(exactly = 0) { f.repository.deleteConversation(any()) }
        }
    }

    @Test fun `tree authorization rejects foreign lineage before reading its message payload`() = runTest {
        fixture { f ->
            val child = f.put(ConfigurationScope.Personal, f.rootId)
            rejects<IllegalStateException> { f.application.deleteForUndo(f.page.commandTarget) }
            rejects<IllegalStateException> { f.application.forkAtMessage(f.page.commandTarget, f.runtime.durable.nodes.single().currentMessage.id) }
            coVerify(exactly = 0) { f.repository.getChildConversationSnapshots(any()) }
            coVerify(exactly = 0) { f.repository.getConversationSnapshotById(child) }
            coVerify(exactly = 0) { f.repository.deleteConversation(any()) }
        }
    }

    @Test fun `committed deletion still evicts the original runtime when its caller cancels`() = runTest {
        fixture { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { f.repository.deleteConversation(f.rootId) } coAnswers {
                entered.complete(Unit)
                release.await()
                f.rows.remove(f.rootId)
                Unit
            }
            val deleting = launch { f.application.delete(f.page.commandTarget) }
            entered.await()
            deleting.cancel()
            release.complete(Unit)
            deleting.join()
            assertTrue(deleting.isCancelled)
            assertFalse(f.rows.containsKey(f.rootId))
            assertNull(f.registry.findRuntime(f.rootId))
        }
    }

    @Test fun `undo restores complete lineage once and concurrent discard cannot release its claimed retention`() = runTest {
        fixture { f ->
            val child = f.put(f.scope, f.rootId)
            val expected = f.rows.toMap()
            val token = f.application.deleteForUndo(f.page.commandTarget)
            f.page.close()
            assertTrue(f.rows.isEmpty())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { f.repository.insertConversationTree(any(), any()) } coAnswers {
                entered.complete(Unit)
                release.await()
                val root = firstArg<ConversationAggregateSnapshot>()
                f.rows[root.conversationId] = root
                secondArg<List<ConversationAggregateSnapshot>>().forEach { f.rows[it.conversationId] = it }
            }
            val restoring = launch { f.application.restore(token) }
            entered.await()
            f.application.discardRestoreToken(token)
            assertEquals(0, f.retentionReleased)
            rejects<IllegalStateException> { f.application.restore(token) }
            release.complete(Unit)
            restoring.join()
            assertEquals(expected, f.rows)
            assertEquals(f.rootId, f.rows.getValue(child).header.parentConversationId)
            assertEquals(1, f.retentionReleased)
            token.close()
            rejects<IllegalStateException> { f.application.restore(token) }
            assertEquals(1, f.retentionReleased)
        }
    }

    @Test fun `revoked undo cannot rebind and releases its retained artifacts on failure`() = runTest {
        fixture { f ->
            val token = f.application.deleteForUndo(f.page.commandTarget)
            f.sessions.finishExit(f.sessions.beginExit(requireNotNull(f.sessions.captureExitRequest())))
            f.sessions.enrollFixture(exampleEnterprisePackage())
            rejects<EnterpriseConfigurationException> { f.application.restore(token) }
            assertTrue(f.rows.isEmpty())
            assertEquals(1, f.retentionReleased)
            rejects<IllegalStateException> { f.application.restore(token) }
        }
    }

    @Test fun `undo rejects a replacement conversation instead of overwriting its identifier`() = runTest {
        fixture { f ->
            val token = f.application.deleteForUndo(f.page.commandTarget)
            val replacement = token.root.copy(header = token.root.header.copy(title = "replacement"))
            f.rows[f.rootId] = replacement
            rejects<ConversationCommandConflictException> { f.application.restore(token) }
            assertEquals(replacement, f.rows[f.rootId])
            assertEquals(1, f.retentionReleased)
        }
    }

    @Test fun `child answer requires the original root and pending Session`() = runTest {
        fixture { f ->
            val target = f.page.commandTarget
            val answer = f.runGate.registerPendingInteraction(f.rootId, target.selection.access, "run", "ask", Job())
            val otherRoot = ConversationCommandTarget(f.put(f.scope), target.selection) {}
            assertFalse(f.application.answerSubAssistant(otherRoot, "run", "ask", "foreign"))
            assertFalse(f.application.answerSubAssistant(target, "run", "old-ask", "stale"))
            assertFalse(answer.isCompleted)
            assertTrue(f.application.answerSubAssistant(target, "run", "ask", "accepted"))
            assertFalse(f.application.answerSubAssistant(target, "run", "ask", "duplicate"))
            assertEquals("accepted", answer.await())
            f.runGate.unregisterPendingInteraction("run", answer)
        }
    }

    @Test fun `relogin cannot answer the previous Session pending even from a new page`() = runTest {
        fixture { f ->
            val original = f.page.commandTarget
            val answer = f.runGate.registerPendingInteraction(f.rootId, original.selection.access, "run", "ask", Job())
            f.sessions.finishExit(f.sessions.beginExit(requireNotNull(f.sessions.captureExitRequest())))
            f.sessions.enrollFixture(exampleEnterprisePackage())
            rejects<EnterpriseConfigurationException> { f.application.answerSubAssistant(original, "run", "ask", "old page") }
            val selected = f.sessions.observeSelectedRealmSelection().first { it != null }!!
            val reopened = ConversationViewLease(f.rootId, selected.access, selected.revision) {}
            try {
                assertFalse(f.application.answerSubAssistant(reopened.commandTarget, "run", "ask", "new session"))
                assertFalse(answer.isCompleted)
            } finally { reopened.close(); f.runGate.unregisterPendingInteraction("run", answer) }
        }
    }

    @Test fun `switching back permits a fresh page to answer the same original background run`() = runTest {
        fixture { f ->
            val target = f.page.commandTarget
            val answer = f.runGate.registerPendingInteraction(f.rootId, target.selection.access, "run", "ask", Job())
            f.sessions.selectPersonalFixture()
            f.sessions.selectEnterpriseFixture()
            rejects<EnterpriseConfigurationException> { f.application.answerSubAssistant(target, "run", "ask", "old page") }
            val selected = f.sessions.observeSelectedRealmSelection().first { it != null }!!
            val reopened = ConversationViewLease(f.rootId, selected.access, selected.revision) {}
            try {
                assertTrue(f.application.answerSubAssistant(reopened.commandTarget, "run", "ask", "resumed"))
                assertEquals("resumed", answer.await())
            } finally { reopened.close(); f.runGate.unregisterPendingInteraction("run", answer) }
        }
    }

    @Test fun `closing or cancelling a queued child answer cannot complete its waiter`() = runTest {
        for (cancel in listOf(false, true)) fixture { f ->
            val target = f.page.commandTarget
            val answer = f.runGate.registerPendingInteraction(f.rootId, target.selection.access, "run", "ask", Job())
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val lockOwner = launch { f.locks.withLock(f.rootId) { entered.complete(Unit); release.await() } }
            entered.await()
            val command = launch {
                if (cancel) f.application.answerSubAssistant(target, "run", "ask", "queued")
                else rejects<IllegalStateException> { f.application.answerSubAssistant(target, "run", "ask", "closed") }
            }
            runCurrent()
            if (cancel) command.cancel() else f.page.close()
            release.complete(Unit)
            command.join()
            lockOwner.join()
            assertFalse(answer.isCompleted)
            f.runGate.unregisterPendingInteraction("run", answer)
        }
    }

    @Test fun `start rejects old selection and closed page before installing any worker`() = runTest {
        fixture { f ->
            val target = f.page.commandTarget
            f.sessions.selectPersonalFixture()
            f.sessions.selectEnterpriseFixture()
            rejects<EnterpriseConfigurationException> { f.turns.sendMessage(target, listOf(UIMessagePart.Text("late")), false) }
            assertNull(f.runtime.currentWorker())
            coVerify(exactly = 0) { f.repository.commit(any()) }
        }
        fixture { f ->
            f.page.close()
            rejects<IllegalStateException> { f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("closed")), false) }
            assertNull(f.runtime.currentWorker())
        }
    }

    @Test fun `accepted append survives page closure and selection switch in its original realm`() = runTest {
        fixture { f ->
            val receipt = requireNotNull(f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("accepted")), false))
            f.page.close()
            f.sessions.selectPersonalFixture()
            runCurrent()
            assertEquals(f.errors.errors.value.toString(), receipt.userMessageId, f.runtime.durable.currentMessages().last().id)
            assertEquals("accepted", f.runtime.durable.currentMessages().last().toText())
            assertEquals(f.scope, f.runtime.durable.header.scope)
            assertTrue(f.errors.errors.value.toString(), f.errors.errors.value.isEmpty())
            assertNull(f.runtime.currentWorker())
        }
    }

    @Test fun `closing Session after worker acceptance prevents an append from running under a later login`() = runTest {
        fixture { f ->
            val cleanup = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val previous = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleanup.complete(Unit); release.await() } }
            }
            f.runtime.installTurnWorker(Uuid.random(), previous)
            val sending = async { f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("revoked")), false) }
            cleanup.await()
            assertFalse(sending.isCompleted)
            f.sessions.finishExit(f.sessions.beginExit(requireNotNull(f.sessions.captureExitRequest())))
            f.sessions.enrollFixture(exampleEnterprisePackage())
            release.complete(Unit)
            runCurrent()
            assertNull(sending.await())
            assertEquals("original", f.runtime.durable.currentMessages().single().toText())
            assertNull(f.runtime.currentWorker())
            assertEquals(1, f.errors.errors.value.size)
            coVerify(exactly = 0) { f.repository.commit(any()) }
        }
    }

    @Test fun `cancellation before installation does not cancel the existing worker`() = runTest {
        fixture { f ->
            val held = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val worker = Job()
            f.runtime.installTurnWorker(Uuid.random(), worker)
            val holder = launch { f.locks.withLock(f.rootId) { held.complete(Unit); release.await() } }
            held.await()
            val sending = launch { f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("cancelled")), false) }
            runCurrent()
            sending.cancel()
            release.complete(Unit)
            sending.join(); holder.join(); runCurrent()
            assertSame(worker, f.runtime.currentWorker())
            assertTrue(worker.isActive)
            coVerify(exactly = 0) { f.repository.commit(any()) }
            worker.cancel()
        }
    }

    @Test fun `chat submission atomically chooses queue without cancelling active worker`() = runTest {
        fixture { f ->
            val turn = Uuid.random()
            val worker = Job()
            f.runtime.installTurnWorker(turn, worker)
            val target = ConversationAssistantTarget(f.page.commandTarget, DEFAULT_ASSISTANT_ID)
            val result = f.turns.submitChatInput(target, listOf(UIMessagePart.Text("queued")), false)
            assertTrue(result is ChatInputSubmissionReceipt.Queued)
            runCurrent()
            assertFalse(worker.isCancelled)
            assertSame(worker, f.runtime.currentWorker())
            assertEquals(listOf("original"), f.runtime.durable.currentMessages().map { it.toText() })
            assertEquals(1, f.runtime.inputQueue.value.items.size)
            worker.complete()
            f.runtime.releaseTurnWorker(turn, worker)
            f.turns.resumeInputQueue(target)
            runCurrent()
            assertEquals(listOf("original", "queued"), f.runtime.durable.currentMessages().map { it.toText() })
        }
        fixture { f ->
            val target = ConversationAssistantTarget(f.page.commandTarget, DEFAULT_ASSISTANT_ID)
            val result = f.turns.submitChatInput(target, listOf(UIMessagePart.Text("immediate")), false)
            assertTrue(result is ChatInputSubmissionReceipt.Sent)
            assertEquals("immediate", f.runtime.durable.currentMessages().last().toText())
            assertTrue(f.runtime.inputQueue.value.items.isEmpty())
        }
    }

    @Test fun `ordinary request completion dispatches queued inputs in order after page closes`() = runTest {
        fixture { f ->
            val release = CompletableDeferred<Unit>()
            val cleanup = CompletableDeferred<Unit>()
            val original = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleanup.complete(Unit); release.await() } }
            }
            f.runtime.installTurnWorker(Uuid.random(), original)
            val first = async { f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("A")), false) }
            cleanup.await()
            val target = ConversationAssistantTarget(f.page.commandTarget, DEFAULT_ASSISTANT_ID)
            val second = requireNotNull(f.turns.enqueueMessage(target, listOf(UIMessagePart.Text("B")), false, null))
            val third = requireNotNull(f.turns.enqueueMessage(target, listOf(UIMessagePart.Text("C")), false, null))
            assertNotEquals(second.itemId, third.itemId)
            f.page.close()
            runCurrent()
            assertEquals(listOf("original"), f.runtime.durable.currentMessages().map { it.toText() })
            release.complete(Unit)
            runCurrent()
            assertNotNull(first.await())
            assertEquals(f.errors.errors.value.toString(), listOf("original", "A", "B", "C"),
                f.runtime.durable.currentMessages().map { it.toText() })
            assertTrue(f.runtime.inputQueue.value.items.isEmpty())
            assertNull(f.runtime.currentWorker())
            assertTrue(f.errors.errors.value.toString(), f.errors.errors.value.isEmpty())
        }
    }

    @Test fun `queued precommit failure retains stable message identity and resumes without duplicates`() = runTest {
        fixture { f ->
            var reject = true
            coEvery { f.repository.commit(any()) } answers {
                if (reject) throw java.io.IOException("queue transaction rejected")
                true
            }
            val target = ConversationAssistantTarget(f.page.commandTarget, DEFAULT_ASSISTANT_ID)
            f.turns.enqueueMessage(target, listOf(UIMessagePart.Text("B")), false, null)
            f.turns.enqueueMessage(target, listOf(UIMessagePart.Text("C")), false, null)
            val messages = f.runtime.inputQueue.value.items.map { it.userMessageId }
            runCurrent()
            assertEquals(listOf("original"), f.runtime.durable.currentMessages().map { it.toText() })
            assertEquals(messages, f.runtime.inputQueue.value.items.map { it.userMessageId })
            assertEquals(InputQueuePauseReason.REQUEST_FAILED, f.runtime.inputQueue.value.pauseReason)
            assertTrue(f.errors.errors.value.any { it.detail.contains("queue transaction rejected") })
            reject = false
            f.turns.resumeInputQueue(target)
            runCurrent()
            assertEquals(listOf("original", "B", "C"), f.runtime.durable.currentMessages().map { it.toText() })
            assertEquals(messages, f.runtime.durable.currentMessages().drop(1).map { it.id })
            assertTrue(f.runtime.inputQueue.value.items.isEmpty())
        }
    }

    @Test fun `stop before queued admission prevents dispatch until explicit resume`() = runTest {
        fixture { f ->
            val target = ConversationAssistantTarget(f.page.commandTarget, DEFAULT_ASSISTANT_ID)
            f.turns.enqueueMessage(target, listOf(UIMessagePart.Text("B")), false, null)
            f.application.stopGeneration(f.page.commandTarget)
            f.turns.enqueueMessage(target, listOf(UIMessagePart.Text("C")), false, null)
            runCurrent()
            assertEquals(listOf("original"), f.runtime.durable.currentMessages().map { it.toText() })
            assertEquals(2, f.runtime.inputQueue.value.items.size)
            f.turns.resumeInputQueue(target)
            runCurrent()
            assertEquals(listOf("original", "B", "C"), f.runtime.durable.currentMessages().map { it.toText() })
        }
    }

    @Test fun `three accepted replacements await the entire original cleanup before appending`() = runTest {
        fixture { f ->
            val release = CompletableDeferred<Unit>()
            val cleanup = CompletableDeferred<Unit>()
            val original = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleanup.complete(Unit); release.await() } }
            }
            f.runtime.installTurnWorker(Uuid.random(), original)
            val first = async { f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("B")), false) }
            cleanup.await()
            val last = async { f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("C")), false) }
            runCurrent()
            assertFalse(last.isCompleted)
            assertEquals("original", f.runtime.durable.currentMessages().single().toText())
            release.complete(Unit)
            runCurrent()
            assertNull(first.await())
            val latest = requireNotNull(last.await())
            assertEquals(f.errors.errors.value.toString(), listOf("original", "C"), f.runtime.durable.currentMessages().map { it.toText() })
            assertEquals(latest.userMessageId, f.runtime.durable.currentMessages().last().id)
            assertNull(f.runtime.currentWorker())
            assertTrue(f.errors.errors.value.toString(), f.errors.errors.value.isEmpty())
        }
    }

    @Test fun `failed original terminal survives two preparing replacements and can be stopped later`() = runTest {
        fixture { f ->
            val originalTurn = Uuid.random()
            val originalWorker = Job()
            f.runtime.installTurnWorker(originalTurn, originalWorker)
            net.weero.measix.pilot.service.turn.TurnCommitter.start(f.coordinator, f.runtime, originalTurn, f.finalizer, me.rerere.ai.provider.Model(modelId = "fixture"))
            f.failTerminal = true
            f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("B")), false)
            f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("C")), false)
            runCurrent()
            assertEquals(originalTurn, f.runtime.snapshot.value.stream?.turnId)
            assertSame(originalWorker, f.runtime.currentWorker())
            assertFalse(f.runtime.durable.currentMessages().any { it.toText() in listOf("B", "C") })
            f.failTerminal = false
            f.application.stopGeneration(f.page.commandTarget)
            assertNull(f.runtime.currentWorker())
            assertNull(f.runtime.snapshot.value.stream)
            assertNull(f.runtime.peekCancelReason(originalTurn))
        }
    }

    @Test fun `stop captured while replacement prepares retains the failed original terminal ticket`() = runTest {
        fixture { f ->
            val originalTurn = Uuid.random()
            val originalWorker = Job()
            f.runtime.installTurnWorker(originalTurn, originalWorker)
            net.weero.measix.pilot.service.turn.TurnCommitter.start(f.coordinator, f.runtime, originalTurn, f.finalizer, me.rerere.ai.provider.Model(modelId = "fixture"))
            f.failTerminal = true
            f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("B")), false)
            val stop = requireNotNull(f.finalizer.captureStop(f.rootId))
            runCurrent()
            assertSame(originalWorker, f.runtime.currentWorker())
            f.failTerminal = false
            f.finalizer.finishStop(stop)
            assertNull(f.runtime.snapshot.value.stream)
            assertNull(f.runtime.currentWorker())
            assertNull(f.runtime.peekCancelReason(originalTurn))
        }
    }

    @Test fun `stop and supersede serialize one terminal commit and replacement still appends`() = runTest {
        fixture { f ->
            val originalTurn = Uuid.random()
            f.runtime.installTurnWorker(originalTurn, Job())
            net.weero.measix.pilot.service.turn.TurnCommitter.start(f.coordinator, f.runtime, originalTurn, f.finalizer, me.rerere.ai.provider.Model(modelId = "fixture"))
            val stop = requireNotNull(f.finalizer.captureStop(f.rootId))
            val receipt = requireNotNull(f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("B")), false))
            f.finalizer.finishStop(stop)
            runCurrent()
            assertEquals(f.errors.errors.value.toString(), receipt.userMessageId, f.runtime.durable.currentMessages().last().id)
            assertNull(f.runtime.currentWorker())
            assertNull(f.runtime.peekCancelReason(originalTurn))
            assertEquals(1, f.terminalCommits)
            assertTrue(f.errors.errors.value.toString(), f.errors.errors.value.isEmpty())
        }
    }

    @Test fun `fresh page in a new Session cannot continue a turn frozen under the previous login`() = runTest {
        fixture { f ->
            val originalAccess = f.page.commandTarget.selection.access
            val turnId = Uuid.random()
            val worker = Job()
            f.runtime.installTurnWorker(turnId, worker)
            val started = net.weero.measix.pilot.service.turn.TurnCommitter.start(
                f.coordinator, f.runtime, turnId, f.finalizer, me.rerere.ai.provider.Model(modelId = "fixture"))
            val lease = net.weero.measix.pilot.service.runtime.ModelExecutionLease { error("no request expected") }
            f.runtime.bindModelExecution(turnId, worker, f.runtime.durable.header.assistantId, lease)
            f.runtime.bindTurnContext(turnId, worker, mockk {
                every { realmAccess } returns originalAccess
                every { assistant } returns net.weero.measix.pilot.service.turn.resolveTurnAssistantSnapshot(
                    net.weero.measix.pilot.data.model.Assistant(id = f.runtime.durable.header.assistantId))
                every { model } returns mockk { every { requests } returns lease }
            })
            f.runtime.retainAwaitingUser(started.handle)
            worker.complete()
            f.sessions.finishExit(f.sessions.beginExit(requireNotNull(f.sessions.captureExitRequest())))
            f.sessions.enrollFixture(exampleEnterprisePackage())
            val selected = f.sessions.observeSelectedRealmSelection().first { it != null }!!
            val reopened = ConversationViewLease(f.rootId, selected.access, selected.revision) {}
            try {
                rejects<IllegalStateException> {
                    f.turns.submitToolDecision(reopened.commandTarget,
                        me.rerere.ai.core.ToolCallLocator(Uuid.random(), Uuid.random(), Uuid.random()),
                        ToolInteractionDecision.Approve)
                }
                assertSame(worker, f.runtime.currentWorker())
                assertTrue(f.runtime.isAwaitingUser(turnId))
                assertEquals(turnId, f.runtime.snapshot.value.stream?.turnId)
            } finally { reopened.close() }
        }
    }

    @Test fun `cancellation during real Service START commit still hands off the terminal owner`() = runTest {
        fixture { f ->
            f.enableGeneration()
            f.cancelDuringStart = true
            val receipt = requireNotNull(f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("start"))))
            requireNotNull(f.runtime.currentWorker()).join()
            assertEquals(net.weero.measix.pilot.data.db.entity.TurnExecutionStatus.CANCELLED,
                f.executions[receipt.turnId.toString()]?.status)
            assertEquals(1, f.terminalCommits)
            assertNull(f.runtime.snapshot.value.stream)
            assertNull(f.runtime.currentWorker())
            coVerify(exactly = 0) { f.runner.run(any()) }
            assertTrue(f.errors.errors.value.toString(), f.errors.errors.value.isEmpty())
        }
    }

    @Test fun `real Service continuation reuses the original context and Turn after switching away and back`() = runTest {
        fixture { f ->
            f.enableGeneration()
            val inputs = mutableListOf<net.weero.measix.pilot.service.turn.TurnRunInputs>()
            lateinit var locator: me.rerere.ai.core.ToolCallLocator
            coEvery { f.runner.run(any()) } coAnswers {
                val input = firstArg<net.weero.measix.pilot.service.turn.TurnRunInputs>()
                inputs += input
                if (inputs.size == 1) {
                    val assistant = input.messages.last()
                    val parts = assistant.parts.map {
                        if (it is UIMessagePart.Step) it.copy(modelResult = net.weero.measix.pilot.testkit.sampledModelResult()) else it
                    }
                    val step = parts.filterIsInstance<UIMessagePart.Step>().single()
                    val tool = UIMessagePart.Tool(localCallId = Uuid.random(), stepId = step.stepId,
                        providerCallId = "call", toolName = "ask_user", input = "{}",
                        interactionState = me.rerere.ai.ui.ToolInteractionState.AwaitingInput)
                    locator = me.rerere.ai.core.ToolCallLocator(assistant.id, step.stepId, tool.localCallId)
                    input.onCheckpoint(ModelResponseCheckpoint(input.handle, StepHandle(step.stepId),
                        assistant.copy(parts = parts + tool), net.weero.measix.pilot.data.db.entity.TurnExecutionStatus.AWAITING_USER))
                    net.weero.measix.pilot.service.turn.TurnPause(listOf(
                        net.weero.measix.pilot.data.ai.tools.PendingToolInteraction(locator, tool.interactionState)))
                } else awaitCancellation()
            }
            val receipt = requireNotNull(f.turns.sendMessage(f.page.commandTarget, listOf(UIMessagePart.Text("start"))))
            f.runtime.activeTurnRevision.first { f.runtime.isAwaitingUser(receipt.turnId) || f.runtime.currentWorker() == null }
            runCurrent()
            assertTrue(f.errors.errors.value.toString(), f.errors.errors.value.isEmpty())
            assertTrue(f.runtime.isAwaitingUser(receipt.turnId))
            f.sessions.selectPersonalFixture(); f.sessions.selectEnterpriseFixture()
            val selected = f.sessions.observeSelectedRealmSelection().first { it != null }!!
            val reopened = ConversationViewLease(f.rootId, selected.access, selected.revision) {}
            try {
                f.turns.submitToolDecision(reopened.commandTarget, locator, ToolInteractionDecision.Answer("answer"))
                runCurrent()
                assertEquals(2, inputs.size)
                assertSame(inputs[0].turnContext, inputs[1].turnContext)
                assertEquals(inputs[0].handle, inputs[1].handle)
                assertEquals(f.page.commandTarget.selection.access, inputs[1].turnContext.realmAccess)
                assertEquals(1, f.executions.size)
                f.application.stopGeneration(reopened.commandTarget)
                assertNull(f.runtime.snapshot.value.stream)
                assertNull(f.runtime.currentWorker())
            } finally { reopened.close() }
        }
    }

    private suspend fun TestScope.fixture(action: suspend (Fixture) -> Unit) {
        val f = Fixture(this)
        try { f.initialize(); action(f) }
        finally { f.appScope.cancel() }
    }

    @Test fun `summary registration remains owned when caller is cancelled before admission returns`() = runTest {
        fixture { f ->
            val cleanup = CompletableDeferred<Unit>()
            lateinit var caller: Job
            val worker = f.appScope.async<Result<Unit>>(start = CoroutineStart.LAZY) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleanup.await() } }
            }
            every { f.effects.launchCompression(any(), any(), any(), any(), any(), any()) } answers {
                f.runtime.registerAuxiliaryWorker(f.page.access, worker)
                worker.start()
                caller.cancel()
                worker
            }
            // Enter the worker first so its cancellation must wait for real cleanup.
            worker.start()
            runCurrent()
            caller = launch(start = CoroutineStart.LAZY) { f.application.compress(f.page.commandTarget, "", 100, 0) }
            caller.start()
            runCurrent()
            assertTrue(worker.isCancelled)
            assertFalse(caller.isCompleted)
            assertTrue(f.runtime.hasAuxiliaryWork)
            cleanup.complete(Unit)
            caller.join()
            assertTrue(worker.isCompleted)
            assertFalse(f.runtime.hasAuxiliaryWork)
            coVerify(exactly = 0) { f.repository.commit(any()) }
        }
    }

    @Test fun `enterprise exit awaits its auxiliary cleanup and durable terminal without stopping personal work`() = runTest {
        fixture { f ->
            val personalId = f.put(ConfigurationScope.Personal)
            val personal = f.registry.registerSnapshot(f.rows.getValue(personalId))
            val personalWorker = Job()
            personal.installTurnWorker(Uuid.random(), personalWorker)
            val turn = Uuid.random()
            val worker = Job()
            f.runtime.installTurnWorker(turn, worker)
            net.weero.measix.pilot.service.turn.TurnCommitter.start(f.coordinator, f.runtime, turn, f.finalizer, me.rerere.ai.provider.Model(modelId = "fixture"))
            val release = CompletableDeferred<Unit>()
            val auxiliary = f.appScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { release.await() } }
            }
            f.runtime.registerAuxiliaryWorker(f.page.access, auxiliary)
            val token = f.sessions.beginExit(requireNotNull(f.sessions.captureExitRequest()))
            val stop = async { f.application.stopEnterpriseWork(token) }
            runCurrent()
            assertFalse(stop.isCompleted)
            assertTrue(auxiliary.isCancelled)
            assertFalse(auxiliary.isCompleted)
            assertTrue(personalWorker.isActive)
            release.complete(Unit)
            stop.await()
            assertEquals(TurnExecutionStatus.CANCELLED, f.executions[turn.toString()]?.status)
            assertNull(f.runtime.snapshot.value.stream)
            assertFalse(f.runtime.hasAuxiliaryWork)
            assertTrue(personalWorker.isActive)
            f.sessions.finishExit(token)
            personalWorker.cancel()
        }
    }

    @Test fun `enterprise stop failure retains terminal owner and retry verifies stored unfinished turns`() = runTest {
        fixture { f ->
            val turn = Uuid.random()
            val worker = Job()
            f.runtime.installTurnWorker(turn, worker)
            net.weero.measix.pilot.service.turn.TurnCommitter.start(f.coordinator, f.runtime, turn, f.finalizer, me.rerere.ai.provider.Model(modelId = "fixture"))
            val token = f.sessions.beginExit(requireNotNull(f.sessions.captureExitRequest()))
            f.failTerminal = true
            rejects<java.io.IOException> { f.application.stopEnterpriseWork(token) }
            assertEquals(token, f.sessions.pendingExit())
            assertSame(worker, f.runtime.currentWorker())
            assertEquals(TurnExecutionStatus.RUNNING, f.executions[turn.toString()]?.status)
            f.failTerminal = false
            f.application.stopEnterpriseWork(token)
            assertNull(f.runtime.currentWorker())
            assertEquals(TurnExecutionStatus.CANCELLED, f.executions[turn.toString()]?.status)
            coEvery { f.repository.countUnfinishedTurns(f.scope) } returns 1
            rejects<IllegalStateException> { f.application.requireEnterpriseStopped(token) }
            assertEquals(token, f.sessions.pendingExit())
        }
    }

    @Test fun `cache eviction after enterprise runtime capture does not skip remaining active work`() = runTest {
        fixture { f ->
            val turn = Uuid.random()
            f.runtime.installTurnWorker(turn, Job())
            net.weero.measix.pilot.service.turn.TurnCommitter.start(f.coordinator, f.runtime, turn, f.finalizer, me.rerere.ai.provider.Model(modelId = "fixture"))
            val token = f.sessions.beginExit(requireNotNull(f.sessions.captureExitRequest()))
            val idleId = f.put(f.scope, parent = f.rootId)
            f.registry.registerSnapshot(f.rows.getValue(idleId))
            val held = CompletableDeferred<Unit>()
            val evict = CompletableDeferred<Unit>()
            val holder = launch {
                f.locks.withLock(idleId) {
                    held.complete(Unit)
                    evict.await()
                    f.registry.evictRuntime(idleId)
                }
            }
            held.await()
            val stop = async { f.application.stopEnterpriseWork(token) }
            runCurrent()
            assertFalse(stop.isCompleted)
            evict.complete(Unit)
            holder.join()
            stop.await()
            assertEquals(TurnExecutionStatus.CANCELLED, f.executions[turn.toString()]?.status)
            assertNull(f.runtime.currentWorker())
            assertEquals(token, f.sessions.pendingExit())
        }
    }

    private inner class Fixture(test: TestScope) {
        val appScope = AppScope(StandardTestDispatcher(test.testScheduler))
        val sessions = EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.enterpriseTestStore(temporary.newFolder()), net.weero.measix.pilot.data.enterprise.EnterpriseDataResetStore(java.io.File(System.getProperty("java.io.tmpdir"), "enterprise-reset-${java.util.UUID.randomUUID()}")))
        val scope = exampleEnterprisePackage().identity.scope
        val repository = mockk<ConversationRepository>()
        val settings = mockk<SettingsStore>()
        val artifactStore = mockk<ArtifactStore>()
        val rows = linkedMapOf<Uuid, ConversationAggregateSnapshot>()
        var retentionReleased = 0
        val executions = linkedMapOf<String, net.weero.measix.pilot.data.db.entity.TurnExecutionEntity>()
        var failTerminal = false
        var cancelDuringStart = false
        var terminalCommits = 0
        val locks = ConversationOperationLocks()
        val registry = ConversationRuntimeRegistry(appScope, repository, locks)
        val gate = ApplicationRecoveryGate().apply { ready() }
        val coordinator = ConversationCommandCoordinator(registry, repository, gate, locks)
        val finalizer = TurnFinalizer(repository, registry, coordinator, JsonInstant)
        val lifecycle = SubAssistantLifecycle(repository, registry, coordinator, JsonInstant)
        val effects = mockk<GenerationSideEffects>(relaxed = true)
        val errors = ChatErrorStore()
        val runner = mockk<net.weero.measix.pilot.service.turn.TurnRunner>()
        val memory = mockk<MemoryService>()
        val mcp = mockk<net.weero.measix.pilot.data.ai.mcp.McpRuntimeCoordinator>()
        val turns by lazy {
            val context = mockk<android.app.Application>()
            every { context.getString(any()) } returns "operation"
            every { effects.preloadSoundEffects() } returns Unit
            ConversationTurnService(context, appScope, mockk(relaxed = true), settings, net.weero.measix.pilot.test.testModelExecutionService(settings, sessions, gate), mockk(relaxed = true), memory, sessions, runner, mockk(relaxed = true),
                mcp, mockk(relaxed = true), net.weero.measix.pilot.service.turn.TurnContextFactory(mockk()),
                mockk(relaxed = true), mockk(), finalizer, lifecycle, registry, coordinator, gate,
                errors, effects, ArtifactUseCase(artifactStore, gate, sessions), ConversationTitleCoordinator())
        }
        val runGate = net.weero.measix.pilot.service.subassistant.SubAssistantRunGate()
        val application = ConversationApplicationService(settings, repository, mockk(), registry, coordinator, gate,
            lifecycle, effects, artifactStore, mockk(), finalizer, JsonInstant, mockk(), ConversationTitleCoordinator(), sessions, runGate)
        val rootId = put(scope)
        lateinit var runtime: ConversationRuntime
        lateinit var page: ConversationViewLease

        init {
            every { settings.userSettings } returns kotlinx.coroutines.flow.MutableStateFlow(
                net.weero.measix.pilot.data.datastore.Settings())
            net.weero.measix.pilot.test.installExecutionConfigurationFixture(settings)
            coEvery { settings.forgetConversation(any(), any()) } returns Unit
            coEvery { repository.getConversationHeader(any()) } answers { rows[firstArg()]?.header }
            coEvery { repository.getConversationSnapshotById(any()) } answers { rows[firstArg()] }
            coEvery { repository.getChildConversationIds(any()) } answers {
                val id = firstArg<Uuid>()
                rows.values.filter { it.header.parentConversationId == id }.map { it.conversationId }
            }
            coEvery { repository.getChildConversationSnapshots(any()) } answers {
                val id = firstArg<Uuid>()
                rows.values.filter { it.header.parentConversationId == id }
            }
            coEvery { repository.getTurnExecution(any()) } answers { executions[firstArg()] }
            coEvery { repository.countUnfinishedTurns(any()) } answers {
                val requested = firstArg<ConfigurationScope>()
                executions.values.count { execution ->
                    rows[Uuid.parse(execution.conversationId)]?.header?.scope == requested &&
                        execution.status in setOf(TurnExecutionStatus.RUNNING, TurnExecutionStatus.AWAITING_USER)
                }
            }
            coEvery { repository.getTurnExecutions(any()) } returns emptyList()
            coEvery { repository.getToolExecutions(any()) } returns emptyList()
            coEvery { repository.existsConversationById(any()) } answers { rows.containsKey(firstArg()) }
            coEvery { repository.commit(any()) } answers {
                val facts = (firstArg<ConversationWrite>() as? ConversationWrite.Mutate)?.executionFacts
                facts?.turn?.let {
                    if (it.status == net.weero.measix.pilot.data.db.entity.TurnExecutionStatus.CANCELLED) {
                        if (failTerminal) throw java.io.IOException("terminal disk failure")
                        terminalCommits++
                    }
                    executions[it.turnId] = it
                    if (cancelDuringStart && facts.turnOperation == TurnExecutionOperation.START) {
                        runtime.requestCancel(Uuid.parse(it.turnId), "user_stop")
                    }
                }
                true
            }
            coEvery { repository.deleteConversation(any()) } answers {
                val id = firstArg<Uuid>()
                rows.entries.removeAll { it.key == id || it.value.header.parentConversationId == id }
                Unit
            }
            coEvery { repository.insertConversationSnapshot(any()) } answers {
                val snapshot = firstArg<ConversationAggregateSnapshot>()
                rows[snapshot.conversationId] = snapshot
            }
            coEvery { artifactStore.retainNodesForUndo(any(), any()) } answers { ArtifactRetentionLease { retentionReleased++ } }
            every { effects.clearTitleTracking(any()) } returns Unit
        }

        fun configureAssistant(assistant: net.weero.measix.pilot.data.model.Assistant) {
            val current = settings.userSettings.value
            every { settings.userSettings } returns kotlinx.coroutines.flow.MutableStateFlow(current.copy(assistants = listOf(assistant)))
        }

        fun enableGeneration() {
            val model = me.rerere.ai.provider.Model(modelId = "test")
            val assistant = net.weero.measix.pilot.data.model.Assistant(id = DEFAULT_ASSISTANT_ID, enableMemory = false, chatModelId = model.id)
            val config = net.weero.measix.pilot.data.datastore.Settings(assistants = listOf(assistant),
                providers = listOf(me.rerere.ai.provider.ProviderSetting.OpenAI(models = listOf(model))), chatModelId = model.id)
            every { settings.userSettings } returns kotlinx.coroutines.flow.MutableStateFlow(
                config)
            coEvery { memory.captureExecution(any(), any()) } returns null
            coEvery { mcp.prepareTurnCapabilities(any(), any(), any(), any(), any(), any(), any()) } returns net.weero.measix.pilot.data.ai.mcp.TurnMcpCapabilitySnapshot.EMPTY
        }

        suspend fun initialize() {
            sessions.enrollFixture(exampleEnterprisePackage())
            runtime = registry.registerSnapshot(rows.getValue(rootId))
            val selection = sessions.observeSelectedRealmSelection().first { it != null }!!
            page = ConversationViewLease(rootId, selection.access, selection.revision) {}
        }

        fun put(scope: ConfigurationScope, parent: Uuid? = null): Uuid {
            val conversation = Conversation.ofId(Uuid.random(), DEFAULT_ASSISTANT_ID).copy(scope = scope, parentConversationId = parent)
                .updateCurrentMessages(listOf(UIMessage.user("original")))
            rows[conversation.id] = conversation.toSnapshot()
            return conversation.id
        }
    }

    private suspend inline fun <reified T : Throwable> rejects(block: suspend () -> Unit) {
        try { block(); fail("Expected ${T::class.simpleName}") }
        catch (error: Throwable) { if (error !is T) throw error }
    }
}
