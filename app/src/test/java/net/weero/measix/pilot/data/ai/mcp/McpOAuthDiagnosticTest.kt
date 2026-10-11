package net.weero.measix.pilot.data.ai.mcp

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class McpOAuthDiagnosticTest {
    @Test
    fun standardErrorsKeepStatusAndCodeWithoutEchoedCredentials() = runBlocking {
        for (status in listOf(200, 400)) {
            val client = client(status, """{"error":"invalid_grant","error_description":"refresh_token=refresh-secret; rejected verifier-secret and client-secret"}""")
            val error = failure {
                client.exchangeCode("https://example.com/token?token=query-secret", "client", "client-secret",
                    "refresh-secret", "verifier-secret", "http://localhost/callback", null)
            }
            assertEquals(status, (error as McpOAuthResponseException).statusCode)
            assertEquals("invalid_grant", error.errorCode)
            assertTrue(McpProtocolFailureClassifier.isUnauthorized(error))
            assertFalse(McpProtocolFailureClassifier.isConnectionError(error))
            assertSafe(error, "refresh-secret", "verifier-secret", "client-secret", "query-secret")
            assertTrue(error.message.orEmpty().contains("rejected"))
        }
    }

    @Test
    fun endpointAndMalformedResponseDiagnosticsAreSanitized() = runBlocking {
        val httpError = failure {
            client(503, "temporary unavailable").refreshToken(
                "https://example.com/token?access_token=query-secret", "client", null, "refresh-secret", null, null)
        }
        assertSafe(httpError, "query-secret", "refresh-secret")
        assertTrue(McpProtocolFailureClassifier.isConnectionError(httpError))
        assertTrue(httpError.message.orEmpty().contains("503"))
        assertTrue(httpError.message.orEmpty().contains("example.com/token"))
        val malformed = """{"client_secret":"registration-secret","client_id":[]} trailing"""
        val registration = failure {
            client(200, malformed).registerClient("https://example.com/register", "test", "http://localhost/callback", null)
        }
        assertSafe(registration, "registration-secret")
        assertTrue(registration.stackTraceToString().contains("JsonDecodingException"))
        val metadata = failure {
            client(200, """{"client_secret":"metadata-secret","issuer":[]} trailing""")
                .discoverAuthorizationServer("https://example.com")
        }
        assertSafe(metadata, "metadata-secret")
        assertTrue(metadata.stackTraceToString().contains("JsonDecodingException"))
    }

    @Test
    fun transportCausesKeepTheirIdentityAndStacksButNotCredentials() = runBlocking {
        val client = McpOAuthClient(OkHttpClient.Builder().addInterceptor {
            throw IOException("server rejected refresh-secret", IllegalStateException("client_secret=client-secret"))
                .also { it.addSuppressed(IOException("code_verifier=verifier-secret")) }
        }.build())
        val error = failure {
            client.refreshToken("https://example.com/token", "client", "client-secret", "refresh-secret", null, null)
        }
        assertSafe(error, "refresh-secret", "client-secret", "verifier-secret")
        assertTrue(error is IOException)
        assertTrue(McpProtocolFailureClassifier.isConnectionError(error))
        val diagnostic = error.stackTraceToString()
        assertTrue(diagnostic.contains("java.io.IOException"))
        assertTrue(diagnostic.contains("java.lang.IllegalStateException"))
        assertTrue(diagnostic.contains("Suppressed:"))
        assertTrue(diagnostic.contains("Caused by:"))
        assertTrue(diagnostic.contains("McpOAuthDiagnosticTest"))
    }

    @Test
    fun cancellationIsNotConvertedIntoAnOAuthFailure() = runBlocking {
        val cancelled = CancellationException("cancelled")
        val client = McpOAuthClient(OkHttpClient.Builder().addInterceptor { throw cancelled }.build())
        val error = failure {
            client.refreshToken("https://example.com/token", "client", null, "refresh-secret", null, null)
        }
        assertTrue(error is CancellationException)
        assertEquals("cancelled", error.message)
    }

    private fun client(status: Int, body: String) = McpOAuthClient(OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
            .body(body.toResponseBody()).build()
    }.build())

    private suspend fun failure(action: suspend () -> Unit): Throwable = try {
        action()
        error("Expected failure")
    } catch (error: Throwable) {
        assertFalse("The operation unexpectedly succeeded", error.message == "Expected failure")
        error
    }

    private fun assertSafe(error: Throwable, vararg secrets: String) {
        val diagnostic = error.stackTraceToString()
        secrets.forEach { assertFalse(diagnostic, diagnostic.contains(it)) }
    }
}
