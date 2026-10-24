package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.ai.mcp.McpCommonOptions
import net.weero.measix.pilot.data.ai.mcp.McpRefreshReceipt
import net.weero.measix.pilot.data.ai.mcp.McpRuntimeCoordinator
import net.weero.measix.pilot.data.ai.mcp.McpOAuthState
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.ai.mcp.McpToolPolicy
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpApplicationServiceTest {
    private val manager = mockk<McpRuntimeCoordinator>(relaxed = true)
    private val settingsStore = mockk<SettingsStore>()
    private val sessions = mockk<net.weero.measix.pilot.data.enterprise.EnterpriseSessionController>()
    private val synchronization = mockk<EnterpriseSynchronizationService>()
    private lateinit var local: Settings
    private lateinit var effective: MutableStateFlow<net.weero.measix.pilot.data.datastore.Settings>
    private lateinit var service: McpApplicationService

    @Before
    fun setUp() {
        local = Settings()
        effective = MutableStateFlow(local)
        every { settingsStore.userSettings } returns effective
        coEvery { settingsStore.updateLocal(any()) } coAnswers {
            firstArg<(Settings) -> Settings>()(local).also { committed ->
                local = committed
                effective.value = committed
            }
        }
        coEvery { sessions.withSelectedRealmSelection<Any?>(any(), any()) } coAnswers {
            secondArg<suspend () -> Any?>().invoke()
        }
        service = McpApplicationService(manager, settingsStore, sessions, synchronization)
    }

    @Test
    fun `detail save merges edited fields with latest oauth and unrelated changes`() = runTest {
        val baseline = remote(name = "Tools", policies = listOf(McpToolPolicy(name = "a")))
        val oauth = McpOAuthState(enabled = true, accessToken = "rotated")
        val latest = baseline.clone(commonOptions = baseline.commonOptions.copy(enable = false, oauth = oauth,
            toolPolicies = listOf(McpToolPolicy(name = "a"), McpToolPolicy(name = "new", needsApproval = true))))
        local = Settings(mcpServers = listOf(latest))
        val edited = baseline.clone(commonOptions = baseline.commonOptions.copy(name = "Renamed"))
        service.saveConfiguration(baseline, edited)
        val saved = local.mcpServers.single()
        assertEquals("Renamed", saved.commonOptions.name)
        assertEquals(false, saved.commonOptions.enable)
        assertEquals(oauth, saved.commonOptions.oauth)
        assertEquals(latest.commonOptions.toolPolicies, saved.commonOptions.toolPolicies)
    }

    @Test
    fun `detail save rejects concurrent field conflicts and does not resurrect deleted servers`() = runTest {
        val baseline = remote(name = "Tools")
        val edited = baseline.clone(commonOptions = baseline.commonOptions.copy(name = "My edit"))
        val latest = baseline.clone(commonOptions = baseline.commonOptions.copy(name = "Another edit"))
        local = Settings(mcpServers = listOf(latest))
        try { service.saveConfiguration(baseline, edited); error("Expected conflict") }
        catch (failure: IllegalStateException) { assertEquals("mcp_edit_conflict: name", failure.message) }
        assertEquals(listOf(latest), local.mcpServers)
        local = local.copy(mcpServers = emptyList())
        try { service.saveConfiguration(baseline, edited); error("Expected removal") }
        catch (failure: IllegalStateException) { assertEquals("mcp_definition_removed", failure.message) }
        assertTrue(local.mcpServers.isEmpty())
    }

    @Test
    fun `detail endpoint change drops original oauth without changing unrelated policies`() = runTest {
        val baseline = remote(name = "Tools", oauth = McpOAuthState(enabled = true, accessToken = "old"))
        local = Settings(mcpServers = listOf(baseline))
        service.saveConfiguration(baseline, (baseline as McpServerConfig.StreamableHTTPServer).copy(url = "https://other.example/mcp"))
        assertEquals(null, local.mcpServers.single().commonOptions.oauth)
    }

    @Test
    fun `same-name overwrite clears oauth when the resource changes and preserves tool policy`() = runTest {
        val oauth = McpOAuthState(enabled = true, accessToken = "current-token")
        val existing = remote(
            name = "Remote tools",
            url = "https://old.example/mcp",
            oauth = oauth,
            policies = listOf(McpToolPolicy(name = "measure", enable = false, needsApproval = true)),
        )
        local = Settings(mcpServers = listOf(existing))
        effective.value = local

        service.overwriteByName(
            listOf(
                remote(
                    name = "remote tools",
                    url = "https://new.example/mcp",
                )
            )
        )

        val saved = local.mcpServers.single() as McpServerConfig.StreamableHTTPServer
        assertEquals(existing.id, saved.id)
        assertEquals("https://new.example/mcp", saved.url)
        assertEquals(null, saved.commonOptions.oauth)
        assertEquals(false, saved.commonOptions.toolPolicies.single().enable)
        assertEquals(true, saved.commonOptions.toolPolicies.single().needsApproval)
    }

    @Test
    fun `same-resource overwrite preserves latest oauth`() = runTest {
        val oauth = McpOAuthState(enabled = true, accessToken = "current-token")
        val existing = remote(name = "Remote tools", oauth = oauth)
        local = Settings(mcpServers = listOf(existing))
        effective.value = local

        service.overwriteByName(listOf(remote(name = "remote tools")))

        assertEquals(oauth, local.mcpServers.single().commonOptions.oauth)
    }

    @Test
    fun `upsert clears oauth when static headers change`() = runTest {
        val oauth = McpOAuthState(enabled = true, accessToken = "current-token")
        val existing = remote(name = "Remote tools", oauth = oauth)
        local = Settings(mcpServers = listOf(existing))
        effective.value = local

        service.upsert(
            remote(
                id = existing.id,
                name = existing.commonOptions.name,
                headers = listOf("Authorization" to "Bearer replacement-token"),
            )
        )

        assertEquals(null, local.mcpServers.single().commonOptions.oauth)
    }

    @Test
    fun `upsert rejects case-insensitive duplicate server names`() = runTest {
        local = Settings(mcpServers = listOf(remote(name = "Remote tools")))
        effective.value = local

        val error = try {
            service.upsert(remote(name = " remote tools "))
            null
        } catch (caught: IllegalArgumentException) {
            caught
        }
        assertTrue(error is IllegalArgumentException)
        assertEquals(1, local.mcpServers.size)
    }

    @Test
    fun `delete atomically removes server and assistant references`() = runTest {
        val server = remote(name = "Remote tools")
        val assistant = Assistant(mcpServers = setOf(server.id))
        local = Settings(mcpServers = listOf(server), assistants = listOf(assistant))
        effective.value = local

        service.delete(server.id)

        assertTrue(local.mcpServers.isEmpty())
        assertTrue(local.assistants.single().mcpServers.isEmpty())
    }

    @Test
    fun `refresh returns the coordinator operation receipt unchanged`() = runTest {
        val receipt = McpRefreshReceipt(requestedServerCount = 3, settledServerCount = 1)
        coEvery { manager.refreshAllRegisteredServers() } returns receipt

        assertEquals(receipt, service.refreshAll(net.weero.measix.pilot.data.enterprise.RealmSelection(
            net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, 1)))
    }

    @Test
    fun `enterprise refresh checks execution version and includes personal and managed servers`() = runTest {
        val scope = net.weero.measix.pilot.data.configuration.ConfigurationScope.Enterprise(
            me.rerere.common.configuration.EnterpriseAuthority("refresh-deployment"), "user")
        val access = net.weero.measix.pilot.data.enterprise.RealmAccess.Enterprise(scope, "session")
        val selection = net.weero.measix.pilot.data.enterprise.RealmSelection(access, 1)
        val version = net.weero.measix.pilot.data.enterprise.EnterpriseAppliedVersion("revision", 1, "configuration", "execution")
        coEvery { synchronization.prepareExecution(access) } returns version
        coEvery { manager.refreshAllRegisteredServers() } returns McpRefreshReceipt(1, 0)
        coEvery { manager.refreshManagedServers(selection, version) } returns McpRefreshReceipt(2, 2)

        assertEquals(McpRefreshReceipt(3, 2), service.refreshAll(selection))

        coVerify(exactly = 1) { synchronization.prepareExecution(access) }
        coVerify(exactly = 1) { manager.refreshManagedServers(selection, version) }
    }

    @Test
    fun `switching realm during preflight rejects managed discovery without transferring personal maintenance`() = runTest {
        val scope = net.weero.measix.pilot.data.configuration.ConfigurationScope.Enterprise(
            me.rerere.common.configuration.EnterpriseAuthority("refresh-deployment"), "user")
        val access = net.weero.measix.pilot.data.enterprise.RealmAccess.Enterprise(scope, "session")
        val selection = net.weero.measix.pilot.data.enterprise.RealmSelection(access, 1)
        val version = net.weero.measix.pilot.data.enterprise.EnterpriseAppliedVersion("revision", 1, "configuration", "execution")
        var revoked = false
        coEvery { sessions.withSelectedRealmSelection<Any?>(selection, any()) } coAnswers {
            check(!revoked) { "enterprise_selection_revoked" }
            secondArg<suspend () -> Any?>().invoke()
        }
        coEvery { synchronization.prepareExecution(access) } answers { revoked = true; version }
        coEvery { manager.refreshAllRegisteredServers() } returns McpRefreshReceipt(1, 1)

        try { service.refreshAll(selection); org.junit.Assert.fail("Old selection must fail") }
        catch (error: IllegalStateException) { assertEquals("enterprise_selection_revoked", error.message) }

        coVerify(exactly = 1) { manager.refreshAllRegisteredServers() }
        coVerify(exactly = 0) { manager.refreshManagedServers(any(), any()) }
    }

    @Test
    fun `enterprise preflight failure still completes the existing personal refresh`() = runTest {
        val scope = net.weero.measix.pilot.data.configuration.ConfigurationScope.Enterprise(
            me.rerere.common.configuration.EnterpriseAuthority("refresh-deployment"), "user")
        val access = net.weero.measix.pilot.data.enterprise.RealmAccess.Enterprise(scope, "session")
        val selection = net.weero.measix.pilot.data.enterprise.RealmSelection(access, 1)
        val failure = java.io.IOException("fixture enterprise unavailable")
        coEvery { synchronization.prepareExecution(access) } throws failure
        coEvery { manager.refreshAllRegisteredServers() } returns McpRefreshReceipt(1, 1)

        try { service.refreshAll(selection); org.junit.Assert.fail("Enterprise failure must remain visible") }
        catch (error: java.io.IOException) { assertEquals(failure.message, error.message) }

        coVerify(exactly = 1) { manager.refreshAllRegisteredServers() }
        coVerify(exactly = 0) { manager.refreshManagedServers(any(), any()) }
    }

    @Test
    fun `single server restart returns the coordinator operation receipt unchanged`() = runTest {
        val server = remote(name = "Remote tools")
        local = Settings(mcpServers = listOf(server))
        effective.value = local
        val receipt = McpRefreshReceipt(requestedServerCount = 1, settledServerCount = 0)
        coEvery { manager.restartServer(server.id) } returns receipt

        assertEquals(receipt, service.restart(server.id))
    }

    @Test fun `editing invalid legacy endpoints clears credentials and permits repair`() = runTest {
        for (oldUrl in listOf("", "invalid", "https://example.test/#fragment")) {
            for (oauth in listOf(null, McpOAuthState(enabled = true, accessToken = "old"))) {
                val existing = remote(name = "Legacy", url = oldUrl, oauth = oauth)
                local = Settings(mcpServers = listOf(existing))
                effective.value = local
                service.upsert(existing.copy(url = "https://example.test/"))
                assertEquals("https://example.test/", (local.mcpServers.single() as McpServerConfig.StreamableHTTPServer).url)
                assertEquals(null, local.mcpServers.single().commonOptions.oauth)
            }
        }
    }

    @Test fun `root slash equivalent editor URL preserves existing authorization audience`() = runTest {
        val oauth = McpOAuthState(enabled = true, accessToken = "old", authorizedResource = "https://example.test/")
        val existing = remote(name = "Legacy", url = "https://example.test/", oauth = oauth)
        local = Settings(mcpServers = listOf(existing))
        effective.value = local
        service.upsert(existing.copy(url = "https://example.test"))
        assertEquals(oauth, local.mcpServers.single().commonOptions.oauth)
    }

    private fun remote(
        id: ConfigurationReference = ConfigurationReference.random(),
        name: String,
        url: String = "https://example.test/mcp",
        oauth: McpOAuthState? = null,
        policies: List<McpToolPolicy> = emptyList(),
        headers: List<Pair<String, String>> = emptyList(),
    ) = McpServerConfig.StreamableHTTPServer(
        id = id,
        commonOptions = McpCommonOptions(
            name = name,
            headers = headers,
            oauth = oauth,
            toolPolicies = policies,
        ),
        url = url,
    )

    @Test fun `every config write rejects unsafe headers and batch validation is atomic`() = runTest {
        val existing = remote(name = "existing")
        local = Settings(mcpServers = listOf(existing))
        effective.value = local
        val bad = remote(name = "existing", headers = listOf("" to "credential-secret"))
        val good = remote(name = "new")
        val commands: List<suspend () -> Unit> = listOf(
            { service.upsert(bad) },
            { service.importServers(listOf(good, bad)); Unit },
            { service.overwriteByName(listOf(good, bad)) },
        )
        for (command in commands) {
            try { command(); org.junit.Assert.fail("invalid headers must fail before writing") }
            catch (error: net.weero.measix.pilot.data.ai.mcp.McpHeaderValidationException) {
                assertEquals(1, error.row)
                org.junit.Assert.assertFalse(error.message!!.contains("credential-secret"))
            }
            assertEquals(listOf(existing), local.mcpServers)
        }
        val corrected = bad.clone(commonOptions = bad.commonOptions.copy(headers = listOf("Authorization" to "  Bearer unchanged  ")))
        service.overwriteByName(listOf(corrected))
        assertEquals("  Bearer unchanged  ", local.mcpServers.single().commonOptions.headers.single().second)
    }
}
