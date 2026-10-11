package me.rerere.tts.provider.providers

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.common.http.readResponse
import me.rerere.common.http.withSingleAttemptBody
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.Authenticator
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource

// Local resource bounds, not claims about the service's text or account quotas.
internal const val MAX_VOLCENGINE_EVENT_BYTES = 1024 * 1024
internal const val MAX_VOLCENGINE_AUDIO_BYTES = 16 * 1024 * 1024
private const val MAX_WIRE_BYTES = 32 * 1024 * 1024
private const val MAX_EVENTS = 8192
private const val MAX_ERROR_BYTES = 64 * 1024

class VolcengineTtsException(
    val reason: String,
    val detail: String,
    val logId: String? = null,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : IOException(buildString {
    append(reason).append(": ").append(detail)
    statusCode?.let { append("; HTTP ").append(it) }
    logId?.let { append("; X-Tt-Logid=").append(it) }
}, cause)

// Parser exceptions include response excerpts. Preserve their diagnostic identity and stack,
// but never attach the original throwable, which may contain the request credential.
private fun Throwable.withRedactedCredential(apiKey: String): Throwable {
    val copies = IdentityHashMap<Throwable, Throwable>()
    fun copy(error: Throwable): Throwable {
        copies[error]?.let { return it }
        val detail = error.javaClass.name + (error.message?.replace(apiKey, "[redacted]")?.let { ": $it" } ?: "")
        val diagnostic = RedactedResponseCause(detail)
        copies[error] = diagnostic
        diagnostic.stackTrace = error.stackTrace
        error.cause?.let { if (it !== error) diagnostic.initCause(copy(it)) }
        error.suppressed.forEach { if (it !== error) diagnostic.addSuppressed(copy(it)) }
        return diagnostic
    }
    return copy(this)
}

private class RedactedResponseCause(detail: String) : Exception(detail) {
    override fun toString(): String = requireNotNull(message)
}

internal fun buildVolcengineTtsRequest(setting: TTSProviderSetting.Volcengine, text: String): Request {
    require(setting.apiKey.isNotBlank()) { "volcengine_tts_api_key_required" }
    require(setting.resourceId.isNotBlank()) { "volcengine_tts_resource_required" }
    require(setting.speaker.isNotBlank()) { "volcengine_tts_speaker_required" }
    require(setting.speechRate in -50..100) { "volcengine_tts_invalid_speech_rate" }
    require(text.isNotBlank()) { "volcengine_tts_text_required" }
    val base = setting.baseUrl.trim().toHttpUrl()
    require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) {
        "volcengine_tts_invalid_endpoint"
    }
    val requestId = Uuid.random().toString()
    val body = buildJsonObject {
        put("user", buildJsonObject { put("uid", requestId) })
        put("req_params", buildJsonObject {
            put("text", text)
            put("speaker", setting.speaker.trim())
            put("audio_params", buildJsonObject {
                put("format", "mp3")
                put("speech_rate", setting.speechRate)
            })
        })
    }
    return Request.Builder()
        .url(base.newBuilder().encodedPath(base.encodedPath.trimEnd('/') + "/api/v3/tts/unidirectional/sse").build())
        .header("Accept", "text/event-stream")
        .header("X-Api-Key", setting.apiKey.trim())
        .header("X-Api-Resource-Id", setting.resourceId.trim())
        .header("X-Api-Request-Id", requestId)
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build().withSingleAttemptBody()
}

