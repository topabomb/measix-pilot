package net.weero.measix.pilot.service

import android.content.Context
import com.sun.net.httpserver.HttpServer
import io.mockk.mockk
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.providers.VolcengineTTSProvider
import me.rerere.tts.provider.providers.VolcengineTtsException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import net.weero.measix.pilot.utils.userVisibleDiagnostic

class VolcengineSpeechTransportTest {
    private val context = mockk<Context>()
    private val setting = TTSProviderSetting.Volcengine(apiKey = "secret-fixture-key")

    @Test fun `empty HTTP error body redacts credential in reason phrase and log id`() = runBlocking {
        for (status in listOf(401, 503)) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).code(status)
                    .message("Rejected ${setting.apiKey}").protocol(Protocol.HTTP_1_1)
                    .header("X-Tt-Logid", "trace-${setting.apiKey}")
                    .body("".toResponseBody("text/plain".toMediaType())).build()
            }.build()
            try {
                VolcengineTTSProvider(client).generateSpeech(context, setting, TTSRequest("hello")).collect {}
                fail("Expected HTTP $status")
            } catch (error: VolcengineTtsException) {
                assertEquals(status, error.statusCode)
                assertEquals("Rejected [redacted]", error.detail)
                assertEquals("trace-[redacted]", error.logId)
                for (diagnostic in listOf(error.userVisibleDiagnostic(), error.stackTraceToString())) {
                    assertFalse(diagnostic, diagnostic.contains(setting.apiKey))
                    assertTrue(diagnostic, diagnostic.contains("Rejected [redacted]"))
                }
            } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
        }
    }

    @Test fun `malformed provider response preserves parser identity without leaking credentials in UI or logs`() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).code(200).message("OK").protocol(Protocol.HTTP_1_1)
                .body("data: {\"message\":\"${setting.apiKey}\",bad}\n\n".toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        try {
            VolcengineTTSProvider(client).generateSpeech(context, setting, TTSRequest("hello")).collect {}
            fail("Malformed response must fail")
        } catch (error: VolcengineTtsException) {
            for (diagnostic in listOf(error.userVisibleDiagnostic(), error.stackTraceToString())) {
                assertTrue(diagnostic, diagnostic.contains("JsonDecodingException"))
                assertFalse(diagnostic, diagnostic.contains(setting.apiKey))
                assertTrue(diagnostic, diagnostic.contains("[redacted]"))
            }
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }

    @Test fun `actual adapter publishes one complete chunk and retains HTTP diagnostics`() = runBlocking {
        for (status in listOf(200, 401, 429, 503)) {
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                assertEquals("secret-fixture-key", chain.request().header("X-Api-Key"))
                val body = if (status == 200) "data: {\"code\":0,\"data\":\"AQI=\"}\n\ndata: {\"code\":20000000,\"data\":null}\n\n"
                    else "original diagnostic secret-fixture-key"
                Response.Builder().request(chain.request()).code(status).message("Fixture").protocol(Protocol.HTTP_1_1)
                    .header("X-Tt-Logid", "trace-id").body(body.toResponseBody("text/event-stream".toMediaType())).build()
            }.build()
            try {
                val results = VolcengineTTSProvider(client).generateSpeech(context, setting, TTSRequest("hello")).toList()
                assertEquals(200, status)
                assertEquals(1, results.size)
                assertTrue(results.single().isLast)
                assertArrayEquals(byteArrayOf(1, 2), results.single().data)
            } catch (error: VolcengineTtsException) {
                assertEquals(status, error.statusCode)
                assertEquals("trace-id", error.logId)
                assertTrue(error.detail.contains("original diagnostic"))
                assertFalse(error.detail.contains(setting.apiKey))
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
            assertEquals(1, requests)
        }
    }

    @Test fun `redirect and retryable statuses never replay synthesis`() = runBlocking {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var status = 307
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Location", "/again")
            exchange.responseHeaders.add("Retry-After", "0")
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        val client = OkHttpClient()
        try {
            for (code in listOf(307, 408, 503)) {
                status = code
                val before = calls.get()
                try {
                    VolcengineTTSProvider(client).generateSpeech(context,
                        setting.copy(baseUrl = "http://127.0.0.1:${server.address.port}"), TTSRequest("once")).collect {}
                    fail("Expected HTTP $code")
                } catch (error: VolcengineTtsException) { assertEquals(code, error.statusCode) }
                assertEquals(before + 1, calls.get())
            }
        } finally { server.stop(0); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow() }
    }

    @Test fun `cancellation closes the actual blocked response before returning without partial audio`() = runBlocking {
        val reading = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.sendResponseHeaders(200, 0)
            try {
                exchange.responseBody.write("data: {\"code\":0,\"data\":\"AQI=\"}\n\n".toByteArray())
                exchange.responseBody.flush()
                releaseServer.await(10, TimeUnit.SECONDS)
            } finally { exchange.close() }
        }
        server.start()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun responseBodyStart(call: Call) { reading.countDown() }
            override fun callFailed(call: Call, ioe: java.io.IOException) { failed.countDown() }
        }).build()
        val output = AtomicInteger()
        val job = launch(Dispatchers.Default) {
            VolcengineTTSProvider(client).generateSpeech(context,
                setting.copy(baseUrl = "http://127.0.0.1:${server.address.port}"), TTSRequest("cancel"))
                .collect { output.incrementAndGet() }
        }
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            withTimeout(3_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertTrue("Call failure precedes cancellation completion", failed.await(1, TimeUnit.SECONDS))
            assertEquals(0, output.get())
        } finally {
            releaseServer.countDown(); job.cancelAndJoin(); server.stop(0)
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
        }
    }
}
