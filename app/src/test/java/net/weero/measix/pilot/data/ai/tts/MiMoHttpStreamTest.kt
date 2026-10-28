package net.weero.measix.pilot.data.ai.tts

import com.sun.net.httpserver.HttpServer
import io.mockk.mockk
import java.net.InetSocketAddress
import java.util.Base64
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProviderSetting
import me.rerere.tts.provider.providers.MiMoTTSProvider
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiMoHttpStreamTest {
    @Test
    fun `a complete burst preserves all audio and one terminal result`() = runBlocking {
        val expected = ByteArray(4096) { (it % 251).toByte() }
        val wire = expected.joinToString("") { value -> audioEvent(byteArrayOf(value)) } + "data: [DONE]\n\n"
        val client = OkHttpClient()
        withServer(wire, client) { provider, setting ->
            val output = java.io.ByteArrayOutputStream()
            var terminalCount = 0
            withTimeout(10_000) {
                provider.generateSpeech(mockk(), setting, TTSRequest("text")).collect { chunk ->
                    if (chunk.isLast) terminalCount++
                    output.write(chunk.data)
                }
            }
            assertArrayEquals(expected, output.toByteArray())
            org.junit.Assert.assertEquals(1, terminalCount)
        }
    }

    @Test
    fun `EOF without DONE is not a completed synthesis`() = runBlocking {
        withServer(audioEvent(byteArrayOf(1, 2)), OkHttpClient()) { provider, setting ->
            var failure: Throwable? = null
            try {
                provider.generateSpeech(mockk(), setting, TTSRequest("text")).collect { }
            } catch (error: Exception) { failure = error }
            assertTrue("partial synthesis must fail", failure != null)
        }
    }

    @Test
    fun `service error after audio cannot publish a successful partial synthesis`() = runBlocking {
        val wire = audioEvent(byteArrayOf(1, 2)) +
            "data: {\"error\":{\"message\":\"voice unavailable\",\"code\":\"voice_failed\"}}\n\n" +
            "data: [DONE]\n\n"
        withServer(wire, OkHttpClient()) { provider, setting ->
            var failure: Throwable? = null
            try {
                provider.generateSpeech(mockk(), setting, TTSRequest("text")).collect { }
            } catch (error: Exception) { failure = error }
            assertTrue("remote synthesis failure must be retained", failure?.message.orEmpty().contains("voice"))
        }
    }

    private suspend fun withServer(
        wire: String,
        client: OkHttpClient,
        block: suspend (MiMoTTSProvider, TTSProviderSetting.MiMo) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat/completions") { exchange ->
            exchange.use {
                val bytes = wire.toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }
        server.start()
        try {
            block(MiMoTTSProvider(client), TTSProviderSetting.MiMo(
                baseUrl = "http://127.0.0.1:${server.address.port}", apiKey = "test-only",
            ))
        } finally {
            server.stop(0)
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private fun audioEvent(bytes: ByteArray): String =
        "data: {\"choices\":[{\"delta\":{\"audio\":{\"data\":\"${Base64.getEncoder().encodeToString(bytes)}\"}}}]}\n\n"
}
