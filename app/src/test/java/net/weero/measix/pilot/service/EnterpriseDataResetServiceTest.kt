package net.weero.measix.pilot.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import net.weero.measix.pilot.data.ai.mcp.McpCatalogStore
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.repository.MemoryRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.uuid.Uuid

/**
 * Reset formats every enterprise realm the device holds: both branches run in every settled state and the
 * only runtime gate is the stop barrier for a live Session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnterpriseDataResetServiceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `leaving page cancels only its wait and retries join the accepted worker`() = runTest {
        fixture { f ->
            val (sessions, _, reset) = f.new()
            val scope = f.enroll(sessions)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { f.catalogs.clearEnterpriseScope(scope) } coAnswers {
                entered.complete(Unit)
                release.await()
            }
            val preview = reset.prepare(EnterpriseDataResetMode.CLEAR_ALL)
            val request = preview.request
            val page = async { reset.reset(preview) }
            entered.await()
            page.cancel()
            page.join()
            assertEquals(request.operationId.toString(), f.store.read()?.operationId)
            assertTrue(requireNotNull(reset.progress.value).running)
            assertNull(reset.progress.value?.failure)
            val retry = async { reset.retryReset() }
            val repeated = async { reset.reset(preview) }
            runCurrent()
            assertFalse(retry.isCompleted)
            assertFalse(repeated.isCompleted)
            release.complete(Unit)
            retry.await()
            repeated.await()
            coVerify(exactly = 1) { f.catalogs.clearEnterpriseScope(scope) }
            coVerify(exactly = 1) { f.conversations.clearEnterpriseScope(scope) }
            assertNull(f.store.read())
            assertNull(reset.progress.value)
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, (sessions.state.value as EnterpriseState.Available).manifest.phase)
        }
    }

    @Test fun `worker cancellation is pending without failure and original intent retries in process`() = runTest {
        fixture { f ->
            val (sessions, _, reset) = f.new()
            val scope = f.enroll(sessions)
            coEvery { f.catalogs.clearEnterpriseScope(scope) } throws CancellationException("worker stopped")
            val preview = reset.prepare(EnterpriseDataResetMode.CLEAR_ALL)
            val request = preview.request
            try { reset.reset(preview); fail("Expected cancellation") }
            catch (_: CancellationException) { }
            assertFalse(requireNotNull(reset.progress.value).running)
            assertNull(reset.progress.value?.failure)
            assertEquals(request.operationId.toString(), f.store.read()?.operationId)
            coEvery { f.catalogs.clearEnterpriseScope(scope) } returns Unit
            reset.retryReset()
            assertNull(f.store.read())
            assertNull(reset.progress.value)
        }
    }

    @Test fun `cancellation before acceptance leaves no reset intent`() = runTest {
        fixture { f ->
            val (sessions, _, reset) = f.new()
            f.enroll(sessions)
            val enumerating = CompletableDeferred<Unit>()
            coEvery { f.conversations.enterpriseScopes() } coAnswers {
                enumerating.complete(Unit)
                CompletableDeferred<Set<ConfigurationScope.Enterprise>>().await()
            }
            val page = async { f.reset(reset, EnterpriseDataResetMode.CLEAR_ALL) }
            enumerating.await()
            page.cancel()
            page.join()
            assertNull(f.store.read())
            assertNull(reset.progress.value)
            coVerify(exactly = 0) { f.catalogs.clearEnterpriseScope(any()) }
        }
    }

    @Test fun `application shutdown before worker starts preserves pending intent for startup`() = runTest {
        fixture { f ->
            val (sessions, _, reset) = f.new()
            f.enroll(sessions)
            f.workerScope.coroutineContext[Job]?.cancel()
            val preview = reset.prepare(EnterpriseDataResetMode.KEEP_HISTORY)
            val request = preview.request
            try { reset.reset(preview); fail("Expected application shutdown") }
            catch (_: CancellationException) { }
            assertEquals(request.operationId.toString(), f.store.read()?.operationId)
            assertFalse(requireNotNull(reset.progress.value).running)
            assertNull(reset.progress.value?.failure)
            coVerify(exactly = 0) { f.catalogs.clearEnterpriseScope(any()) }
            val (restarted, resume) = f.restart()
            resume.resumePending()
            assertNull(f.store.read())
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, (restarted.state.value as EnterpriseState.Available).manifest.phase)
        }
    }

    @Test fun `cancellation during final scope read cannot accept a reset`() = runTest {
        fixture { f ->
            val (sessions, _, reset) = f.new()
            f.enroll(sessions)
            coEvery { f.catalogs.enterpriseScopes() } coAnswers {
                currentCoroutineContext()[Job]?.cancel()
                emptySet()
            }
            val page = async { f.reset(reset, EnterpriseDataResetMode.CLEAR_ALL) }
            try { page.await(); fail("Expected cancellation before acceptance") }
            catch (_: CancellationException) { }
            assertNull(f.store.read())
            assertNull(reset.progress.value)
            coVerify(exactly = 0) { f.catalogs.clearEnterpriseScope(any()) }
        }
    }

    @Test fun `corrupt durable intent fails closed and remains available for diagnosis`() {
        val root = temporary.newFolder()
        val file = File(root, "intent.json").apply { writeText("{") }

        try {
            EnterpriseDataResetStore(root).read()
            fail("Corrupt reset intent must not be treated as completed")
        } catch (error: EnterpriseStorageException) {
            assertEquals("invalid_enterprise_reset_intent", error.reason)
        }
        assertTrue(file.isFile)
    }

    @Test fun `keep history reset drops access state and managed caches for every frozen realm`() = runTest {
        fixture { f ->
            val (sessions, _, reset) = f.new()
            val scope = f.enroll(sessions)
            val residual = f.residualScope()
            f.frozenScopes = setOf(scope, residual)
            val installation = net.weero.measix.pilot.data.enterprise.enterpriseTestStore(f.root).installationId()

            f.reset(reset, EnterpriseDataResetMode.KEEP_HISTORY)

            val manifest = (sessions.state.value as EnterpriseState.Available).manifest
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, manifest.phase)
            assertNull(manifest.session)
            assertEquals(scope, manifest.lastIdentity?.scope)
            coVerify(exactly = 1) { f.catalogs.clearEnterpriseScope(scope) }
            coVerify(exactly = 1) { f.catalogs.clearEnterpriseScope(residual) }
            coVerify(exactly = 0) { f.conversations.clearEnterpriseScope(any()) }
            coVerify(exactly = 0) { f.files.clearEnterpriseScope(any()) }
            coVerify(exactly = 0) { f.memories.clearEnterpriseScope(any()) }
            coVerify(exactly = 0) { f.settings.clearEnterprisePreferences(any()) }
            assertNotEquals(installation, net.weero.measix.pilot.data.enterprise.enterpriseTestStore(f.root).installationId())
            assertEquals(0, f.logoutCalls)
            f.assertStoreClean()
        }
    }

    @Test fun `clear all removes history of every frozen realm including residual principals`() = runTest {
        fixture { f ->
            val (sessions, _, reset) = f.new()
            val scope = f.enroll(sessions)
            val residual = f.residualScope()
            f.frozenScopes = setOf(scope, residual)

            f.reset(reset, EnterpriseDataResetMode.CLEAR_ALL)

            assertNull((sessions.state.value as EnterpriseState.Available).manifest.lastIdentity)
            for (target in listOf(scope, residual)) {
                coVerify(exactly = 1) { f.conversations.clearEnterpriseScope(target) }
                coVerify(exactly = 1) { f.files.clearEnterpriseScope(target) }
                coVerify(exactly = 1) { f.memories.clearEnterpriseScope(target) }
                coVerify(exactly = 1) { f.settings.clearEnterprisePreferences(target) }
                coVerify(exactly = 1) { f.catalogs.clearEnterpriseScope(target) }
            }
            assertEquals(0, f.logoutCalls)
            f.assertStoreClean()
        }
    }

    @Test fun `scope committed before closing barrier is included by the post barrier freeze`() = runTest {
        fixture { f ->
            val (sessions, _, reset) = f.new()
            val active = f.enroll(sessions)
            val raced = f.residualScope()
            f.frozenScopes = setOf(active)
            coEvery { f.conversations.stopEnterpriseWork(any()) } answers {
                f.frozenScopes = f.frozenScopes + raced
            }

            f.reset(reset, EnterpriseDataResetMode.CLEAR_ALL)

            coVerify(exactly = 1) { f.conversations.clearEnterpriseScope(raced) }
            coVerify(exactly = 1) { f.files.clearEnterpriseScope(raced) }
            coVerify(exactly = 1) { f.memories.clearEnterpriseScope(raced) }
            coVerify(exactly = 1) { f.settings.clearEnterprisePreferences(raced) }
            coVerify(exactly = 1) { f.catalogs.clearEnterpriseScope(raced) }
        }
    }

    @Test fun `corrupt storage resets access facts and managed caches without touching history`() = runTest {
        fixture { f ->
            val enrolled = f.new()
            val scope = f.enroll(enrolled.first)
            coEvery { f.conversations.enterpriseScopes() } returns setOf(scope)
            File(f.root, "manifest.json").writeText("corrupted")
            val (sessions, _, reset) = f.new()
            assertTrue(sessions.recover() is EnterpriseState.Failed)

            f.reset(reset, EnterpriseDataResetMode.KEEP_HISTORY)

            val manifest = (sessions.state.value as EnterpriseState.Available).manifest
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, manifest.phase)
            assertNull(manifest.session)
            assertNull(manifest.lastIdentity)
            coVerify(exactly = 1) { f.catalogs.clearEnterpriseScope(scope) }
            coVerify(exactly = 0) { f.conversations.clearEnterpriseScope(any()) }
            coVerify(exactly = 0) { f.files.clearEnterpriseScope(any()) }
            coVerify(exactly = 0) { f.memories.clearEnterpriseScope(any()) }
            coVerify(exactly = 0) { f.settings.clearEnterprisePreferences(any()) }
            assertEquals(0, f.logoutCalls)
            f.assertStoreClean()
        }
    }

    @Test fun `corrupt storage can also clear every frozen realm`() = runTest {
        fixture { f ->
            val enrolled = f.new()
            val scope = f.enroll(enrolled.first)
            val residual = f.residualScope()
            coEvery { f.conversations.enterpriseScopes() } returns setOf(scope, residual)
            File(f.root, "manifest.json").writeText("corrupted")
            val (sessions, _, reset) = f.new()
            assertTrue(sessions.recover() is EnterpriseState.Failed)

            f.reset(reset, EnterpriseDataResetMode.CLEAR_ALL)

            for (target in listOf(scope, residual)) {
                coVerify(exactly = 1) { f.conversations.clearEnterpriseScope(target) }
                coVerify(exactly = 1) { f.files.clearEnterpriseScope(target) }
                coVerify(exactly = 1) { f.memories.clearEnterpriseScope(target) }
                coVerify(exactly = 1) { f.settings.clearEnterprisePreferences(target) }
            }
            assertEquals(0, f.logoutCalls)
            f.assertStoreClean()
        }
    }

    @Test fun `interrupted reset resumes from the retained intent after a restart`() = runTest {
        fixture { f ->
            val (sessions, _, reset) = f.new()
            val scope = f.enroll(sessions)
            // Two frozen realms keep the retained intent multi-scope, so the resume path must read it back.
            val residual = f.residualScope()
            f.frozenScopes = setOf(scope, residual)
            coEvery { f.catalogs.clearEnterpriseScope(any()) } throws IOException("catalog pending")
            try { f.reset(reset, EnterpriseDataResetMode.CLEAR_ALL); fail("Expected catalog failure") }
            catch (_: IOException) { }

            val retained = requireNotNull(f.store.read())
            assertEquals(EnterpriseDataResetStage.DOMAINS_CLOSED, retained.stage)
            assertEquals(setOf(scope, residual), retained.scopes.toSet())
            assertEquals(2, retained.scopes.size)
            assertEquals(EnterpriseSessionPhase.CLOSING, (sessions.state.value as EnterpriseState.Available).manifest.phase)

            coEvery { f.catalogs.clearEnterpriseScope(any()) } returns Unit
            val (restarted, _, resume) = f.new()
            resume.resumePending()

            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, (restarted.state.value as EnterpriseState.Available).manifest.phase)
            assertNull(f.store.read())
            f.assertStoreClean()
        }
    }

    @Test fun `owner failure retains the intent and retry converges`() = runTest {
        fixture { f ->
            val (sessions, _, reset) = f.new()
            f.enroll(sessions)
            coEvery { f.memories.clearEnterpriseScope(any()) } throws IOException("memory store unavailable")
            try { f.reset(reset, EnterpriseDataResetMode.CLEAR_ALL); fail("Expected memory failure") }
            catch (_: IOException) { }
            assertNotNull(f.store.read())

            coEvery { f.memories.clearEnterpriseScope(any()) } returns Unit
            reset.retryReset()

            assertNull(f.store.read())
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, (sessions.state.value as EnterpriseState.Available).manifest.phase)
            f.assertStoreClean()
        }
    }

    @Test fun `keep history resume keeps the previous enterprise identity after a restart`() = runTest {
        fixture { f ->
            val (sessions, _, reset) = f.new()
            val scope = f.enroll(sessions)
            coEvery { f.catalogs.clearEnterpriseScope(any()) } throws IOException("catalog pending")
            try { f.reset(reset, EnterpriseDataResetMode.KEEP_HISTORY); fail("Expected catalog failure") }
            catch (_: IOException) { }

            coEvery { f.catalogs.clearEnterpriseScope(any()) } returns Unit
            val (restarted, resume) = f.restart()
            resume.resumePending()

            // The CLOSING continuation preserves the identity a signed-out rewrite alone would lose.
            val manifest = (restarted.state.value as EnterpriseState.Available).manifest
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, manifest.phase)
            assertNull(manifest.session)
            assertEquals(scope, manifest.lastIdentity?.scope)
            assertNull(f.store.read())
            f.assertStoreClean()
        }
    }

    @Test fun `frozen resume freezes a live session durably without the runtime domain stop`() = runTest {
        fixture { f ->
            // A live READY Session on disk: the confirmed reset died before its barrier ran. The bare
            // enroller carries no exit watcher, so nothing races the resumed writes over the same store.
            val enroller = EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.enterpriseTestStore(f.root), f.store) { f.now }
            val scope = f.enroll(enroller)
            f.store.write(f.frozenIntent(scope))

            val (restarted, resume) = f.restart()
            resume.resumePending()

            val manifest = (restarted.state.value as EnterpriseState.Available).manifest
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, manifest.phase)
            assertNull(manifest.session)
            assertEquals(scope, manifest.lastIdentity?.scope)
            // Cold start holds no in-process enterprise work, so the runtime stop path is never entered;
            // pre-restart turns are converged by turn recovery, not by the reset barrier.
            coVerify(exactly = 0) { f.conversations.stopEnterpriseWork(any()) }
            assertNull(f.store.read())
            f.assertStoreClean()
        }
    }

    @Test fun `startup resume defers to a pending foreign exit and converges once it completes`() = runTest {
        fixture { f ->
            val enroller = EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.enterpriseTestStore(f.root), f.store) { f.now }
            val scope = f.enroll(enroller)
            // A reset confirmed while a foreign exit was already closing is rejected but keeps its intent.
            enroller.beginExit(requireNotNull(enroller.captureExitRequest()))
            f.store.write(f.frozenIntent(scope))

            val (restarted, resume) = f.restart()
            // Must not throw: the pending exit's own recovery completion runs later in this startup.
            resume.resumePending()

            assertEquals("enterprise_exit_in_progress", resume.progress.value?.failure)
            assertNotNull(f.store.read())
            assertEquals(EnterpriseSessionPhase.CLOSING,
                (restarted.state.value as EnterpriseState.Available).manifest.phase)

            // The pending-exit completion finishes the foreign CLOSING, then the retained intent converges.
            restarted.finishExit(requireNotNull(restarted.pendingExit()))
            resume.retryReset()

            assertNull(f.store.read())
            val manifest = (restarted.state.value as EnterpriseState.Available).manifest
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, manifest.phase)
            assertEquals(scope, manifest.lastIdentity?.scope)
            f.assertStoreClean()
        }
    }

    @Test fun `reset waits for a foreign exit and completes on retry`() = runTest {
        fixture { f ->
            val (sessions, exits, reset) = f.new()
            f.enroll(sessions)
            val request = requireNotNull(exits.captureRequest())
            sessions.beginExit(request.request)

            var error: EnterpriseConfigurationException? = null
            try { f.reset(reset, EnterpriseDataResetMode.KEEP_HISTORY); fail("Expected reset rejection") }
            catch (rejected: EnterpriseConfigurationException) { error = rejected }
            assertEquals("enterprise_exit_in_progress", error?.reason)
            // The durable intent stays so the user's confirmed choice can resume once the exit finishes.
            assertNotNull(f.store.read())

            // The foreign exit finishes through its own owner, then the retained intent converges on retry.
            sessions.finishExit(requireNotNull(sessions.pendingExit()))
            reset.retryReset()

            assertNull(f.store.read())
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, (sessions.state.value as EnterpriseState.Available).manifest.phase)
            f.assertStoreClean()
        }
    }

    @Test fun `revocation and pending exit completion never adopt a local data reset closing token`() = runTest {
        fixture { f ->
            val (sessions, exits, _) = f.new()
            val scope = f.enroll(sessions)
            val access = requireNotNull((sessions.state.value as EnterpriseState.Available).manifest.session)
                .let { RealmAccess.Enterprise(scope, it.id) }
            sessions.beginLocalDataReset(requireNotNull(exits.captureRequest()).request)

            var invalidation: EnterpriseConfigurationException? = null
            try { sessions.beginInvalidation(access, EnterpriseExitReason.AUTHORIZATION_REVOKED); fail("Expected invalidation rejection") }
            catch (rejected: EnterpriseConfigurationException) { invalidation = rejected }
            assertEquals("enterprise_reset_in_progress", invalidation?.reason)
            var recovery: EnterpriseConfigurationException? = null
            try { exits.completeDuringRecovery(); fail("Expected recovery completion rejection") }
            catch (rejected: EnterpriseConfigurationException) { recovery = rejected }
            assertEquals("enterprise_reset_in_progress", recovery?.reason)
        }
    }

    @Test fun `identity deletion strengthens keep history reset at every durable stage and survives restart`() = runTest {
        for (stage in EnterpriseDataResetStage.entries) fixture { f ->
            val original = EnterpriseSessionController(
                net.weero.measix.pilot.data.enterprise.enterpriseTestStore(f.root), f.store,
            ) { f.now }
            val scope = f.enroll(original)
            val access = original.captureRealmAccess(scope) as RealmAccess.Enterprise
            original.beginLocalDataReset(requireNotNull(original.captureExitRequest()))
            f.store.write(f.frozenIntent(scope).copy(
                stage = stage,
                completedOwners = if (stage == EnterpriseDataResetStage.FROZEN) emptyList()
                else listOf(EnterpriseDataResetIntent.OWNER_CATALOGS),
            ))
            original.beginInvalidation(access, EnterpriseExitReason.IDENTITY_DELETED)

            val (restarted, reset) = f.restart()
            reset.resumePending()

            val retained = requireNotNull(f.store.read())
            assertEquals(EnterpriseDataResetMode.CLEAR_ALL, retained.mode)
            assertEquals(EnterpriseExitReason.IDENTITY_DELETED, restarted.pendingExit()?.reason)
            restarted.finishExit(requireNotNull(restarted.pendingExit()))
            reset.retryReset()

            val manifest = (restarted.state.value as EnterpriseState.Available).manifest
            assertEquals(EnterpriseSessionPhase.SIGNED_OUT, manifest.phase)
            assertEquals(EnterpriseExitReason.IDENTITY_DELETED, manifest.exitReason)
            assertNull(manifest.lastIdentity)
            coVerify(exactly = 1) { f.conversations.clearEnterpriseScope(scope) }
            coVerify(exactly = 1) { f.files.clearEnterpriseScope(scope) }
            coVerify(exactly = 1) { f.memories.clearEnterpriseScope(scope) }
            coVerify(exactly = 1) { f.settings.clearEnterprisePreferences(scope) }
            assertNull(f.store.read())
            f.assertStoreClean()
        }
    }

    @Test fun `in flight platform deletion is admitted before keep history reset can finalize`() = runTest {
        fixture { f ->
            val (sessions, _, reset) = f.new()
            val scope = f.enroll(sessions)
            val access = sessions.captureRealmAccess(scope) as RealmAccess.Enterprise
            val operation = sessions.capturePlatformOperation(access.sessionId)

            val resetting = async {
                runCatching { f.reset(reset, EnterpriseDataResetMode.KEEP_HISTORY) }
            }
            runCurrent()
            assertFalse(resetting.isCompleted)
            assertEquals(
                EnterpriseExitReason.LOCAL_DATA_RESET,
                requireNotNull(sessions.pendingExit()).reason,
            )

            sessions.beginInvalidation(access, EnterpriseExitReason.IDENTITY_DELETED)
            operation.release()
            val failure = resetting.await().exceptionOrNull() as EnterpriseConfigurationException
            assertEquals("enterprise_reset_in_progress", failure.reason)
            assertEquals(EnterpriseDataResetMode.CLEAR_ALL, requireNotNull(f.store.read()).mode)

            sessions.finishExit(requireNotNull(sessions.pendingExit()))
            reset.retryReset()

            val manifest = (sessions.state.value as EnterpriseState.Available).manifest
            assertEquals(EnterpriseExitReason.IDENTITY_DELETED, manifest.exitReason)
            assertNull(manifest.lastIdentity)
            coVerify(exactly = 1) { f.conversations.clearEnterpriseScope(scope) }
            assertNull(f.store.read())
        }
    }

    @Test fun `changed queue refuses reset before any intent or cleanup`() = runTest {
        fixture { f ->
            val (sessions, _, reset) = f.new()
            f.enroll(sessions)
            val preview = reset.prepare(EnterpriseDataResetMode.CLEAR_ALL)
            coEvery { f.conversations.requireEnterpriseResetInputs(preview) } throws
                EnterpriseConfigurationException("enterprise_pending_inputs_changed")
            assertEquals("enterprise_pending_inputs_changed", runCatching { reset.reset(preview) }.exceptionOrNull()?.message)
            assertNull(f.store.read())
            assertEquals(EnterpriseSessionPhase.READY, (sessions.state.value as EnterpriseState.Available).manifest.phase)
            coVerify(exactly = 0) { f.conversations.clearEnterpriseInputQueuesAfterResetStop() }
            coVerify(exactly = 0) { f.conversations.clearEnterpriseScope(any()) }
        }
    }

    @Test fun `reset preview cannot follow a later space selection`() = runTest {
        fixture { f ->
            val (sessions, _, reset) = f.new()
            f.enroll(sessions)
            val preview = reset.prepare(EnterpriseDataResetMode.KEEP_HISTORY)
            sessions.selectPersonalFixture()
            assertTrue(runCatching { reset.reset(preview) }.exceptionOrNull() is EnterpriseConfigurationException)
            assertNull(f.store.read())
            coVerify(exactly = 0) { f.conversations.requireEnterpriseResetInputs(any()) }
        }
    }

    @Test fun `both reset branches clear all residual enterprise queues without a live session`() = runTest {
        for (mode in EnterpriseDataResetMode.entries) fixture { f ->
            val (sessions, _, reset) = f.new()
            sessions.recover()
            f.frozenScopes = setOf(f.residualScope())
            val preview = reset.prepare(mode)
            assertNull(preview.pendingInputCount)
            reset.reset(preview)
            coVerify(exactly = 1) { f.conversations.clearEnterpriseInputQueuesAfterResetStop() }
            coVerify(exactly = 0) { f.conversations.stopEnterpriseWork(any()) }
            coVerify(exactly = if (mode == EnterpriseDataResetMode.CLEAR_ALL) 1 else 0) {
                f.conversations.clearEnterpriseScope(f.residualScope())
            }
        }
    }

    @Test fun `failed intent write leaves no accepted reset and never clears pending inputs`() = runTest {
        fixture { f ->
            val (sessions, exits, _) = f.new()
            f.enroll(sessions)
            val failingStore = mockk<EnterpriseDataResetStore>()
            every { failingStore.read() } returns null
            every { failingStore.write(any()) } throws IOException("intent write failed")
            val reset = EnterpriseDataResetService(failingStore, sessions, exits, f.conversations, f.settings,
                f.memories, f.catalogs, f.files, ApplicationRecoveryGate().apply { ready() }, f.workerScope) { f.now }
            val preview = reset.prepare(EnterpriseDataResetMode.CLEAR_ALL)
            assertEquals("intent write failed", runCatching { reset.reset(preview) }.exceptionOrNull()?.message)
            assertNull(f.store.read())
            assertEquals(EnterpriseSessionPhase.READY, (sessions.state.value as EnterpriseState.Available).manifest.phase)
            coVerify(exactly = 0) { f.conversations.clearEnterpriseInputQueuesAfterResetStop() }
        }
    }

    @Test fun `accepted intent is handed off even when the session acceptance receipt is cancelled`() = runTest {
        fixture { f ->
            val (sessions, exits, original) = f.new()
            f.enroll(sessions)
            val preview = original.prepare(EnterpriseDataResetMode.KEEP_HISTORY)
            val cancelledReceipt = mockk<EnterpriseSessionController>()
            coEvery { cancelledReceipt.acceptLocalDataResetAdmission(any(), any()) } coAnswers {
                secondArg<suspend () -> Unit>()()
                throw CancellationException("acceptance receipt cancelled")
            }
            f.workerScope.coroutineContext[Job]?.cancel()
            val reset = EnterpriseDataResetService(f.store, cancelledReceipt, exits, f.conversations, f.settings,
                f.memories, f.catalogs, f.files, ApplicationRecoveryGate().apply { ready() }, f.workerScope) { f.now }
            assertTrue(runCatching { reset.reset(preview) }.exceptionOrNull() is CancellationException)
            assertEquals(preview.request.operationId.toString(), f.store.read()?.operationId)
            val progress = requireNotNull(reset.progress.value)
            assertEquals(preview.request.operationId, progress.operationId)
            assertFalse(progress.running)
            assertNull(progress.failure)
        }
    }

    @Test fun `write verification failure with retained intent exposes pending retry without cleanup`() = runTest {
        fixture { f ->
            val (sessions, exits, original) = f.new()
            f.enroll(sessions)
            val preview = original.prepare(EnterpriseDataResetMode.KEEP_HISTORY)
            val uncertainStore = mockk<EnterpriseDataResetStore>()
            every { uncertainStore.read() } answers { f.store.read() }
            every { uncertainStore.write(any()) } answers {
                f.store.write(firstArg())
                throw EnterpriseStorageException("enterprise_reset_commit_failed").apply {
                    initCause(java.io.IOException("Cannot verify enterprise-reset/intent.json"))
                }
            }
            val reset = EnterpriseDataResetService(uncertainStore, sessions, exits, f.conversations, f.settings,
                f.memories, f.catalogs, f.files, ApplicationRecoveryGate().apply { ready() }, f.workerScope) { f.now }
            assertEquals("enterprise_reset_commit_failed", runCatching { reset.reset(preview) }.exceptionOrNull()?.message)
            assertNotNull(f.store.read())
            assertTrue(requireNotNull(reset.progress.value?.failure).contains("enterprise_reset_commit_failed"))
            assertTrue(requireNotNull(reset.progress.value?.failure).contains("IOException: Cannot verify enterprise-reset/intent.json"))
            assertFalse(requireNotNull(reset.progress.value).running)
            coVerify(exactly = 0) { f.conversations.clearEnterpriseInputQueuesAfterResetStop() }
            every { uncertainStore.write(any()) } answers { f.store.write(firstArg()) }
            every { uncertainStore.clear() } throws EnterpriseStorageException("enterprise_reset_clear_failed").apply {
                initCause(java.io.IOException("Cannot remove enterprise-reset/intent.json"))
            }
            assertTrue(runCatching { reset.retryReset() }.isFailure)
            assertTrue(requireNotNull(reset.progress.value?.failure).contains("enterprise_reset_clear_failed"))
            assertTrue(requireNotNull(reset.progress.value?.failure).contains("IOException: Cannot remove enterprise-reset/intent.json"))
            every { uncertainStore.clear() } answers { f.store.clear() }
            reset.retryReset()
            assertNull(f.store.read())
            assertNull(reset.progress.value)
        }
    }

    private suspend fun TestScope.fixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture(this)
        try { block(f) } finally {
            f.scope.coroutineContext[Job]?.cancel()
            f.workerScope.coroutineContext[Job]?.cancel()
        }
    }

    private inner class Fixture(testScope: TestScope) {
        val root = temporary.newFolder()
        // The reset tests call the close barrier directly; keep the unrelated expiry observer on an
        // independent scheduler so runTest cannot auto-advance a multi-year platform Session to expiry.
        val scope: CoroutineScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        val workerScope: CoroutineScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScope.testScheduler))
        var now = 1000L
        var logoutCalls = 0
        var frozenScopes: Set<ConfigurationScope.Enterprise> = emptySet()
        val store = EnterpriseDataResetStore(temporary.newFolder())
        val conversations = mockk<ConversationApplicationService>()
        val settings = mockk<SettingsStore>()
        val memories = mockk<MemoryRepository>()
        val catalogs = mockk<McpCatalogStore>()
        val files = mockk<FileManagementApplicationService>()

        init {
            coEvery { conversations.captureEnterpriseExitInputs(any()) } returns emptyMap()
            coEvery { conversations.requireEnterpriseExitInputs(any()) } returns Unit
            coEvery { conversations.captureEnterpriseResetInputs() } returns emptyMap()
            coEvery { conversations.requireEnterpriseResetInputs(any()) } returns Unit
            coEvery { conversations.clearEnterpriseInputQueuesAfterResetStop() } returns Unit
            coEvery { conversations.stopEnterpriseWork(any()) } returns Unit
            coEvery { conversations.requireEnterpriseStopped(any()) } returns Unit
            coEvery { conversations.clearEnterpriseScope(any()) } returns Unit
            coEvery { settings.clearEnterprisePreferences(any()) } returns Unit
            coEvery { memories.clearEnterpriseScope(any()) } returns Unit
            coEvery { catalogs.clearEnterpriseScope(any()) } returns Unit
            coEvery { files.clearEnterpriseScope(any()) } returns Unit
            // Scope freeze reads every owner's realms; the fixture keeps it explicit per test.
            coEvery { conversations.enterpriseScopes() } answers { frozenScopes }
            coEvery { files.enterpriseScopes() } returns emptySet()
            coEvery { memories.enterpriseScopes() } returns emptySet()
            coEvery { settings.enterpriseScopes() } answers { frozenScopes }
            coEvery { catalogs.enterpriseScopes() } returns emptySet()
        }

        /** A realm that only exists as leftover history, to prove the reset is not narrowed to one principal. */
        fun residualScope(): ConfigurationScope.Enterprise = ConfigurationScope.Enterprise(
            packetAuthority(), "residual-user",
        )

        /** The intent a confirmed-but-interrupted reset left on disk before its barrier completed. */
        fun frozenIntent(scope: ConfigurationScope.Enterprise) = EnterpriseDataResetIntent(
            schemaVersion = EnterpriseDataResetIntent.SCHEMA_VERSION,
            operationId = Uuid.random().toString(),
            mode = EnterpriseDataResetMode.KEEP_HISTORY,
            createdAtMillis = now,
            scopes = listOf(scope),
            stage = EnterpriseDataResetStage.FROZEN,
            completedOwners = emptyList(),
        )

        /** A fresh controller, exit service and reset service over the same durable directories. */
        fun new(): Triple<EnterpriseSessionController, EnterpriseExitService, EnterpriseDataResetService> {
            val sessions = EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.enterpriseTestStore(root), store) { now }
            val gate = ApplicationRecoveryGate().apply { ready() }
            val exits = EnterpriseExitService(sessions,
                mockk { coEvery { cancelAndAwait(any()) } returns Unit }, conversations, gate, scope,
                net.weero.measix.pilot.service.portal.PortalDocumentRegistry(),
                mockk(relaxed = true), terminals = mockk { io.mockk.coEvery { closeRealm(any()) } returns Unit },
                mcp = mockk { io.mockk.coEvery { closeRealm(any()) } returns Unit }, speech = mockk(relaxed = true),
                identityData = mockk(relaxed = true),
                remoteWorkspace = mockk { io.mockk.coEvery { cancelAndAwait(any()) } returns Unit }, webDav = mockk { io.mockk.coEvery { cancelAndAwait(any()) } returns Unit }, platformLogout = { logoutCalls++ })
            val reset = EnterpriseDataResetService(store, sessions, exits, conversations, settings, memories,
                catalogs, files, gate, workerScope) { now }
            return Triple(sessions, exits, reset)
        }

        /**
         * A fresh controller and reset service without an exit watcher: a startup resume never uses the
         * runtime domain stop, and the strict exit mock fails the test the moment it is called.
         */
        fun restart(): Pair<EnterpriseSessionController, EnterpriseDataResetService> {
            val sessions = EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.enterpriseTestStore(root), store) { now }
            val reset = EnterpriseDataResetService(store, sessions, mockk(), conversations, settings,
                memories, catalogs, files, ApplicationRecoveryGate().apply { ready() }, workerScope) { now }
            return sessions to reset
        }

        suspend fun enroll(sessions: EnterpriseSessionController): ConfigurationScope.Enterprise {
            sessions.enrollFixture(exampleEnterprisePackage())
            val scope = requireNotNull((sessions.state.value as EnterpriseState.Available).manifest.session).identity.scope
            frozenScopes = setOf(scope)
            return scope
        }

        suspend fun reset(service: EnterpriseDataResetService, mode: EnterpriseDataResetMode) {
            service.reset(service.prepare(mode))
        }

        fun assertStoreClean() {
            assertTrue("revisions", (File(root, "revisions").listFiles() ?: emptyArray()).isEmpty())
            assertTrue("credentials", (File(root, "credentials").listFiles() ?: emptyArray()).isEmpty())
        }

        private fun packetAuthority() = exampleEnterprisePackage().identity.authority
    }
}
