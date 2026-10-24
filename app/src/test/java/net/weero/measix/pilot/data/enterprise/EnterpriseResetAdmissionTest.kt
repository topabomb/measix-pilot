package net.weero.measix.pilot.data.enterprise

import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import io.mockk.mockk
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
class EnterpriseResetAdmissionTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun intent() = EnterpriseDataResetIntent(
        EnterpriseDataResetIntent.SCHEMA_VERSION, Uuid.random().toString(),
        EnterpriseDataResetMode.KEEP_HISTORY, 1000L, emptyList(),
        EnterpriseDataResetStage.FROZEN, emptyList(),
    )

    private suspend fun rejected(reason: String, action: suspend () -> Unit) {
        try {
            action()
            fail("Expected $reason")
        } catch (error: EnterpriseConfigurationException) {
            assertEquals(reason, error.message)
        }
    }

    @Test fun `accepted intent revokes observers and ordinary admission before closing`() = runTest {
        val reset = EnterpriseDataResetStore(temporary.newFolder())
        val owner = EnterpriseSessionController(enterpriseTestStore(temporary.newFolder()), reset) { 1000L }
        owner.enrollFixture(exampleEnterprisePackage())
        val access = owner.captureSelectedRealmAccess() as RealmAccess.Enterprise
        val allowed = mutableListOf<Boolean>()
        val selections = mutableListOf<RealmSelection?>()
        backgroundScope.launch { owner.observeRealmAccess(access).collect { allowed += it } }
        backgroundScope.launch { owner.observeSelectedRealmSelection().collect { selections += it } }
        runCurrent()
        val refresh = owner.beginPlatformRefresh(access.sessionId)
        val preview = owner.captureLocalDataResetAdmission()
        owner.acceptLocalDataResetAdmission(preview) { reset.write(intent()) }
        runCurrent()
        assertEquals(listOf(true, false), allowed)
        assertNull(selections.last())
        assertEquals(EnterpriseSessionPhase.READY, (owner.state.value as EnterpriseState.Available).manifest.phase)
        rejected("enterprise_reset_in_progress") { owner.withRealmAccess(access) { fail("admitted") } }
        rejected("enterprise_reset_in_progress") { owner.withSelectedRealmSelection(requireNotNull(preview.selection)) {} }
        rejected("enterprise_reset_in_progress") { owner.platformContext(access.sessionId) }
        rejected("enterprise_reset_in_progress") { owner.beginPlatformRefresh(access.sessionId) }
        rejected("enterprise_reset_in_progress") { owner.acceptPlatformRefresh(refresh, mockk()) }
        rejected("enterprise_reset_in_progress") { owner.completePlatformBootstrap(access.sessionId, mockk()) }
        rejected("enterprise_reset_in_progress") { owner.acceptPlatformBootstrap(access, mockk()) }
        rejected("enterprise_reset_in_progress") { owner.acceptPlatformEnrollment(mockk(), mockk(), mockk()) }
        rejected("enterprise_reset_in_progress") { owner.synchronize(access, exampleEnterprisePackage().toCandidate()) }
        rejected("enterprise_reset_in_progress") { owner.captureExecution(access) }
        assertTrue(owner.withRealmAccess(RealmAccess.Personal) { true })
        val closing = owner.beginLocalDataReset(requireNotNull(owner.captureExitRequest()))
        owner.withClosingSession(closing) { }
        owner.completeLocalDataReset(closing, retainIdentity = true)
        reset.clear()
        assertNotNull(owner.beginPlatformEnrollment())
    }

    @Test fun `closing persistence failure cannot reopen an accepted reset`() = runTest {
        var failCommit = false
        val root = temporary.newFolder()
        val reset = EnterpriseDataResetStore(temporary.newFolder())
        val store = enterpriseTestStore(root) {
            if (failCommit && it == EnterpriseStorageCheckpoint.BEFORE_MANIFEST_COMMIT) error("disk failed")
        }
        val owner = EnterpriseSessionController(store, reset) { 1000L }
        owner.enrollFixture(exampleEnterprisePackage())
        val access = owner.captureSelectedRealmAccess() as RealmAccess.Enterprise
        val preview = owner.captureLocalDataResetAdmission()
        owner.acceptLocalDataResetAdmission(preview) { reset.write(intent()) }
        failCommit = true
        try {
            owner.beginLocalDataReset(requireNotNull(owner.captureExitRequest()))
            fail("Expected commit failure")
        } catch (_: Exception) { }
        rejected("enterprise_reset_in_progress") { owner.withRealmAccess(access) {} }
        val restarted = EnterpriseSessionController(enterpriseTestStore(root), reset) { 1000L }
        restarted.recover()
        rejected("enterprise_reset_in_progress") { restarted.beginPlatformEnrollment() }
        rejected("enterprise_reset_in_progress") { restarted.withRealmAccess(access) {} }
    }

    @Test fun `reset confirmation rejects a selection switch even after switching back`() = runTest {
        val reset = EnterpriseDataResetStore(temporary.newFolder())
        val owner = EnterpriseSessionController(enterpriseTestStore(temporary.newFolder()), reset) { 1000L }
        owner.enrollFixture(exampleEnterprisePackage())
        val preview = owner.captureLocalDataResetAdmission()
        val selection = requireNotNull(preview.selection)
        val personal = owner.switchRealm(RealmSwitchRequest(selection, RealmAccess.Personal)) {}
        owner.switchRealm(RealmSwitchRequest(personal, selection.access)) {}
        rejected("enterprise_selection_revoked") {
            owner.acceptLocalDataResetAdmission(preview) { fail("must not persist") }
        }
        assertNull(reset.read())
    }

    @Test fun `enrollment exchange blocks reset until the original attempt ends`() = runTest {
        val reset = EnterpriseDataResetStore(temporary.newFolder())
        val owner = EnterpriseSessionController(enterpriseTestStore(temporary.newFolder()), reset)
        val preview = owner.captureLocalDataResetAdmission()
        val attempt = owner.beginPlatformEnrollment()
        rejected("enterprise_enrollment_in_progress") { owner.captureLocalDataResetAdmission() }
        rejected("enterprise_enrollment_in_progress") { owner.acceptLocalDataResetAdmission(preview) {} }
        owner.finishEnrollmentAttempt(attempt)
        owner.acceptLocalDataResetAdmission(preview) { reset.write(intent()) }
        rejected("enterprise_reset_in_progress") { owner.beginPlatformEnrollment() }
    }

    @Test fun `corrupt intent retains original cause and rejects enterprise admission`() = runTest {
        val root = temporary.newFolder()
        File(root, "intent.json").writeText("broken")
        val reset = EnterpriseDataResetStore(root)
        val owner = EnterpriseSessionController(enterpriseTestStore(temporary.newFolder()), reset)
        try {
            owner.beginPlatformEnrollment()
            fail("Expected invalid intent")
        } catch (error: EnterpriseStorageException) {
            assertEquals("invalid_enterprise_reset_intent", error.reason)
            assertNotNull(error.cause)
        }
        assertTrue(owner.withRealmAccess(RealmAccess.Personal) { true })
    }

    @Test fun `damaged session can still confirm the existing local reset recovery path`() = runTest {
        val root = temporary.newFolder()
        File(root, "manifest.json").writeText("broken")
        val reset = EnterpriseDataResetStore(temporary.newFolder())
        val owner = EnterpriseSessionController(enterpriseTestStore(root), reset)
        assertTrue(owner.recover() is EnterpriseState.Failed)
        val preview = owner.captureLocalDataResetAdmission()
        assertNull(preview.selection)
        owner.acceptLocalDataResetAdmission(preview) { reset.write(intent()) }
        assertNotNull(reset.read())
        assertTrue(owner.resetLocalDataState(retainIdentity = false) is EnterpriseState.Available)
        reset.clear()
        assertNotNull(owner.beginPlatformEnrollment())
    }

    @Test fun `failed intent removal keeps pending fact and exposes original cause`() {
        val root = temporary.newFolder()
        val reset = EnterpriseDataResetStore(root)
        reset.write(intent())
        File(root, "intent.json.new").mkdir()
        File(root, "intent.json.new/blocked").writeText("keep")
        try {
            reset.clear()
            fail("Expected deletion failure")
        } catch (error: EnterpriseStorageException) {
            assertEquals("enterprise_reset_clear_failed", error.reason)
            assertNotNull(error.cause)
        }
        assertNotNull(reset.read())
    }
}
