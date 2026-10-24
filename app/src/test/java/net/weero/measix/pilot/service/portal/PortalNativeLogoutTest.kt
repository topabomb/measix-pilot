package net.weero.measix.pilot.service.portal

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.EnterpriseState
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.EnterpriseExitPreview
import net.weero.measix.pilot.service.EnterpriseExitResult
import net.weero.measix.pilot.service.EnterpriseExitService
import org.junit.Assert.*
import org.junit.Test

class PortalNativeLogoutTest {
    private class Fixture {
        val access = RealmAccess.Enterprise(ConfigurationScope.Enterprise(EnterpriseAuthority("portal-test"), "user"), "session")
        val selection = RealmSelection(access, 1)
        var now = 0L
        val document = PortalDocumentContext("document", selection, "https://portal.example", 100, { now })
        val sessions = mockk<EnterpriseSessionController>()
        val exits = mockk<EnterpriseExitService>()
        val preview = mockk<EnterpriseExitPreview>()
        init {
            every { preview.access } returns access
            every { preview.selection } returns selection
            every { preview.pendingInputCount } returns 3
            coEvery { exits.captureRequest() } returns preview
            coEvery { exits.exit(preview) } returns EnterpriseExitResult()
            val state = mockk<EnterpriseState.Available>(relaxed = true)
            every { state.manifest.session!!.identity.enterpriseName } returns "Original enterprise"
            coEvery { sessions.portalState(selection) } returns state
        }
    }

    @Test fun `logout presents captured pending count before handing original preview to exit`() = runTest {
        val f = Fixture()
        val actions = PortalNativeActions(mockk(), f.document, f.sessions, f.exits, mockk(), mockk(), backgroundScope)
        val logout = async { actions.logout() }
        runCurrent()
        val prompt = actions.prompt.value as PortalNativePrompt.Logout
        assertEquals(3, prompt.pendingInputCount)
        assertEquals("Original enterprise", prompt.enterpriseName)
        coVerify(exactly = 0) { f.exits.exit(any()) }
        actions.decide(prompt, true)
        logout.await()
        coVerify(exactly = 1) { f.exits.exit(f.preview) }
        assertNull(actions.prompt.value)
    }

    @Test fun `cancelled or expired portal confirmation cannot start exit`() = runTest {
        for (expired in listOf(false, true)) {
            val f = Fixture()
            val actions = PortalNativeActions(mockk(), f.document, f.sessions, f.exits, mockk(), mockk(), backgroundScope)
            val logout = async { runCatching { actions.logout() } }
            runCurrent()
            val prompt = requireNotNull(actions.prompt.value)
            if (expired) f.now = 100
            actions.decide(prompt, expired)
            assertEquals(if (expired) "session_expired" else "user_cancelled", (logout.await().exceptionOrNull() as PortalFailure).code)
            coVerify(exactly = 0) { f.exits.exit(any()) }
            assertNull(actions.prompt.value)
        }
    }
}
