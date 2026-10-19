package net.weero.measix.pilot.data.ai.mcp

import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.configuration.*
import net.weero.measix.pilot.data.datastore.*
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.CapturedModelConfiguration
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Exercises discovery through the real managed HTTP transport without directory notifications. */
internal class McpManagedCatalogRefreshTest : McpRuntimeCoordinatorTestBase() {
    @Test
    fun `new enterprise turn refreshes count descriptions and parameters without a new release`() = runBlocking {
        withTimeout(30_000) {
            Fixture().use { fixture ->
                val first = fixture.prepare()
                assertEquals("${first.serverOutcomes}; ${manager.runtimeCapabilities.value}", 8, first.tools.size)
                fixture.tools = tools(9, "new description", "new_parameter")

                val second = fixture.prepare()

                assertEquals(9, second.tools.size)
                assertEquals("new description", second.tools.first().description)
                assertTrue(second.tools.first().inputSchema.getValue("properties").jsonObject.containsKey("new_parameter"))
                assertEquals(2, fixture.lists.get())
                assertEquals(8, first.tools.size)
                assertEquals("old description", first.tools.first().description)
                assertEquals(0, fixture.calls.get())
            }
        }
    }

    @Test
    fun `cached turn waits for discovery publication and a successful empty managed catalog replaces old tools`() = runBlocking {
        withTimeout(30_000) {
            Fixture().use { fixture ->
                val first = fixture.prepare()
                val started = CompletableDeferred<Unit>()
                val gate = CountDownLatch(1)
                fixture.beforeList = { started.complete(Unit); check(gate.await(10, TimeUnit.SECONDS)) }
                fixture.tools = emptyList()
                val preparing = async { fixture.prepare() }
                try {
                    started.await()
                    assertFalse(preparing.isCompleted)
                    assertEquals(8, catalogs.value.values.single().tools.size)
                    gate.countDown()
                    val second = preparing.await()
                    assertTrue(second.tools.isEmpty())
                    assertTrue(catalogs.value.values.single().tools.isEmpty())
                    assertEquals(8, first.tools.size)
                } finally { gate.countDown() }
            }
        }
    }

    @Test
    fun `manual discovery updates the catalog and closes its lease without owning the active turn`() = runBlocking {
        withTimeout(30_000) {
            Fixture().use { fixture ->
                val first = fixture.prepare()
                fixture.tools = tools(9, "manual description", "manual_parameter")

                assertEquals(McpRefreshReceipt(1, 1), manager.refreshManagedServers(fixture.selection, fixture.version))

                assertEquals(9, catalogs.value.values.single().tools.size)
                assertEquals(1, fixture.releases.get())
                assertEquals(1, manager.runtimeCapabilities.value.keys.count { it.access == fixture.access })
                assertTrue(manager.runtimeCapabilities.value.values.single().sessionCallable)
                assertEquals(8, first.tools.size)
                assertEquals(0, fixture.calls.get())
            }
        }
    }