/** Pull parsing retains every audio event without a producer queue. Nothing is published before terminal success. */
internal fun readVolcengineAudio(source: BufferedSource, logId: String?, apiKey: String): ByteArray {
    val audio = ByteArrayOutputStream()
    val data = StringBuilder()
    var eventType: String? = null
    var events = 0
    var eventBytes = 0L
    var wireBytes = 0L
    fun fail(reason: String, detail: String, cause: Throwable? = null): Nothing =
        throw VolcengineTtsException(reason, detail.replace(apiKey, "[redacted]"),
            logId?.replace(apiKey, "[redacted]"), cause = cause?.withRedactedCredential(apiKey))
    fun dispatch(): Boolean {
        if (data.isEmpty()) {
            if (eventType in setOf("151", "153")) fail("volcengine_tts_service_error", "Session event $eventType")
            return false
        }
        if (++events > MAX_EVENTS) fail("volcengine_tts_response_limit", "More than $MAX_EVENTS events")
        val payload = try { Json.parseToJsonElement(data.toString()) as? JsonObject }
        catch (error: IllegalArgumentException) { fail("volcengine_tts_invalid_event", "Malformed SSE JSON", error) }
            ?: fail("volcengine_tts_invalid_event", "Expected an SSE JSON object")
        val code = (payload["code"] as? JsonPrimitive)?.intOrNull
            ?: fail("volcengine_tts_invalid_event", "Missing integer code")
        if (eventType in setOf("151", "153") || code != 0 && code != 20000000) {
            val message = (payload["message"] as? JsonPrimitive)?.content.orEmpty()
            fail("volcengine_tts_service_error", "code=$code event=$eventType message=$message")
        }
        when (val encoded = payload["data"]) {
            null, JsonNull -> Unit
            is JsonPrimitive -> {
                if (!encoded.isString) fail("volcengine_tts_invalid_audio", "Expected Base64 audio or null")
                val bytes = try { Base64.getDecoder().decode(encoded.content) }
                catch (error: IllegalArgumentException) { fail("volcengine_tts_invalid_audio", "Invalid Base64 audio", error) }
                if (bytes.size > MAX_VOLCENGINE_AUDIO_BYTES - audio.size()) {
                    fail("volcengine_tts_response_limit", "Audio exceeds $MAX_VOLCENGINE_AUDIO_BYTES bytes")
                }
                audio.write(bytes)
            }
            else -> fail("volcengine_tts_invalid_audio", "Expected Base64 audio or null")
        }
        if (code == 20000000) {
            if (audio.size() == 0) fail("volcengine_tts_empty_audio", "Successful response contained no audio")
            return true
        }
        return false
    }
    while (!source.exhausted()) {
        val newline = source.indexOf('\n'.code.toByte(), 0, MAX_VOLCENGINE_EVENT_BYTES.toLong() + 1)
        if (newline < 0 && source.buffer.size > MAX_VOLCENGINE_EVENT_BYTES) {
            fail("volcengine_tts_response_limit", "SSE line exceeds $MAX_VOLCENGINE_EVENT_BYTES bytes")
        }
        val length = if (newline >= 0) newline + 1 else source.buffer.size
        wireBytes += length
        eventBytes += length
        if (wireBytes > MAX_WIRE_BYTES || eventBytes > MAX_VOLCENGINE_EVENT_BYTES) {
            fail("volcengine_tts_response_limit", "SSE wire or event byte limit exceeded")
        }
        val line = source.readUtf8(length).removeSuffix("\n").removeSuffix("\r")
        if (line.isEmpty()) {
            if (dispatch()) return audio.toByteArray()
            data.setLength(0)
            eventType = null
            eventBytes = 0
        } else if (!line.startsWith(':')) {
            val field = line.substringBefore(':')
            val value = line.substringAfter(':', "").removePrefix(" ")
            when (field) {
                "event" -> eventType = value
                "data" -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(value)
                }
            }
        }
    }
    if (dispatch()) return audio.toByteArray()
    fail("volcengine_tts_incomplete_response", "Stream closed before synthesis completed")
}

class VolcengineTTSProvider(client: OkHttpClient = OkHttpClient()) : TTSProvider<TTSProviderSetting.Volcengine> {
    private val httpClient = client.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .build()

    override fun generateSpeech(context: Context, providerSetting: TTSProviderSetting.Volcengine, request: TTSRequest): Flow<AudioChunk> = flow {
        check(request.transport == null) { "volcengine_managed_transport_unsupported" }
        val httpRequest = buildVolcengineTtsRequest(providerSetting, request.text)
        val bytes = httpClient.newCall(httpRequest).readResponse { response ->
            val logId = response.header("X-Tt-Logid")?.replace(providerSetting.apiKey.trim(), "[redacted]")
            if (!response.isSuccessful) {
                val source = response.body.source()
                source.request(MAX_ERROR_BYTES.toLong() + 1)
                val oversized = source.buffer.size > MAX_ERROR_BYTES
                val detail = source.readUtf8(minOf(source.buffer.size, MAX_ERROR_BYTES.toLong()))
                    .ifBlank { response.message }
                    .replace(providerSetting.apiKey.trim(), "[redacted]")
                throw VolcengineTtsException("volcengine_tts_http_error",
                    detail + if (oversized) " [detail exceeds 64 KiB]" else "",
                    logId, response.code)
            }
            readVolcengineAudio(response.body.source(), logId, providerSetting.apiKey.trim())
        }
        emit(AudioChunk(data = bytes, format = AudioFormat.MP3, isLast = true,
            metadata = mapOf("provider" to "volcengine", "voice" to providerSetting.speaker)))
    }.flowOn(Dispatchers.IO)
}
