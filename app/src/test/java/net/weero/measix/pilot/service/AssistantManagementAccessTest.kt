package net.weero.measix.pilot.service

import net.weero.measix.pilot.data.enterprise.toCandidate
import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.configuration.*
import net.weero.measix.pilot.data.datastore.*
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantManagementAccessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `original enterprise execution keeps its principal after space switch and cannot reuse a replacement session`() = runTest {
        val f = Fixture(AppScope(StandardTestDispatcher(testScheduler)))
        try {
            val caller = f.enroll()
            runCurrent()
            f.sessions.selectPersonalFixture()
            val created = f.service.createAssistant("Child", "d", "p", caller).getOrThrow()
            val saved = f.settings.snapshotUserDocument()
            assertEquals(setOf(created.id), saved.preferences.assistantUsage(f.packet.identity.scope, caller.assistantId)?.additionalSubAssistantIds)
            assertFalse(saved.configuration.assistants.any { created.id in it.allowedSubAssistantIds })
            val closing = f.sessions.beginExit(requireNotNull(f.sessions.captureExitRequest()))
            f.sessions.finishExit(closing)
            f.sessions.enrollFixture(f.packet)
            assertTrue(f.service.createAssistant("Stale", "d", "p", caller).isFailure)
            assertEquals(JsonInstant.encodeToString(saved), JsonInstant.encodeToString(f.settings.snapshotUserDocument()))
        } finally { f.scope.cancel() }
    }

    @Test fun `latest policy and expiry after artifact validation both reject before durable configuration commit`() = runTest {
        val f = Fixture(AppScope(StandardTestDispatcher(testScheduler)))
        try {
            val caller = f.enroll()
            val initial = JsonInstant.encodeToString(f.settings.snapshotUserDocument())
            val access = caller.realmAccess as RealmAccess.Enterprise
            f.sessions.synchronize(access, f.packet.copy(configuration = f.packet.configuration.copy(
                generation = f.packet.configuration.generation + 1,
                policy = f.packet.configuration.policy.copy(allowLocalAssistants = false))).toCandidate())
            assertTrue(f.service.createAssistant("Denied", "d", "p", caller).isFailure)
            assertEquals(initial, JsonInstant.encodeToString(f.settings.snapshotUserDocument()))
            f.sessions.synchronize(access, f.packet.copy(configuration = f.packet.configuration.copy(
                generation = f.packet.configuration.generation + 2,
            )).toCandidate())
            f.beforeCommit = { f.now = (f.sessions.state.value as EnterpriseState.Available).manifest.session!!.expiresAtMillis }
            assertTrue(f.service.createAssistant("Expired", "d", "p", caller).isFailure)
            assertEquals(initial, JsonInstant.encodeToString(f.settings.snapshotUserDocument()))
        } finally { f.scope.cancel() }
    }

    @Test fun `queue refusal precedes definition removal and leaves no deletion tombstone`() = runTest {
        val f = Fixture(AppScope(StandardTestDispatcher(testScheduler)))
        try {
            f.enroll()
            f.service.createAssistant("Keep", "d", "p").getOrThrow()
            val target = f.service.createAssistant("Target", "d", "p").getOrThrow()
            val before = JsonInstant.encodeToString(f.settings.snapshotUserDocument())
            coEvery { f.conversations.requireAssistantDeletionInputs(target.id, null) } throws
                AssistantManagementRejection("assistant_pending_inputs_confirmation_required")
            assertEquals("assistant_pending_inputs_confirmation_required", f.service.deleteAssistant(target.id).exceptionOrNull()?.message)
            assertEquals(before, JsonInstant.encodeToString(f.settings.snapshotUserDocument()))
            io.mockk.coVerify(exactly = 0) { f.conversations.cancelGenerationsForAssistant(any(), any()) }
        } finally { f.scope.cancel() }
    }

    @Test fun `pure target validation precedes queue inspection and preview disclosure`() = runTest {
        val f = Fixture(AppScope(StandardTestDispatcher(testScheduler)))
        try {
            f.enroll()
            val managed = f.packet.identity.reference(f.packet.configuration.assistants.first().id)
            assertEquals("enterprise_assistant_read_only", f.service.deleteAssistant(managed).exceptionOrNull()?.message)
            assertEquals("enterprise_assistant_read_only", runCatching { f.service.prepareDeletion(managed) }.exceptionOrNull()?.message)
            io.mockk.coVerify(exactly = 0) { f.conversations.requireAssistantDeletionInputs(any(), any()) }
            io.mockk.coVerify(exactly = 0) { f.conversations.captureAssistantDeletionInputs(any()) }
        } finally { f.scope.cancel() }
    }

    @Test fun `changed preview refuses deletion while original shared definition survives`() = runTest {
        val f = Fixture(AppScope(StandardTestDispatcher(testScheduler)))
        try {
            f.enroll()
            f.service.createAssistant("Keep", "d", "p").getOrThrow()
            val target = f.service.createAssistant("Target", "d", "p").getOrThrow()
            val preview = f.service.prepareDeletion(target.id)
            coEvery { f.conversations.requireAssistantDeletionInputs(target.id, preview) } throws
                AssistantManagementRejection("assistant_pending_inputs_changed")
            assertEquals("assistant_pending_inputs_changed", f.service.deleteAssistant(target.id, preview = preview).exceptionOrNull()?.message)
            assertTrue(f.settings.snapshotUserDocument().configuration.assistants.any { it.id == target.id })
            assertTrue(f.settings.snapshotUserDocument().internalState.pendingAssistantDeletions.isEmpty())
        } finally { f.scope.cancel() }
    }

    @Test fun `personal deletion holds session admission through the durable settings commit`() = runTest {
        val f = Fixture(AppScope(StandardTestDispatcher(testScheduler)))
        try {
            f.enroll()
            f.sessions.selectPersonalFixture()
            f.service.createAssistant("Keep", "d", "p").getOrThrow()
            val target = f.service.createAssistant("Target", "d", "p").getOrThrow()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeCommit = { entered.complete(Unit); release.await() }
            val deleting = async { f.service.deleteAssistant(target.id) }
            entered.await()
            val uiAdmission = async {
                f.sessions.withSelectedRealmAccess(RealmAccess.Personal) {
                    f.settings.snapshotUserDocument().configuration.assistants.any { it.id == target.id }
                }
            }
            runCurrent()
            assertFalse(uiAdmission.isCompleted)
            release.complete(Unit)
            deleting.await().getOrThrow()
            assertFalse(uiAdmission.await())
        } finally { f.scope.cancel() }
    }

    @Test fun `realm switch invalidates a prepared shared assistant deletion`() = runTest {
        val f = Fixture(AppScope(StandardTestDispatcher(testScheduler)))
        try {
            f.enroll()
            f.service.createAssistant("Keep", "d", "p").getOrThrow()
            val target = f.service.createAssistant("Target", "d", "p").getOrThrow()
            val preview = f.service.prepareDeletion(target.id)
            f.sessions.selectPersonalFixture()
            assertTrue(f.service.deleteAssistant(target.id, preview = preview).isFailure)
            assertTrue(f.settings.snapshotUserDocument().configuration.assistants.any { it.id == target.id })
        } finally { f.scope.cancel() }
    }

    private inner class Fixture(val scope: AppScope) {
        val packet = exampleEnterprisePackage()
        var now = 1000L
        var beforeCommit: suspend () -> Unit = {}
        val sessions = EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.enterpriseTestStore(temporary.newFolder()), net.weero.measix.pilot.data.enterprise.EnterpriseDataResetStore(java.io.File(System.getProperty("java.io.tmpdir"), "enterprise-reset-${java.util.UUID.randomUUID()}"))) { now }
        private val callerId = packet.identity.reference(packet.configuration.assistants.first().id)
        private val root = temporary.newFolder()
        private val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = root
        }
        private val initial = UserSettingsDocument.empty().let { it.copy(preferences = it.preferences.withAssistantUsage(packet.identity.scope,
            AssistantUsagePreferences(callerId, localTools = UsageValue(listOf(LocalToolOption.AssistantManagement))))) }
        private val data = object : DataStore<Preferences> {
            override val data = MutableStateFlow<Preferences>(mutablePreferencesOf(SettingsStore.USER_SETTINGS to JsonInstant.encodeToString(initial)))
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = transform(data.value).also { data.value = it }
        }
        val settings = SettingsStore(context, scope, data)
        private val artifacts = mockk<ArtifactStore>()
        val conversations = mockk<ConversationApplicationService>(relaxed = true)
        val service: AssistantManagementService
        init {
            coEvery { conversations.captureAssistantDeletionInputs(any()) } returns emptyMap()
            coEvery { artifacts.manageAssistantReferences(any(), any(), any(), any(), any(), any()) } coAnswers {
                settings.manageAssistant(firstArg(), secondArg(), thirdArg(), arg(3), arg(5), arg(4)) { _, _, commit ->
                    beforeCommit()
                    withContext(NonCancellable) { commit() }
                }
            }
            service = AssistantManagementService(settings, mockk(), artifacts, mockk(), ApplicationRecoveryGate().apply { ready() }, conversations, sessions)
        }
        suspend fun enroll(): AssistantManagementCaller {
            sessions.enrollFixture(packet)
            return AssistantManagementCaller(callerId, sessions.captureRealmAccess(packet.identity.scope))
        }
    }
}
