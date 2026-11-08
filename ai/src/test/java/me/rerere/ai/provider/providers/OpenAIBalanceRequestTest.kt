package me.rerere.ai.provider.providers

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.BalanceMethod
import me.rerere.ai.provider.BalanceOption
import me.rerere.ai.provider.InvalidBalanceOption
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.HttpException
import me.rerere.common.http.PrivateRequestBoundaryInterceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIBalanceRequestTest {
    @Test
    fun legacyGetIsBodylessAndFormatsDoubleAfterExpression() = runBlocking {
        val legacy = Json.decodeFromString<BalanceOption>("""{"enabled":true,"resultPath":"units * 1000 + total"}""")
        assertEquals(BalanceMethod.GET, legacy.method)
        assertEquals("", legacy.body)
        val provider = provider("""{"units":0.004,"total":16777217}""") { request ->
            assertEquals("GET", request.method)
            assertEquals("/v1/credits", request.url.encodedPath)
            assertNull(request.body)
        }
        val savedLocale = Locale.getDefault()
        try {
            for (locale in listOf(Locale.GERMANY, Locale.forLanguageTag("pl-PL"))) {
                Locale.setDefault(locale)
                assertEquals("16777221,00", provider.getBalance(setting(legacy)))
            }
        } finally {
            Locale.setDefault(savedLocale)
        }
    }

    @Test
    fun postSubstitutesStringLeavesUsingTheSameRotatedKeyAsAuthorization() = runBlocking {
        val template = """{"auth":"Bearer {{apiKey}}","endpoint":"{{baseUrl}}","nested":["{{apiKey}}",2]}"""
        val option = BalanceOption(true, "https://balance.example/custom", "remaining", BalanceMethod.POST, template)
        val restored = Json.decodeFromString<BalanceOption>(Json.encodeToString(option))
        assertEquals(option, restored)
        val quotedKey = """key-with-"quote"""
        val provider = provider("""{"remaining":"unlimited"}""") { request ->
            assertEquals("POST", request.method)
            assertEquals("balance.example", request.url.host)
            val body = checkNotNull(request.body)
            assertEquals("application", body.contentType()?.type)
            assertEquals("json", body.contentType()?.subtype)
            val wire = Json.parseToJsonElement(Buffer().also(body::writeTo).readUtf8()).jsonObject
            val auth = checkNotNull(request.header("Authorization"))
            assertTrue(auth in setOf("Bearer first-key", "Bearer $quotedKey"))
            assertEquals(auth, wire.getValue("auth").jsonPrimitive.content)
            assertEquals("https://api.example/v1", wire.getValue("endpoint").jsonPrimitive.content)
            assertTrue(wire.getValue("nested").toString().endsWith(",2]"))
        }
        assertEquals("unlimited", provider.getBalance(setting(restored).copy(apiKey = "first-key,$quotedKey")))
    }

    @Test
    fun rejectsInvalidConfigWithoutSendingAndRetainsSafeHttpError() = runBlocking {
        val option = BalanceOption(enabled = true, method = BalanceMethod.POST, resultPath = "remaining")
        val body = """{"error":"upstream failed for secret-key"}"""
        var calls = 0
        val provider = provider(body, status = 429) { request ->
            calls++
            assertEquals("{}", Buffer().also(checkNotNull(request.body)::writeTo).readUtf8())
        }
        for (invalid in listOf("[]", """{"bad":"{{unknown}}"}""", """{"bad":unquoted}""", "{")) {
            val error = assertThrows(InvalidBalanceOption::class.java) {
                runBlocking { provider.getBalance(setting(option.copy(body = invalid))) }
            }
            assertEquals(InvalidBalanceOption.Field.BODY, error.field)
        }
        val relative = assertThrows(InvalidBalanceOption::class.java) {
            runBlocking { provider.getBalance(setting(option.copy(apiPath = "balance")).copy(baseUrl = "https://api.example")) }
        }
        assertEquals(InvalidBalanceOption.Field.URL, relative.field)
        assertEquals(0, calls)
        val error = assertThrows(HttpException::class.java) {
            runBlocking { provider.getBalance(setting(option)) }
        }
        assertEquals(429, error.statusCode)
        assertTrue(error.message.orEmpty().contains("upstream failed"))
        assertFalse(error.message.orEmpty().contains("secret-key"))
        assertEquals(1, calls)
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString<BalanceOption>("""{"method":"PATCH"}""")
        }
        Unit
    }

    @Test
    fun numericCredentialEchoIsNotDisplayedAsBalance() = runBlocking {
        val option = BalanceOption(enabled = true, method = BalanceMethod.POST, resultPath = "remaining")
        val provider = provider("""{"remaining":123456}""") {}
        assertEquals("…", provider.getBalance(setting(option).copy(apiKey = "123456")))
    }

    @Test
    fun malformedSuccessfulResponsePreservesParserDiagnosisWithoutTheActiveKey() {
        val option = BalanceOption(enabled = true, method = BalanceMethod.POST, resultPath = "remaining")
        val provider = provider("{\"ref\":\"secret-key\"") {}
        val error = assertThrows(SerializationException::class.java) {
            runBlocking { provider.getBalance(setting(option)) }
        }
        assertTrue(error.message.orEmpty().contains("JsonDecodingException"))
        assertTrue(error.message.orEmpty().contains("offset"))
        assertFalse(error.stackTraceToString().contains("secret-key"))
    }

    @Test
    fun postRefusesRedirectWhileLegacyGetCanFollowIt() {
        val redirects = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/credits") { exchange ->
            try {
                exchange.requestBody.use { it.readBytes() }
                val host = if (exchange.requestMethod == "GET") "localhost" else "127.0.0.1"
                exchange.responseHeaders.add("Location", "http://$host:${server.address.port}/redirected")
                exchange.sendResponseHeaders(307, -1)
            } finally {
                exchange.close()
            }
        }
        server.createContext("/redirected") { exchange ->
            try {
                redirects.incrementAndGet()
                val payload = """{"remaining":23}""".toByteArray()
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            } finally {
                exchange.close()
            }
        }
        val client = OkHttpClient.Builder().addNetworkInterceptor(PrivateRequestBoundaryInterceptor()).build()
        server.start()
        try {
            val option = BalanceOption(
                enabled = true, method = BalanceMethod.POST, body = """{"secret":"{{apiKey}}"}""",
            )
            val provider = OpenAIProvider(client)
            val failure = assertThrows(HttpException::class.java) {
                runBlocking {
                    provider.getBalance(setting(option).copy(baseUrl = "http://127.0.0.1:" + server.address.port))
                }
            }
            assertEquals(307, failure.statusCode)
            assertEquals(0, redirects.get())
            val get = option.copy(method = BalanceMethod.GET, resultPath = "remaining")
            assertEquals(
                "%.2f".format(23.0),
                runBlocking { provider.getBalance(setting(get).copy(baseUrl = "http://127.0.0.1:${server.address.port}")) },
            )
            assertEquals(1, redirects.get())
        } finally {
            server.stop(0)
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private fun setting(option: BalanceOption) =
        ProviderSetting.OpenAI(baseUrl = "https://api.example/v1", apiKey = "secret-key", balanceOption = option)

    private fun provider(body: String, status: Int = 200, inspect: (Request) -> Unit): OpenAIProvider {
        return OpenAIProvider(OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("fixture")
                .body(body.toResponseBody())
                .build()
        }.build())
    }
}