    @Test
    fun `failed automatic and manual discovery preserve the previous directory and original diagnostic`() = runBlocking {
        withTimeout(30_000) {
            Fixture().use { fixture ->
                fixture.prepare()
                val previous = catalogs.value.values.single()
                fixture.tools = fixture.tools + fixture.tools.first()

                val fallback = fixture.prepare()
                assertEquals(8, fallback.tools.size)
                assertTrue(requireNotNull(fallback.serverOutcomes.single().catalogRefreshError).detail.orEmpty().contains("duplicate tool"))
                assertEquals(previous, catalogs.value.values.single())

                val failure = try {
                    manager.refreshManagedServers(fixture.selection, fixture.version)
                    error("Duplicate catalog must fail")
                } catch (error: IllegalStateException) { error }
                assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("duplicate tool"))
                assertEquals(previous, catalogs.value.values.single())
                assertEquals(1, fixture.releases.get())
            }
        }
    }

    @Test
    fun `selection revoked during manual discovery cannot publish a late directory`() = runBlocking {
        withTimeout(30_000) {
            Fixture().use { fixture ->
                fixture.prepare()
                val previous = catalogs.value.values.single()
                val started = CompletableDeferred<Unit>()
                val gate = CountDownLatch(1)
                fixture.beforeList = { started.complete(Unit); check(gate.await(10, TimeUnit.SECONDS)) }
                fixture.tools = tools(9, "late", "late_parameter")
                val refreshing = async {
                    try { manager.refreshManagedServers(fixture.selection, fixture.version); null }
                    catch (error: EnterpriseConfigurationException) { error }
                }
                try {
                    started.await()
                    fixture.selectionRevoked = true
                    gate.countDown()
                    assertNotNull(refreshing.await())
                    assertEquals(previous, catalogs.value.values.single())
                    assertEquals(1, fixture.releases.get())
                } finally { gate.countDown() }
            }
        }
    }

    @Test
    fun `cancelled manual discovery closes its resources and cannot publish a later response`() = runBlocking {
        withTimeout(30_000) {
            Fixture().use { fixture ->
                fixture.prepare()
                val previous = catalogs.value.values.single()
                val started = CompletableDeferred<Unit>()
                val gate = CountDownLatch(1)
                fixture.beforeList = { started.complete(Unit); check(gate.await(10, TimeUnit.SECONDS)) }
                val refreshing = async { manager.refreshManagedServers(fixture.selection, fixture.version) }
                try {
                    started.await()
                    refreshing.cancelAndJoin()
                    assertEquals(1, fixture.releases.get())
                    assertEquals(previous, catalogs.value.values.single())
                    assertEquals(1, manager.runtimeCapabilities.value.size)
                } finally { gate.countDown() }
            }
        }
    }

    @Test
    fun `failed discovery lease cleanup remains owned and the next refresh retries it`() = runBlocking {
        withTimeout(30_000) {
            Fixture().use { fixture ->
                fixture.releaseAction = { error("fixture prune failure") }
                try { manager.refreshManagedServers(fixture.selection, fixture.version); fail("Cleanup must fail") }
                catch (error: IllegalStateException) { assertEquals("fixture prune failure", error.message) }
                assertEquals(1, manager.runtimeCapabilities.value.size)
                assertFalse(manager.runtimeCapabilities.value.values.single().sessionCallable)
                fixture.releaseAction = {}
                manager.refreshManagedServers(fixture.selection, fixture.version)
                assertTrue(manager.runtimeCapabilities.value.isEmpty())
                assertEquals(2, fixture.releases.get())
            }
        }
    }

    @Test
    fun `discovery cleanup timeout is a diagnosable failure and remains retryable`() = runBlocking {
        withTimeout(30_000) {
            Fixture().use { fixture ->
                fixture.releaseAction = { withTimeout(1) { awaitCancellation() } }
                try {
                    manager.refreshManagedServers(fixture.selection, fixture.version)
                    fail("Cleanup timeout must remain visible")
                } catch (error: java.io.IOException) {
                    assertTrue(generateSequence<Throwable>(error) { it.cause }
                        .any { it is kotlinx.coroutines.TimeoutCancellationException })
                    assertTrue(error.message.orEmpty().contains("cleanup timed out"))
                }
                val failure = manager.runtimeCapabilities.value.values.single().status as McpStatus.Error
                assertTrue(failure.detail.orEmpty().contains("TimeoutCancellationException"))
                fixture.releaseAction = {}
                manager.refreshManagedServers(fixture.selection, fixture.version)
                assertTrue(manager.runtimeCapabilities.value.isEmpty())
                assertEquals(2, fixture.releases.get())
            }
        }
    }

    @Test
    fun `failed cleanup of one discovery still closes every other owned connection`() = runBlocking {
        withTimeout(30_000) {
            Fixture(serverCount = 2).use { fixture ->
                val firstId = fixture.packet.identity.reference(fixture.packet.configuration.mcpServers.first().id)
                val secondId = fixture.packet.identity.reference(fixture.packet.configuration.mcpServers.last().id)
                val committing = CompletableDeferred<Unit>()
                val commitGate = CompletableDeferred<Unit>()
                coEvery { catalogStore.commitCandidate(match { it.serverId == firstId }) } coAnswers {
                    committing.complete(Unit)
                    withContext(NonCancellable) { commitGate.await() }
                    McpCatalogCommitResult.RejectedEmpty(null)
                }
                val refreshing = async { manager.refreshManagedServers(fixture.selection, fixture.version) }
                try {
                    committing.await()
                    manager.runtimeCapabilities.first { capabilities ->
                        capabilities.any { (key, value) -> key.serverId == secondId && value.sessionCallable }
                    }
                    refreshing.cancelAndJoin()

                    assertEquals(setOf(firstId), manager.runtimeCapabilities.value.keys.map { it.serverId }.toSet())
                    assertFalse(manager.runtimeCapabilities.value.values.single().sessionCallable)
                    assertEquals(0, fixture.releases.get())
                } finally {
                    commitGate.complete(Unit)
                    refreshing.cancelAndJoin()
                    withTimeout(10_000) { manager.closeRealm(fixture.access) }
                }
                assertTrue(manager.runtimeCapabilities.value.isEmpty())
                assertEquals(1, fixture.releases.get())
            }
        }
    }

    @Test
    fun `first discovery preserves the HTTP error instead of reporting only retry state`() = runBlocking {
        withTimeout(30_000) {
            Fixture().use { fixture ->
                fixture.listStatus = 503
                val failure = try {
                    manager.refreshManagedServers(fixture.selection, fixture.version)
                    error("HTTP discovery must fail")
                } catch (error: IllegalStateException) { error }
                assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains("HTTP 503"))
                assertEquals(1, fixture.releases.get())
                assertTrue(manager.runtimeCapabilities.value.isEmpty())
            }
        }
    }

    @Test
    fun `empty managed selection does not acquire an execution lease`() = runBlocking {
        Fixture(serverCount = 0).use { fixture ->
            assertEquals(McpRefreshReceipt(0, 0), manager.refreshManagedServers(fixture.selection, fixture.version))
            coVerify(exactly = 0) { sessions.captureExecution(fixture.access, fixture.version) }
        }
    }

    @Test
    fun `catalog readiness failure does not acquire an execution lease`() = runBlocking {
        Fixture().use { fixture ->
            coEvery { catalogStore.awaitReady() } throws java.io.IOException("fixture catalog unavailable")
            try { manager.refreshManagedServers(fixture.selection, fixture.version); fail("Readiness must fail") }
            catch (error: java.io.IOException) { assertEquals("fixture catalog unavailable", error.message) }
            coVerify(exactly = 0) { sessions.captureExecution(fixture.access, fixture.version) }
        }
    }

    @Test
    fun `revocation immediately after capture retains failed cleanup for a retry`() = runBlocking {
        withTimeout(30_000) {
            Fixture().use { fixture ->
                fixture.afterCapture = { fixture.selectionRevoked = true }
                fixture.releaseAction = { error("fixture prune failure") }
                try { manager.refreshManagedServers(fixture.selection, fixture.version); fail("Selection must be revoked") }
                catch (error: EnterpriseConfigurationException) { assertEquals("enterprise_selection_revoked", error.message) }
                assertEquals(1, manager.runtimeCapabilities.value.size)
                fixture.afterCapture = null
                fixture.selectionRevoked = false
                fixture.releaseAction = {}
                manager.refreshManagedServers(fixture.selection, fixture.version)
                assertTrue(manager.runtimeCapabilities.value.isEmpty())
                assertEquals(2, fixture.releases.get())
            }
        }
    }

    @Test
    fun `realm shutdown finishes a concurrent manual refresh without waiting for its discovery deadline`() = runBlocking {
        withTimeout(30_000) {
            Fixture().use { fixture ->
                fixture.prepare()
                val previous = catalogs.value.values.single()
                val started = CompletableDeferred<Unit>()
                val gate = CountDownLatch(1)
                fixture.beforeList = { started.complete(Unit); check(gate.await(25, TimeUnit.SECONDS)) }
                val refreshing = async {
                    try { manager.refreshManagedServers(fixture.selection, fixture.version); null }
                    catch (error: EnterpriseConfigurationException) { error }
                }
                try {
                    started.await()
                    fixture.selectionRevoked = true
                    withTimeout(10_000) { manager.closeRealm(fixture.access) }
                    assertNotNull(refreshing.await())
                    assertEquals(previous, catalogs.value.values.single())
                    assertTrue(manager.runtimeCapabilities.value.isEmpty())
                    assertEquals(1, fixture.releases.get())
                } finally { gate.countDown() }
            }
        }
    }

    private inner class Fixture(serverCount: Int = 1) : AutoCloseable {
        val packet = exampleEnterprisePackage().let { packet ->
            val resource = packet.configuration.mcpServers.single()
            val resources = (1..serverCount).map { index ->
                if (index == 1) resource else resource.copy(id = "${resource.id}-$index", name = "${resource.name} $index")
            }
            packet.copy(configuration = packet.configuration.copy(mcpServers = resources,
                assistants = packet.configuration.assistants.map { assistant ->
                    assistant.copy(mcpBindings = assistant.mcpBindings.filter { binding -> resources.any { it.id == binding.mcpServerId } })
                }))
        }
        val access = RealmAccess.Enterprise(packet.identity.scope, "refresh-session")
        val selection = RealmSelection(access, 1)
        val version = EnterpriseAppliedVersion("refresh-revision", packet.configuration.generation, "configuration", "execution")
        val configuration = ConfigurationResolver.resolve(UserSettingsDocument.empty(), access.scope, appliedConfiguration(packet))
        val assistant = configuration.assistants.getValue(packet.identity.reference(packet.configuration.assistants.first().id))
        val lists = AtomicInteger()
        val calls = AtomicInteger()
        val releases = AtomicInteger()
        @Volatile var tools = tools(8, "old description", "old_parameter")
        @Volatile var beforeList: (() -> Unit)? = null
        @Volatile var selectionRevoked = false
        @Volatile var releaseAction: suspend () -> Unit = {}
        @Volatile var afterCapture: (() -> Unit)? = null
        @Volatile var listStatus = 200
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val scope = AppScope(Dispatchers.IO)
        val leases = mutableListOf<McpExecutionLease>()

        init {
            val state = appliedConfiguration(packet).let { it.copy(manifest = it.manifest.copy(applied = version)) }
            every { sessions.requirePublishedRealmAccess(access) } returns Unit
            every { sessions.requirePublishedSelection(selection) } answers {
                if (selectionRevoked) throw EnterpriseConfigurationException("enterprise_selection_revoked")
            }
            coEvery { sessions.withSelectedRealmSelection<Any?>(selection, any()) } coAnswers {
                sessions.requirePublishedSelection(selection)
                secondArg<suspend () -> Any?>().invoke()
            }
            coEvery { sessions.withAppliedConfiguration<Any?>(access, any()) } coAnswers {
                secondArg<suspend (EnterpriseState.Available) -> Any?>().invoke(state)
            }
            coEvery { settingsStore.withExecutionConfiguration<Any?>(access.scope, state, any()) } coAnswers {
                thirdArg<suspend (ExecutionConfigurationSnapshot) -> Any?>().invoke(
                    ExecutionConfigurationSnapshot(Settings(), configuration, "user"))
            }
            http.executor = executor
            http.createContext("/") { exchange -> exchange.use {
                if (it.requestMethod == "GET" || it.requestMethod == "DELETE") it.sendResponseHeaders(405, -1) else {
                    val request = Json.parseToJsonElement(it.requestBody.bufferedReader().readText()).jsonObject
                    val result = when (request.getValue("method").jsonPrimitive.content) {
                        "initialize" -> """{"protocolVersion":"2025-11-25","serverInfo":{"name":"refresh","version":"1"},"capabilities":{"tools":{}}}"""
                        "notifications/initialized" -> null
                        "tools/list" -> {
                            lists.incrementAndGet()
                            beforeList?.invoke()
                            buildJsonObject { put("tools", JsonArray(tools.map { tool -> tool.definition })) }.toString()
                        }
                        "tools/call" -> { calls.incrementAndGet(); """{"content":[]}""" }
                        else -> error("unexpected MCP request")
                    }
                    if (result == null) it.sendResponseHeaders(202, -1) else {
                        val body = """{"jsonrpc":"2.0","id":${request["id"]},"result":$result}""".toByteArray()
                        it.responseHeaders.set("Content-Type", "application/json")
                        val status = if (request.getValue("method").jsonPrimitive.content == "tools/list") listStatus else 200
                        it.sendResponseHeaders(status, body.size.toLong())
                        it.responseBody.write(body)
                    }
                }
            } }
            val candidate = platformCandidate(packet).let {
                val execution = it.execution as EnterpriseExecution.Platform
                it.copy(execution = execution.copy(
                    connection = execution.connection.copy(origin = "http://127.0.0.1:${http.address.port}"),
                    runtimePaths = packet.configuration.mcpServers.associate { resource ->
                        resource.id to execution.runtimePaths.values.first()
                    },
                ))
            }
            coEvery { sessions.captureExecution(access, version) } answers {
                EnterpriseExecutionLease(Uuid.random().toString(), access.sessionId, access.scope, version, candidate) {
                    releaseAction()
                    releases.incrementAndGet()
                }.also { afterCapture?.invoke() }
            }
            coEvery { platform.accessToken(access.sessionId, any()) } returns PlatformAccessToken("fixture-token", Long.MAX_VALUE)
            every { platform.managedRuntimeProblem(access, any(), any()) } returns null
            manager = McpRuntimeCoordinator(settingsStore, sessions, catalogStore, scope, mockk(relaxed = true),
                mockk { every { isOnline } returns MutableStateFlow(true) }, platform,
                foregroundObserver = ForegroundObserver {}, ioDispatcher = Dispatchers.IO,
                oauthCallbackKeepAlive = NoOpOAuthCallbackKeepAlive, foregroundState = MutableStateFlow(true))
            http.start()
        }

        suspend fun prepare(): TurnMcpCapabilitySnapshot {
            val owner = mockk<ConversationRuntime>(relaxed = true) { every { durable.header.scope } returns access.scope }
            val lease = slot<McpExecutionLease>()
            every { owner.bindMcpExecution(any(), any(), any(), capture(lease)) } answers { leases += lease.captured }
            val captured = CapturedModelConfiguration(Settings(), configuration, assistant,
                mockk { every { enterpriseVersion } returns version }, 0, Uuid.random())
            return manager.prepareTurnCapabilities(access, captured, owner, Uuid.random(), Job()) {}
        }

        override fun close() {
            try { runBlocking { leases.forEach { it.release() } } }
            finally {
                scope.cancel()
                http.stop(0)
                executor.shutdownNow()
            }
        }
    }

    private fun tools(count: Int, description: String, parameter: String) = (1..count).map { index ->
        McpCatalogTool("tool_$index", description, buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject(parameter) { put("type", "string") } }
        })
    }
}
