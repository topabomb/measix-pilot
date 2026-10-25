package net.weero.measix.pilot.service

import android.content.Context
import com.sun.net.httpserver.HttpServer
import io.mockk.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import me.rerere.ai.core.ToolInteractionRequirement
import net.weero.measix.pilot.data.ai.tools.createWebDavTools
import net.weero.measix.pilot.data.configuration.*
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.sync.BackupArchiveService
import net.weero.measix.pilot.data.sync.BackupSelection
import net.weero.measix.pilot.data.webdav.*
import net.weero.measix.pilot.service.remoteworkspace.*
import net.weero.measix.pilot.service.webdav.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.net.Uri
import androidx.media3.datasource.DataSpec
import net.weero.measix.pilot.ui.components.files.MediaPreviewDataSource
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnterpriseWebDavServiceTest {
    private val id = "dav_12345678-1234-4234-8234-123456789abc"
    private val otherId = "dav_22345678-1234-4234-8234-123456789abc"
    private val space = "spc_12345678-1234-4234-8234-123456789abc"
    private val access = RealmAccess.Enterprise(exampleEnterprisePackage().identity.scope, "session")
    private val selection = RealmSelection(access, 1)
    private fun definition(id: String) = EnterpriseWebDavResource(id, id, true,
        PlatformWebDAVDefinitionPurpose.WORKSPACE_FILE_EXCHANGE, PlatformWebDAVDefinitionClientProtocol.WEBDAV,
        PlatformWebDAVDefinitionAuthOwnership.ENTERPRISE_MANAGED, "/runtime/v1/resources/$id/spaces/{agentSpaceId}/dav/")
    private val ready = PlatformWorkspaceProjection(fileExchangeWebdavResourceId = id,
        webdav = PlatformWorkspaceDAVProjection(id, true, "ready", PlatformDAVAccessMode.FULL, 3),
        schemaVersion = 1, state = PlatformWorkspaceProjectionState.CONNECTED, bindingRevision = 9, agentSpaceId = space,
        mcpAvailable = false, filesAvailable = false, mcpReason = "disabled", filesReason = "disabled",
        serviceState = PlatformWorkspaceProjectionServiceState.ENABLED)

    private inner class Fixture(scope: CoroutineScope, origin: String = "https://core.example") {
        val connection = PlatformConnection(origin, PlatformDiscovery(PlatformDiscoveryProduct.MEASIX_AGENT_PLATFORM,
            "1", "dep_12345678-1234-4234-8234-123456789abc", "Core", "/api/client/v1", "/runtime/v1", listOf(5)))
        var definitions = listOf(definition(otherId), definition(id))
        var projection = ready
        var publishedProjection = ready
        var backupPreferences = WebDavBackupPreferences()
        val configuration = mockk<ResolvedConfiguration> {
            every { enterpriseConfiguration } answers { mockk { every { webdav } answers { definitions } } }
            every { webDavBackup } answers { backupPreferences }
        }
        val personal = mockk<ResolvedConfiguration> { every { personalWebDavConnections } returns emptyList() }
        val queries = mockk<ConfigurationQueryService> {
            every { observe(ConfigurationScope.Personal) } returns flowOf(personal)
            every { observeSelectedRealmSelection() } returns flowOf(selection)
            coEvery { requireSelection(selection) } returns Unit
            coEvery { requireAccess(access) } returns Unit
            coEvery { read(access) } returns configuration
        }
        val remote = mockk<RemoteWorkspaceService> {
            coEvery { readDavProjection(access) } answers { WorkspaceDavSnapshot(access, connection, projection) }
            coEvery { readPublishedDavProjection(selection) } answers { WorkspaceDavSnapshot(access, connection, publishedProjection) }
            coEvery { refresh(selection) } returns Unit
        }
        val lease = mockk<EnterprisePlatformOperationLease> {
            every { this@mockk.access } returns this@EnterpriseWebDavServiceTest.access
            every { context.platform.connection } returns this@Fixture.connection
            coEvery { release() } returns Unit
        }
        val sessions = mockk<EnterpriseSessionController> { coEvery { capturePlatformOperation(access.sessionId) } returns lease }
        val first = PlatformAccessToken("first-token", Long.MAX_VALUE)
        val replacement = PlatformAccessToken("replacement-token", Long.MAX_VALUE)
        val platform = mockk<PlatformEnterpriseService> {
            coEvery { accessToken(access.sessionId, connection) } returns first
            coEvery { recoverWorkspaceAccessToken(access, connection, first) } returns replacement
            coEvery { workspaceFailure(access, any()) } returns Unit
        }
        val service = WebDavApplicationService(queries, WebDavClient(), scope, sessions, platform, remote)
    }

    @Test fun `fresh target capture reuses one observation but retained targets revalidate remotely`() = runTest {
        val f = Fixture(backgroundScope)
        val handle = f.service.open(selection)
        coVerify(exactly = 1) { f.remote.readDavProjection(access) }
        f.service.close(handle)
        f.projection = ready.copy(webdav = ready.webdav!!.copy(bindingRevision = 4))
        try { f.service.captureExpected(access, handle.target, selection); fail("Old binding must not be recaptured") }
        catch (failure: IllegalStateException) { assertEquals("webdav_binding_changed", failure.message) }
        coVerify(exactly = 2) { f.remote.readDavProjection(access) }
    }

    @Test fun `fresh capture cannot replace a newer published binding with its observation`() = runTest {
        val f = Fixture(backgroundScope)
        f.publishedProjection = ready.copy(webdav = ready.webdav!!.copy(bindingRevision = 4))
        try { f.service.open(selection); fail("Older observation must not authorize the captured target") }
        catch (failure: IllegalStateException) { assertEquals("webdav_binding_changed", failure.message) }
        coVerify(exactly = 1) { f.remote.readDavProjection(access) }
    }

    @Test fun `optional absent DAV never queries workspace and designation never falls back to first resource`() = runTest {
        val f = Fixture(backgroundScope)
        f.definitions = emptyList()
        assertNull(f.service.currentTarget(access, f.configuration))
        coVerify(exactly = 0) { f.remote.readDavProjection(any()) }
        f.definitions = listOf(definition(otherId), definition(id))
        assertEquals(id, f.service.currentTarget(access, f.configuration)?.id)
        f.projection = ready.copy(fileExchangeWebdavResourceId = null)
        assertNull(f.service.currentTarget(access, f.configuration))
        try { f.service.open(selection, otherId); fail("Unbound resource must not acquire another resource's space") }
        catch (failure: IllegalStateException) { assertEquals("webdav_resource_unavailable", failure.message) }
    }

    @Test fun `optional DAV query failure does not block tool assembly and cancellation or session loss still propagates`() = runTest {
        val f = Fixture(backgroundScope)
        val transfers = WebDavTransferService(f.service, mockk(), ApplicationRecoveryGate().apply { ready() })
        coEvery { f.remote.readDavProjection(access) } throws IOException("status unavailable")
        assertTrue(createWebDavTools(access, f.configuration, transfers).isEmpty())
        try { f.service.open(selection); fail("Explicit browser open must preserve the original HTTP diagnosis") }
        catch (failure: IOException) { assertEquals("status unavailable", failure.message) }

        val malformed = requireNotNull(runCatching {
            PlatformWireCodec.decode<PlatformWorkspaceProjection>("""{"schemaVersion":"invalid"}""")
        }.exceptionOrNull())
        coEvery { f.remote.readDavProjection(access) } throws malformed
        assertTrue(createWebDavTools(access, f.configuration, transfers).isEmpty())
        coEvery { f.remote.readDavProjection(access) } returns WorkspaceDavSnapshot(access, f.connection, ready)
        assertEquals(2, createWebDavTools(access, f.configuration, transfers).size)
        coEvery { f.remote.readDavProjection(access) } throws EnterpriseConfigurationException("enterprise_selection_revoked")
        coEvery { f.queries.requireAccess(access) } throws EnterpriseConfigurationException("enterprise_selection_revoked")
        try { createWebDavTools(access, f.configuration, transfers); fail("Session failure swallowed") }
        catch (_: EnterpriseConfigurationException) { }
        coEvery { f.remote.readDavProjection(access) } throws CancellationException("cancelled")
        try { createWebDavTools(access, f.configuration, transfers); fail("Cancellation swallowed") }
        catch (_: CancellationException) { }
    }

    @Test fun `readonly still exposes both tools without workspace but upload rejects before HTTP`() = runTest {
        val f = Fixture(backgroundScope)
        f.projection = ready.copy(webdav = ready.webdav!!.copy(accessMode = PlatformDAVAccessMode.READ_ONLY))
        val tools = createWebDavTools(access, f.configuration, WebDavTransferService(f.service, mockk(), ApplicationRecoveryGate().apply { ready() }))
        assertEquals(listOf("webdav_upload", "webdav_download"), tools.map { it.name })
        tools.forEach { assertEquals(ToolInteractionRequirement.None, it.interactionRequirement(kotlinx.serialization.json.JsonObject(emptyMap()))) }
        val handle = f.service.open(selection)
        assertFalse(handle.writable)
        try { f.service.upload(handle, "file", 1, { "x".byteInputStream() }); fail("Readonly upload accepted") }
        catch (failure: IllegalStateException) { assertEquals("webdav_read_only", failure.message) }
        coVerify(exactly = 0) { f.sessions.capturePlatformOperation(any()) }
        f.projection = ready.copy(webdav = ready.webdav!!.copy(available = false, reason = "temporarily_unavailable"))
        assertEquals(2, createWebDavTools(access, f.configuration, WebDavTransferService(f.service, mockk(), ApplicationRecoveryGate().apply { ready() })).size)
    }

    @Test fun `transient permission query failure preserves validity uncertainty while changed binding revokes original handle`() = runTest {
        val f = Fixture(backgroundScope)
        val handle = f.service.open(selection)
        coEvery { f.remote.readDavProjection(access) } throws IOException("network disconnected")
        try { f.service.isValid(handle); fail("Transient query must not report definite revocation") }
        catch (failure: IOException) { assertEquals("network disconnected", failure.message) }
        coEvery { f.remote.readDavProjection(access) } throws EnterpriseConfigurationException("invalid_platform_field_type")
        try { f.service.isValid(handle); fail("Malformed optional projection must not discard the original editor") }
        catch (failure: EnterpriseConfigurationException) { assertEquals("invalid_platform_field_type", failure.reason) }
        coEvery { f.remote.readDavProjection(access) } returns WorkspaceDavSnapshot(access, f.connection,
            ready.copy(webdav = ready.webdav!!.copy(bindingRevision = 4)))
        assertFalse(f.service.isValid(handle))
    }

    @Test fun `Bearer read refreshes once only for unforwarded authentication rejection`() = runTest {
        val headers = CopyOnWriteArrayList<String>()
        val paths = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { request -> request.use {
                headers += it.requestHeaders.getFirst("Authorization")
                paths += it.requestURI.path
                if (headers.size == 1) {
                    val body = problem(401, "invalid_session", false).toByteArray()
                    it.responseHeaders.set("Content-Type", "application/problem+json")
                    it.sendResponseHeaders(401, body.size.toLong()); it.responseBody.write(body)
                } else {
                    it.sendResponseHeaders(200, 4); it.responseBody.write("data".toByteArray())
                }
            } }; start()
        }
        try {
            val f = Fixture(backgroundScope, "http://127.0.0.1:${server.address.port}")
            val handle = f.service.open(selection)
            clearMocks(f.remote, answers = false)
            val output = ByteArrayOutputStream()
            assertEquals(4L, f.service.download(handle, "file", output))
            assertEquals("data", output.toString())
            assertEquals(listOf("Bearer first-token", "Bearer replacement-token"), headers)
            assertTrue(paths.all { it == "/runtime/v1/resources/$id/spaces/$space/dav/file" })
            coVerify(exactly = 1) { f.platform.recoverWorkspaceAccessToken(access, f.connection, f.first) }
            coVerify(exactly = 1) { f.lease.release() }
            coVerify(exactly = 3) { f.remote.readDavProjection(access) }
        } finally { server.stop(0) }
    }

    @Test fun `token wait cannot retain a revoked selection changed binding or old write permission`() = runTest {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { request -> request.use { requests.incrementAndGet(); it.sendResponseHeaders(204, -1) } }
            start()
        }
        try {
            for (change in listOf("selection", "binding", "readonly")) {
                val f = Fixture(backgroundScope, "http://127.0.0.1:${server.address.port}")
                val handle = f.service.open(selection)
                val entered = CompletableDeferred<Unit>()
                val resume = CompletableDeferred<Unit>()
                coEvery { f.platform.accessToken(access.sessionId, f.connection) } coAnswers {
                    entered.complete(Unit); resume.await(); f.first
                }
                val operation = async {
                    runCatching { f.service.upload(handle, "file", 1, { byteArrayOf(1).inputStream() }) }
                }
                entered.await()
                when (change) {
                    "selection" -> coEvery { f.queries.requireSelection(selection) } throws EnterpriseConfigurationException("enterprise_selection_revoked")
                    "binding" -> f.publishedProjection = ready.copy(webdav = ready.webdav!!.copy(bindingRevision = 4))
                    else -> f.publishedProjection = ready.copy(webdav = ready.webdav!!.copy(accessMode = PlatformDAVAccessMode.READ_ONLY))
                }
                resume.complete(Unit)
                assertTrue(operation.await().isFailure)
                assertEquals(0, requests.get())
                assertTrue(f.service.unknownPaths(handle).isEmpty())
                coVerify(exactly = 1) { f.lease.release() }
                f.service.close(handle)
            }
        } finally { server.stop(0) }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun `buffered video reads do not query the remote workspace again`() = runTest {
        val rangeRequests = AtomicInteger()
        val size = 1024 * 1024
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { request -> request.use {
                it.responseHeaders.set("ETag", "\"media-v1\"")
                if (it.requestMethod == "HEAD") {
                    it.responseHeaders.set("Content-Length", size.toString())
                    it.sendResponseHeaders(200, -1)
                } else {
                    rangeRequests.incrementAndGet()
                    val bounds = it.requestHeaders.getFirst("Range").removePrefix("bytes=").split('-').map(String::toInt)
                    val count = bounds[1] - bounds[0] + 1
                    it.responseHeaders.set("Content-Range", "bytes ${bounds[0]}-${bounds[1]}/$size")
                    it.sendResponseHeaders(206, count.toLong())
                    it.responseBody.write(ByteArray(count) { 42 })
                }
            } }; start()
        }
        val root = Files.createTempDirectory("enterprise-dav-preview").toFile()
        try {
            val f = Fixture(backgroundScope, "http://127.0.0.1:${server.address.port}")
            every { f.queries.observeWebDavConfiguration() } returns flowOf(
                WebDavConfigurationReadState.Available(selection, emptyList(), null))
            coEvery { f.remote.refresh(selection) } returns Unit
            val browser = WebDavBrowserService(f.service, f.queries, ApplicationRecoveryGate().apply { ready() }, backgroundScope, root)
            val session = browser.source(selection).open()
            val source = session.mediaSource(net.weero.measix.pilot.service.files.BrowserFile("video.mp4", false, size.toLong(), null, "\"media-v1\""))
            val data = MediaPreviewDataSource(source)
            try {
                data.open(DataSpec.Builder().setUri(Uri.parse("workspace-media:/video")).build())
                clearMocks(f.remote, answers = false)
                assertEquals(1, data.read(ByteArray(1), 0, 1))
                coVerify(exactly = 2) { f.remote.readDavProjection(access) }
                clearMocks(f.remote, answers = false)
                repeat(32) {
                    val bytes = ByteArray(1024)
                    assertEquals(bytes.size, data.read(bytes, 0, bytes.size))
                    assertTrue(bytes.all { it == 42.toByte() })
                }
                assertEquals(1, rangeRequests.get())
                coVerify(exactly = 0) { f.remote.readDavProjection(any()) }
                f.publishedProjection = ready.copy(webdav = ready.webdav!!.copy(bindingRevision = 4))
                val denied = ByteArray(1) { -1 }
                org.junit.Assert.assertThrows(IOException::class.java) { data.read(denied, 0, 1) }
                assertArrayEquals(byteArrayOf(-1), denied)
                assertEquals(1, rangeRequests.get())
                coVerify(exactly = 0) { f.remote.readDavProjection(any()) }
            } finally { data.close(); source.close(); session.close() }
        } finally { server.stop(0); root.deleteRecursively() }
    }

    @Test fun `PUT rejection refreshes credentials without replay and unknown writes block subsequent attempts`() = runTest {
        for ((status, code, forwarded) in listOf(Triple(401, "invalid_session", false), Triple(502, "workspace_credential_unavailable", true))) {
            val requests = CopyOnWriteArrayList<String>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { request -> request.use {
                    requests += it.requestBody.readBytes().decodeToString()
                    val body = problem(status, code, forwarded).toByteArray()
                    it.responseHeaders.set("Content-Type", "application/problem+json")
                    it.sendResponseHeaders(status, body.size.toLong()); it.responseBody.write(body)
                } }; start()
            }
            try {
                val f = Fixture(backgroundScope, "http://127.0.0.1:${server.address.port}")
                val handle = f.service.open(selection)
                try { f.service.upload(handle, "file", 4, { "data".byteInputStream() }); fail("Write rejection hidden") }
                catch (failure: PlatformHttpException) { assertEquals(status, failure.status) }
                assertEquals(listOf("data"), requests)
                assertEquals(if (forwarded) setOf("file") else emptySet<String>(), f.service.unknownPaths(handle))
                coVerify(exactly = if (forwarded) 0 else 1) { f.platform.recoverWorkspaceAccessToken(any(), any(), any()) }
                coVerify(exactly = 1) { f.lease.release() }
                if (forwarded) {
                    try { f.service.upload(handle, "file", 4, { "data".byteInputStream() }); fail("Unknown write replayed") }
                    catch (failure: IllegalStateException) { assertEquals("webdav_result_requires_verification", failure.message) }
                    assertEquals(1, requests.size)
                }
            } finally { server.stop(0) }
        }
    }

    @Test fun `connection change cancels active DAV and waits for platform lease cleanup before committing route`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val serverRelease = java.util.concurrent.CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { request -> request.use {
                it.sendResponseHeaders(200, 4)
                entered.complete(Unit)
                serverRelease.await(20, java.util.concurrent.TimeUnit.SECONDS)
            } }; start()
        }
        val cleanupStarted = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()
        try {
            val f = Fixture(backgroundScope, "http://127.0.0.1:${server.address.port}")
            coEvery { f.lease.release() } coAnswers {
                withContext(NonCancellable) { cleanupStarted.complete(Unit); cleanupRelease.await() }
            }
            val handle = f.service.open(selection)
            val transfer = launch { f.service.download(handle, "file", ByteArrayOutputStream()) }
            entered.await()
            var committed = false
            val change = async { f.service.changeConnection(access) { committed = true } }
            cleanupStarted.await()
            assertFalse(committed)
            assertFalse(change.isCompleted)
            assertTrue(handle.closed.get())
            try { f.service.open(selection); fail("New IO admitted during connection change") }
            catch (failure: IllegalStateException) { assertEquals("webdav_connection_change_in_progress", failure.message) }
            cleanupRelease.complete(Unit)
            change.await(); transfer.join()
            assertTrue(committed)
            assertTrue(transfer.isCancelled)
            coVerify(exactly = 1) { f.lease.release() }
        } finally { cleanupRelease.complete(Unit); serverRelease.countDown(); server.stop(0) }
    }

    @Test fun `enterprise backup requires an explicit bound resource and sends archive through enterprise DAV`() = runTest {
        val cache = Files.createTempDirectory("enterprise-dav-backup").toFile()
        val context = mockk<Context> {
            every { applicationContext } returns this
            every { cacheDir } returns cache
        }
        val requests = CopyOnWriteArrayList<List<String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { request -> request.use {
                requests += listOf(it.requestMethod, it.requestURI.path,
                    it.requestHeaders.getFirst("Authorization"), it.requestHeaders.getFirst("If-None-Match"),
                    it.requestBody.readBytes().decodeToString())
                it.sendResponseHeaders(201, -1)
            } }; start()
        }
        try {
            val f = Fixture(backgroundScope, "http://127.0.0.1:${server.address.port}")
            val archives = mockk<BackupArchiveService>()
            val backup = WebDavBackupService(context, f.service, f.queries, archives)
            try { backup.open(selection, f.backupPreferences); fail("Empty backup choice must not use designated DAV") }
            catch (failure: IllegalArgumentException) { assertEquals("webdav_backup_resource_unavailable", failure.message) }
            coVerify(exactly = 0) { f.remote.readDavProjection(any()) }
            f.backupPreferences = WebDavBackupPreferences(otherId, path = "")
            try { backup.open(selection, f.backupPreferences); fail("Unbound choice must not use designated DAV") }
            catch (failure: IllegalStateException) { assertEquals("webdav_resource_unavailable", failure.message) }
            assertTrue(requests.isEmpty())
            coVerify(exactly = 0) { archives.prepare(any()) }

            f.backupPreferences = WebDavBackupPreferences(id, path = "", includeDurableAggregate = false)
            val target = backup.open(selection, f.backupPreferences)
            try {
                assertEquals(id, target.handle.target.id)
                assertEquals(access, target.handle.access)
                val archive = File(cache, "backup_owned.zip").apply { writeText("archive bytes") }
                coEvery { archives.prepare(BackupSelection(false, false)) } returns archive
                backup.backup(target)
                assertEquals(listOf(listOf("PUT", "/runtime/v1/resources/$id/spaces/$space/dav/backup_owned.zip",
                    "Bearer first-token", "*", "archive bytes")), requests)
                assertFalse(archive.exists())
                assertEquals(id, f.projection.fileExchangeWebdavResourceId)
                coVerify(exactly = 1) { archives.prepare(BackupSelection(false, false)) }
                coVerify(exactly = 1) { f.lease.release() }
            } finally { backup.close(target) }
        } finally { server.stop(0); cache.deleteRecursively() }
    }

    @Test fun `readonly enterprise backup rejects before preparing archive or acquiring HTTP lease`() = runTest {
        val cache = Files.createTempDirectory("enterprise-dav-readonly-backup").toFile()
        try {
            val context = mockk<Context> {
                every { applicationContext } returns this
                every { cacheDir } returns cache
            }
            val f = Fixture(backgroundScope)
            f.projection = ready.copy(webdav = ready.webdav!!.copy(accessMode = PlatformDAVAccessMode.READ_ONLY))
            f.backupPreferences = WebDavBackupPreferences(id)
            val archives = mockk<BackupArchiveService>()
            val backup = WebDavBackupService(context, f.service, f.queries, archives)
            val target = backup.open(selection, f.backupPreferences)
            try {
                try { backup.backup(target); fail("Readonly backup accepted") }
                catch (failure: IllegalStateException) { assertEquals("webdav_read_only", failure.message) }
                coVerify(exactly = 0) { archives.prepare(any()) }
                coVerify(exactly = 0) { f.sessions.capturePlatformOperation(any()) }
                assertTrue(cache.listFiles()!!.isEmpty())
            } finally { backup.close(target) }
        } finally { cache.deleteRecursively() }
    }

    @Test fun `enterprise backup restore rejects changed binding at publication and removes downloaded archive`() = runTest {
        val cache = Files.createTempDirectory("enterprise-dav-restore").toFile()
        val requests = CopyOnWriteArrayList<List<String>>()
        val bytes = "downloaded archive bytes".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { request -> request.use {
                requests += listOf(it.requestMethod, it.requestURI.path,
                    it.requestHeaders.getFirst("Authorization"), it.requestHeaders.getFirst("If-Match"))
                it.responseHeaders.set("ETag", "\"v1\"")
                it.sendResponseHeaders(200, bytes.size.toLong()); it.responseBody.write(bytes)
            } }; start()
        }
        try {
            val context = mockk<Context> {
                every { applicationContext } returns this
                every { cacheDir } returns cache
            }
            val f = Fixture(backgroundScope, "http://127.0.0.1:${server.address.port}")
            f.backupPreferences = WebDavBackupPreferences(id, path = "copies", includeDurableAggregate = false)
            val archives = mockk<BackupArchiveService>()
            val backup = WebDavBackupService(context, f.service, f.queries, archives)
            val target = backup.open(selection, f.backupPreferences)
            try {
                val item = WebDavBackupItem(target, "copies/backup_original.zip", "backup_original.zip",
                    bytes.size.toLong(), Instant.EPOCH, "\"v1\"")
                var staged: File? = null
                var published = false
                coEvery { archives.stageRestore(any(), BackupSelection(false, false), any()) } coAnswers {
                    staged = firstArg<File>()
                    assertArrayEquals(bytes, staged!!.readBytes())
                    f.projection = ready.copy(webdav = ready.webdav!!.copy(bindingRevision = 4))
                    thirdArg<suspend (suspend () -> Unit) -> Unit>().invoke { published = true }
                }
                try { backup.restore(item); fail("Changed enterprise binding must not publish restore") }
                catch (failure: IllegalStateException) { assertEquals("webdav_binding_changed", failure.message) }
                assertEquals(listOf(listOf("GET", "/runtime/v1/resources/$id/spaces/$space/dav/copies/backup_original.zip",
                    "Bearer first-token", "\"v1\"")), requests)
                assertNotNull(staged)
                assertFalse(published)
                assertFalse(staged!!.exists())
                assertTrue(cache.listFiles()!!.isEmpty())
                coVerify(exactly = 1) { archives.stageRestore(any(), BackupSelection(false, false), any()) }
                coVerify(exactly = 1) { f.lease.release() }
            } finally { backup.close(target) }
        } finally { server.stop(0); cache.deleteRecursively() }
    }

    private fun problem(status: Int, code: String, forwarded: Boolean) =
        """{"type":"about:blank","title":"DAV error","status":$status,"code":"$code","forwarded":$forwarded}"""
}
