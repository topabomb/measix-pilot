package net.weero.measix.pilot.service

import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.net.InetSocketAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.configuration.WebDavConnection
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.webdav.DavHttpException
import net.weero.measix.pilot.data.webdav.WebDavClient
import net.weero.measix.pilot.service.webdav.WebDavApplicationService
import org.junit.Assert.*
import org.junit.Test

class WebDavApplicationServiceTest {
    private val selection = RealmSelection(RealmAccess.Personal, 1)
    private fun configuration(connections: List<WebDavConnection>, current: WebDavConnection?): ResolvedConfiguration = mockk {
        every { personalWebDavConnections } returns connections
        every { currentPersonalWebDavConnectionId } returns current?.id
    }

    @Test fun `unknown writes survive reopening and remain on the original target until acknowledged`() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> exchange.use {
                it.requestBody.readBytes()
                it.sendResponseHeaders(if (it.requestMethod == "PROPFIND") 404 else 503, -1)
            } }
            start()
        }
        try {
            val first = WebDavConnection(name = "First", rootUrl = "http://127.0.0.1:${server.address.port}/dav/")
            val second = WebDavConnection(name = "Second", rootUrl = "https://other.example/")
            val configurations = MutableStateFlow(configuration(listOf(first, second), first))
            val selections = MutableStateFlow<RealmSelection?>(selection)
            val queries = mockk<ConfigurationQueryService> {
                every { observe(ConfigurationScope.Personal) } returns configurations
                every { observeSelectedRealmSelection() } returns selections
                coEvery { requireSelection(selection) } returns Unit
                coEvery { read(RealmAccess.Personal) } coAnswers { configurations.value }
            }
            val service = WebDavApplicationService(queries, WebDavClient(), backgroundScope, mockk(), mockk(), mockk())
            val original = service.open(selection)
            try {
                service.upload(original, "report.txt", 4, { "test".byteInputStream() })
                fail("503 must not become success")
            } catch (error: DavHttpException) { assertEquals(503, error.status) }
            assertEquals(setOf("report.txt"), service.unknownPaths(original))
            service.close(original)
            val reopened = service.open(selection)
            assertEquals(setOf("report.txt"), service.unknownPaths(reopened))
            try {
                service.upload(reopened, "report.txt", 4, { "test".byteInputStream() })
                fail("Unknown write must not replay")
            } catch (error: IllegalStateException) { assertEquals("webdav_result_requires_verification", error.message) }
            val other = service.open(selection, second.id.toString())
            assertTrue(service.unknownPaths(other).isEmpty())
            configurations.value = configuration(listOf(first.copy(name = "Renamed"), second), second)
            runCurrent()
            assertFalse(reopened.closed.get())
            configurations.value = configuration(listOf(first.copy(password = "changed"), second), second)
            runCurrent()
            assertTrue(reopened.closed.get())
            val rotated = service.open(selection, first.id.toString())
            assertEquals(setOf("report.txt"), service.unknownPaths(rotated))
            try {
                service.acknowledgeUnknown(rotated, setOf("report.txt"))
                fail("Acknowledgement requires a fresh read")
            } catch (error: IllegalStateException) { assertEquals("webdav_read_target_before_confirmation", error.message) }
            assertNull(service.verifyUnknown(rotated, "report.txt"))
            service.acknowledgeUnknown(rotated, setOf("report.txt"))
            assertTrue(service.unknownPaths(rotated).isEmpty())
            service.close(rotated)
            service.close(other)
        } finally { server.stop(0) }
    }

    @Test fun `explicit browser choice never changes current tool target and realm switch closes old page`() = runTest {
        val first = WebDavConnection(name = "First", rootUrl = "https://first.example/")
        val second = WebDavConnection(name = "Second", rootUrl = "https://second.example/")
        val configurations = MutableStateFlow(configuration(listOf(first, second), first))
        val selections = MutableStateFlow<RealmSelection?>(selection)
        val queries = mockk<ConfigurationQueryService> {
            every { observe(ConfigurationScope.Personal) } returns configurations
            every { observeSelectedRealmSelection() } returns selections
            coEvery { requireSelection(selection) } returns Unit
            coEvery { read(RealmAccess.Personal) } coAnswers { configurations.value }
        }
        val service = WebDavApplicationService(queries, WebDavClient(), backgroundScope, mockk(), mockk(), mockk())
        val page = service.open(selection, second.id.toString())
        val turn = service.capture(RealmAccess.Personal)
        assertEquals(second, (page.target as net.weero.measix.pilot.service.webdav.WebDavTarget.Personal).connection)
        assertEquals(first, (turn.target as net.weero.measix.pilot.service.webdav.WebDavTarget.Personal).connection)
        selections.value = RealmSelection(RealmAccess.Personal, 3)
        runCurrent()
        assertTrue(page.closed.get())
        assertFalse(turn.closed.get())
        service.close(turn)
    }
}
