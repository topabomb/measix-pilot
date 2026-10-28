package net.weero.measix.pilot.data.ai.mcp

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class McpOAuthDiscoveryTest {
    @Test
    fun `IPv6 discovery preserves bracketed authority port and encoded resource path`() = runBlocking {
        val origin = "https://[2001:db8::1]:9443"
        val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requested += request.url.toString()
            val body = when (request.url.encodedPath) {
                "/.well-known/oauth-protected-resource/mcp%20api" -> """{"resource":"$origin/mcp%20api","authorization_servers":["$origin/issuer"]}"""
                "/.well-known/oauth-authorization-server/issuer" -> """{"issuer":"$origin/issuer","authorization_endpoint":"$origin/authorize","token_endpoint":"$origin/token"}"""
                else -> ""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (body.isEmpty()) 404 else 200).message("fixture").body(body.toResponseBody()).build()
        }.build()
        try {
            val client = McpOAuthClient(http)
            val metadata = client.discoverProtectedResource("$origin/mcp%20api")!!
            assertEquals("$origin/token", client.discoverAuthorizationServer(metadata.authorizationServers.single()).tokenEndpoint)
            assertEquals(listOf("$origin/mcp%20api", "$origin/.well-known/oauth-protected-resource/mcp%20api",
                "$origin/.well-known/oauth-authorization-server/issuer"), requested)
        } finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }

    @Test
    fun `only missing metadata permits trying the next discovery endpoint`() = runBlocking {
        for (status in listOf(404, 410, 401, 403, 500, 200)) {
            val requested = java.util.Collections.synchronizedList(mutableListOf<String>())
            val client = McpOAuthClient(OkHttpClient.Builder().addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                requested += path
                val code = if (path == "/mcp") 401 else status
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("fixture")
                    .body("{invalid metadata}".toResponseBody()).build()
            }.build())
            if (status == 404 || status == 410) {
                assertNull(client.discoverProtectedResource("https://example.com/mcp"))
                assertEquals(3, requested.size)
            } else {
                try {
                    client.discoverProtectedResource("https://example.com/mcp")
                    fail("status=$status must fail rather than fall back")
                } catch (error: Exception) {
                    if (status != 200) assertEquals(status, (error as McpOAuthResponseException).statusCode)
                }
                assertEquals(2, requested.size)
            }
        }
    }

    @Test
    fun `missing PRM can discover issuer at the original resource origin`() = runBlocking {
        val client = McpOAuthClient(OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val body = if (path == "/.well-known/oauth-authorization-server") """{
                "issuer":"https://example.com","authorization_endpoint":"https://example.com/authorize",
                "token_endpoint":"https://example.com/token"
            }""" else ""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (body.isEmpty()) 404 else 200).message("fixture").body(body.toResponseBody()).build()
        }.build())
        assertNull(client.discoverProtectedResource("https://example.com/mcp"))
        val metadata = client.discoverAuthorizationServer(McpOAuthClient.resourceOrigin("https://example.com/mcp"))
        assertEquals("https://example.com/token", metadata.tokenEndpoint)
    }
}
