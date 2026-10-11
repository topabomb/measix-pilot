package net.weero.measix.pilot.data.ai.mcp

import android.content.Context
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class McpOAuthAudienceTest {
    @Test fun `refresh sends original audience and only commits it after acknowledgement`() = runTest {
        for (audience in listOf(null, "https://mcp.example", "https://mcp.example/")) {
            val config = server(McpOAuthState(enabled = true, clientId = "client", refreshToken = "refresh",
                tokenEndpoint = "https://auth.example/token", authorizedResource = audience))
            val values = MutableStateFlow(Settings(mcpServers = listOf(config)))
            val store = store(values)
            val requests = mutableListOf<Request>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests += chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body("""{"access_token":"new","expires_in":3600}""".toResponseBody()).build()
            }.build()
            val scope = AppScope(Dispatchers.Default)
            try {
                val coordinator = McpOAuthCoordinator(store, scope, McpOAuthClient(client), NoOpOAuthCallbackKeepAlive,
                    ioDispatcher = Dispatchers.IO, logger = { _, _ -> })
                coordinator.ensureFreshToken(config)
                val form = Buffer().also { requests.single().body!!.writeTo(it) }.readUtf8()
                val fields = form.split('&').associate { pair ->
                    pair.substringBefore('=') to java.net.URLDecoder.decode(pair.substringAfter('='), "UTF-8")
                }
                val expected = audience ?: "https://mcp.example/"
                assertEquals(expected, fields["resource"])
                val saved = values.value.mcpServers.single().commonOptions.oauth!!
                assertEquals(expected, saved.authorizedResource)
                assertEquals("new", saved.accessToken)
                assertEquals(1L, saved.revision)
            } finally { scope.cancel(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }

    @Test fun `different saved audience refuses refresh before network or settings mutation`() = runTest {
        val config = server(McpOAuthState(enabled = true, clientId = "client", refreshToken = "refresh",
            tokenEndpoint = "https://auth.example/token", authorizedResource = "https://other.example"))
        val values = MutableStateFlow(Settings(mcpServers = listOf(config)))
        val store = store(values)
        val client = mockk<McpOAuthClient>()
        val scope = AppScope(StandardTestDispatcher(testScheduler))
        try {
            val coordinator = McpOAuthCoordinator(store, scope, client, NoOpOAuthCallbackKeepAlive,
                ioDispatcher = StandardTestDispatcher(testScheduler), logger = { _, _ -> })
            try { coordinator.ensureFreshToken(config); fail("audience mismatch accepted") }
            catch (error: IllegalArgumentException) { assertTrue(error.message!!.startsWith("oauth_resource_changed")) }
            coVerify(exactly = 0) { client.refreshToken(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { store.updateLocal(any()) }
            assertEquals(config, values.value.mcpServers.single())
        } finally { scope.cancel() }
    }

    @Test fun `new registration binds localhost old clients keep IP and failed launch leaves no socket`() = runTest {
        for (initial in listOf(null, McpOAuthState(clientId = "legacy"),
            McpOAuthState(clientId = "registered", callbackHost = McpOAuthCallbackHost.LOCALHOST))) {
            val config = server(initial)
            val values = MutableStateFlow(Settings(mcpServers = listOf(config)))
            val client = mockk<McpOAuthClient>()
            val redirects = mutableListOf<String>()
            coEvery { client.discoverProtectedResource(any()) } returns null
            coEvery { client.discoverAuthorizationServer("https://mcp.example") } returns McpOAuthClient.AuthorizationServerMetadata(
                issuer = "https://mcp.example", authorizationEndpoint = "https://auth.example/authorize",
                tokenEndpoint = "https://auth.example/token", registrationEndpoint = "https://auth.example/register")
            every { client.generatePkce() } returns McpOAuthClient.Pkce("verifier", "challenge")
            every { client.generateState() } returns "state"
            coEvery { client.registerClient(any(), any(), any(), any()) } coAnswers {
                redirects += arg<String>(2)
                McpOAuthClient.ClientRegistrationResponse("new-client")
            }
            every { client.buildAuthorizationUrl(any(), any(), any(), any(), any(), any(), any()) } answers {
                redirects += arg<String>(2)
                "https://auth.example/authorize"
            }
            val context = mockk<Context>()
            every { context.applicationContext } returns context
            val keepAlive = object : OAuthCallbackKeepAlive {
                override fun acquire(context: Context): OAuthCallbackKeepAliveLease = throw java.io.IOException("launch fixture")
            }
            val scope = AppScope(StandardTestDispatcher(testScheduler))
            try {
                val coordinator = McpOAuthCoordinator(store(values), scope, client, keepAlive,
                    ioDispatcher = StandardTestDispatcher(testScheduler), logger = { _, _ -> })
                try { coordinator.authorize(config, context); fail("launch failure lost") }
                catch (error: java.io.IOException) { assertEquals("launch fixture", error.message) }
                val uri = java.net.URI(redirects.last())
                val expectedHost = if (initial?.clientId == "legacy") McpOAuthCallbackHost.IP else McpOAuthCallbackHost.LOCALHOST
                assertEquals(expectedHost, values.value.mcpServers.single().commonOptions.oauth!!.callbackHost)
                assertEquals(initial == null, redirects.size == 2)
                if (expectedHost == McpOAuthCallbackHost.LOCALHOST) assertEquals("localhost", uri.host)
                else assertTrue(uri.host in setOf("127.0.0.1", "[::1]"))
                for (address in java.net.InetAddress.getAllByName(uri.host)) {
                    try { Socket().use { it.connect(InetSocketAddress(address, uri.port), 300) }; fail("callback listener survived") }
                    catch (_: java.net.ConnectException) { }
                }
            } finally { scope.cancel() }
        }
    }

    private fun server(oauth: McpOAuthState?) = McpServerConfig.StreamableHTTPServer(url = "https://mcp.example/",
        commonOptions = McpCommonOptions(name = "Fixture", oauth = oauth))

    private fun store(values: MutableStateFlow<Settings>): SettingsStore = mockk<SettingsStore>().also { store ->
        stubMcpUserDefinitions(store, values)
        coEvery { store.updateLocal(any()) } coAnswers {
            firstArg<(Settings) -> Settings>()(values.value).also { values.value = it }
        }
    }
}
