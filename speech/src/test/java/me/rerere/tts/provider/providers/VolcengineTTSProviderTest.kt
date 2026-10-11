package me.rerere.tts.provider.providers

import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.tts.provider.TTSProviderSetting
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class VolcengineTTSProviderTest {
    @Test fun `malformed responses redact credentials throughout parser diagnostics`() {
        val key = "secret-fixture-key"
        val failure = assertThrows(VolcengineTtsException::class.java) {
            readVolcengineAudio(Buffer().writeUtf8("data: {\"message\":\"$key\",bad}\n\n"), key, key)
        }
        assertFalse(failure.stackTraceToString().contains(key))
        assertTrue(failure.stackTraceToString().contains("JsonDecodingException"))
        assertTrue(requireNotNull(failure.cause).stackTrace.isNotEmpty())
        assertTrue(failure.stackTraceToString().contains("[redacted]"))
    }

    @Test fun `request preserves resource voice speed and text without silent clamping`() {
        val setting = TTSProviderSetting.Volcengine(apiKey = "key", baseUrl = "https://voice.example/gateway/", speechRate = -20)
        val request = buildVolcengineTtsRequest(setting, "hello\nworld")
        assertEquals("https://voice.example/gateway/api/v3/tts/unidirectional/sse", request.url.toString())
        assertEquals("key", request.header("X-Api-Key"))
        assertEquals(setting.resourceId, request.header("X-Api-Resource-Id"))
        assertTrue(requireNotNull(request.body).isOneShot())
        val body = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
        assertEquals(request.header("X-Api-Request-Id"), body.getValue("user").jsonObject.getValue("uid").jsonPrimitive.content)
        val params = body.getValue("req_params").jsonObject
        assertEquals("hello\nworld", params.getValue("text").jsonPrimitive.content)
        assertEquals(setting.speaker, params.getValue("speaker").jsonPrimitive.content)
        assertEquals("-20", params.getValue("audio_params").jsonObject.getValue("speech_rate").jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) { buildVolcengineTtsRequest(setting.copy(speechRate = 101), "text") }
        assertThrows(IllegalArgumentException::class.java) { buildVolcengineTtsRequest(setting.copy(baseUrl = "https://voice.example/#fragment"), "text") }
    }

    @Test fun `multiline data captions and sentence end do not lose audio or terminate early`() {
        val source = Buffer().writeUtf8(": heartbeat\r\n\r\nevent: 352\r\ndata: {\r\ndata: \"code\":0,\"data\":\"AQI=\"}\r\n\r\n" +
            "event: 351\ndata: {\"code\":0,\"data\":null}\n\n" +
            "event: 152\ndata: {\"code\":20000000,\"data\":\"Aw==\"}\n\n")
        assertArrayEquals(byteArrayOf(1, 2, 3), readVolcengineAudio(source, "trace", "key"))
    }

    @Test fun `only explicit successful completion with audio is published`() {
        val data = "data: {\"code\":0,\"data\":\"AQI=\"}\n\n"
        val cases = mapOf(
            data to "volcengine_tts_incomplete_response",
            "data: {\"code\":20000000,\"data\":null}\n\n" to "volcengine_tts_empty_audio",
            data + "event: 351\ndata: {\"code\":0,\"data\":null}\n\n" to "volcengine_tts_incomplete_response",
            data + "data: {\"code\":45000000,\"message\":\"voice denied key\"}\n\n" to "volcengine_tts_service_error",
            "event: 153\ndata: {\"code\":0,\"message\":\"failed\"}\n\n" to "volcengine_tts_service_error",
            "event: 151\n\n" to "volcengine_tts_service_error",
            "data: {\"code\":0,\"data\":\"not base64!\"}\n\n" to "volcengine_tts_invalid_audio",
            "data: {\"code\":0,\"data\":false}\n\n" to "volcengine_tts_invalid_audio",
            "data: {bad json}\n\n" to "volcengine_tts_invalid_event",
        )
        for ((wire, reason) in cases) {
            val failure = assertThrows(VolcengineTtsException::class.java) { readVolcengineAudio(Buffer().writeUtf8(wire), "trace", "key") }
            assertEquals(wire, reason, failure.reason)
            assertEquals("trace", failure.logId)
            assertFalse(failure.detail.contains("key"))
        }
    }

    @Test fun `local byte limits reject rather than truncate synthesis`() {
        val tooLong = Buffer().writeUtf8("data: " + "x".repeat(MAX_VOLCENGINE_EVENT_BYTES + 1))
        assertEquals("volcengine_tts_response_limit", assertThrows(VolcengineTtsException::class.java) {
            readVolcengineAudio(tooLong, null, "key")
        }.reason)
        val encoded = Base64.getEncoder().encodeToString(ByteArray(512 * 1024))
        val wire = Buffer()
        repeat(33) { wire.writeUtf8("data: {\"code\":0,\"data\":\"$encoded\"}\n\n") }
        assertEquals("volcengine_tts_response_limit", assertThrows(VolcengineTtsException::class.java) {
            readVolcengineAudio(wire, null, "key")
        }.reason)
    }

    @Test fun `additive settings subtype round trips and old types retain their defaults`() {
        val setting: TTSProviderSetting = TTSProviderSetting.Volcengine(apiKey = "key", resourceId = "resource", speaker = "voice", speechRate = 70)
        assertEquals(setting, Json.decodeFromString<TTSProviderSetting>(Json.encodeToString(setting)))
        val old = Json.decodeFromString<TTSProviderSetting>("{\"type\":\"openai\",\"name\":\"Original\"}") as TTSProviderSetting.OpenAI
        assertEquals("Original", old.name)
        assertEquals("gpt-4o-mini-tts", old.model)
        val restored = Json.decodeFromString<TTSProviderSetting>("{\"type\":\"volcengine\"}") as TTSProviderSetting.Volcengine
        assertEquals(0, restored.speechRate)
        assertEquals("seed-tts-2.0", restored.resourceId)
    }
}
