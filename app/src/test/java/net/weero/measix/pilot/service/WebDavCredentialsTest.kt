package net.weero.measix.pilot.service

import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.net.InetSocketAddress
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.configuration.WebDavConnection
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.webdav.WebDavClient
import net.weero.measix.pilot.service.webdav.WebDavApplicationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebDavCredentialsTest {
    @Test fun `anonymous connection test and browsing omit Authorization`() {
        assertAuthorization("", "", null)
    }

    @Test fun `partial credentials retain Basic authentication and password whitespace`() {
        assertAuthorization("user", "", "Basic dXNlcjo=")
        assertAuthorization("", " secret ", "Basic OiBzZWNyZXQg")
    }

    private fun assertAuthorization(username: String, password: String, expected: String?) = runTest {
        val authorization = Collections.synchronizedList(mutableListOf<String?>())
        val depths = Collections.synchronizedList(mutableListOf<String?>())
        val xml = """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/dav/") { exchange -> exchange.use {
                it.requestBody.readBytes()
                authorization += it.requestHeaders.getFirst("Authorization")
                depths += it.requestHeaders.getFirst("Depth")
                it.responseHeaders.set("Content-Type", "application/xml")
                it.sendResponseHeaders(207, xml.size.toLong())
                it.responseBody.write(xml)
            } }
            start()
        }
        try {
            val selection = RealmSelection(RealmAccess.Personal, 1)
            val connection = WebDavConnection(name = "", rootUrl = "http://127.0.0.1:${server.address.port}/dav/",
                username = username, password = password).validated()
            val configuration = mockk<ResolvedConfiguration> {
                every { personalWebDavConnections } returns listOf(connection)
                every { currentPersonalWebDavConnectionId } returns connection.id
            }
            val queries = mockk<ConfigurationQueryService> {
                every { observe(ConfigurationScope.Personal) } returns MutableStateFlow(configuration)
                every { observeSelectedRealmSelection() } returns MutableStateFlow(selection)
                coEvery { requireSelection(selection) } returns Unit
                coEvery { read(RealmAccess.Personal) } returns configuration
            }
            val service = WebDavApplicationService(queries, WebDavClient(), backgroundScope, mockk(), mockk(), mockk())
            service.testConnection(selection, connection)
            val handle = service.open(selection)
            try { assertTrue(service.list(handle).self?.directory == true) }
            finally { service.close(handle) }
            assertEquals(listOf("0", "1"), depths.toList())
            assertEquals("Connection test and ordinary browsing must share credential semantics",
                listOf(expected, expected), authorization.toList())
        } finally { server.stop(0) }
    }
}
