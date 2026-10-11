package me.rerere.ai.provider.providers

import kotlinx.serialization.json.*
import me.rerere.ai.ui.ClaudeReplayEvidence
import me.rerere.ai.util.json
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrl
import me.rerere.ai.provider.RequestCredentials
import me.rerere.ai.registry.ModelRegistry

internal fun claudeEndpointIdentity(baseUrl: String, credentials: RequestCredentials): String {
    val actual = (credentials as? RequestCredentials.Routed)?.endpoint ?: "${baseUrl.trimEnd('/')}/messages"
    return claudeUrlIdentity(actual.toHttpUrl())
}

internal fun claudeUrlIdentity(actual: okhttp3.HttpUrl): String {
    val url = actual.newBuilder().username("").password("").fragment(null).build()
    // Query routing affects source identity, but query credentials must never be stored verbatim.
    return url.newBuilder().query(null).build().toString() + (url.encodedQuery?.let { "?sha256=${digest(JsonPrimitive(it))}" } ?: "")
}

/** A builder-local annotation removed before transport; it is never part of the wire protocol. */
internal const val CLAUDE_REPLAY_ANNOTATION = "__pilot_claude_replay"

internal data class ClaudePreparedRequest(val body: JsonObject, val retiredDigests: Set<String>)

internal fun claudeBlockDigest(block: JsonObject): String = digest(normalizeBlock(block))

private fun JsonObject.isThinking() = (get("type") as? JsonPrimitive)?.content in setOf("thinking", "redacted_thinking")

private fun normalizeBlock(block: JsonObject): JsonObject {
    val retained = (block - "cache_control" - CLAUDE_REPLAY_ANNOTATION).toMutableMap()
    if ((block["type"] as? JsonPrimitive)?.content == "tool_result") {
        (block["content"] as? JsonArray)?.let { content ->
            retained["content"] = JsonArray(content.map { if (it is JsonObject) normalizeBlock(it) else it })
        }
    }
    return JsonObject(retained)
}

private fun normalizeMessage(message: JsonObject): JsonObject = JsonObject(message + mapOf(
    "content" to ((message["content"] as? JsonArray)?.let { content ->
        JsonArray(content.filterNot { it is JsonObject && it.isThinking() }.map { if (it is JsonObject) normalizeBlock(it) else it })
    } ?: message.getValue("content")),
))

private fun prefixDigest(body: JsonObject, messages: List<JsonObject>, content: List<JsonObject>): String = digest(buildJsonObject {
    put("system", (body["system"] as? JsonArray)?.let { parts ->
        JsonArray(parts.map { if (it is JsonObject) normalizeBlock(it) else it })
    } ?: body["system"] ?: JsonNull)
    // Only tool-level cache control is transport metadata. A schema property named cache_control is content.
    put("tools", (body["tools"] as? JsonArray)?.let { tools ->
        JsonArray(tools.map { if (it is JsonObject) JsonObject(it - "cache_control") else it })
    } ?: JsonNull)
    put("messages", JsonArray(messages.map(::normalizeMessage).filterNot {
        it["content"] is JsonArray && (it["content"] as JsonArray).isEmpty()
    } + buildJsonObject {
        put("role", "assistant"); put("content", JsonArray(content.filterNot { it.isThinking() }.map(::normalizeBlock)))
    }))
})

/** Filter against the already-filtered wire prefix, so a later valid chain can start after a retired one. */
internal fun filterClaudeThinking(body: JsonObject, endpoint: String, alreadyRetired: Set<String> = emptySet()): ClaudePreparedRequest {
    val model = (body["model"] as? JsonPrimitive)?.content ?: error("Claude model is missing")
    val requiresEvidence = ModelRegistry.CLAUDE_5_5.match(model)
    val messages = mutableListOf<JsonObject>()
    var previousThinking: String? = null
    val retired = alreadyRetired.toMutableSet()
    (body["messages"] as JsonArray).forEach { rawMessage ->
        val message = rawMessage.jsonObject
        val content = mutableListOf<JsonObject>()
        (message["content"] as JsonArray).forEach { rawBlock ->
            val annotated = rawBlock.jsonObject
            val block = JsonObject(annotated - CLAUDE_REPLAY_ANNOTATION)
            if (block.isThinking() && requiresEvidence) {
                val evidence = annotated[CLAUDE_REPLAY_ANNOTATION]?.let {
                    runCatching { json.decodeFromJsonElement<ClaudeReplayEvidence>(it) }.getOrNull()
                }
                val valid = claudeBlockDigest(block) !in retired && evidence?.version == 1 && evidence.endpoint == endpoint && evidence.model == model &&
                    evidence.prefixDigest == prefixDigest(body, messages, content) &&
                    evidence.blockDigest == claudeBlockDigest(block) &&
                    (previousThinking == null || evidence.previousThinkingDigest == previousThinking)
                if (valid) {
                    content += block
                    previousThinking = claudeBlockDigest(block)
                } else retired += claudeBlockDigest(block)
            } else content += block
        }
        if (content.isNotEmpty()) messages += JsonObject(message + ("content" to JsonArray(content)))
    }
    return ClaudePreparedRequest(JsonObject(body + ("messages" to JsonArray(messages))), retired)
}

/** Called only with the actual final request and the unmodified response block sequence. */
internal fun claudeReplayEvidence(body: JsonObject, endpoint: String, response: List<JsonObject>, index: Int): ClaudeReplayEvidence {
    val messages = (body["messages"] as JsonArray).map { it.jsonObject }
    val previousContent = response.take(index)
    val previous = previousContent.lastOrNull { it.isThinking() } ?: messages.asReversed().firstNotNullOfOrNull { message ->
        (message["content"] as JsonArray).map { it.jsonObject }.lastOrNull { it.isThinking() }
    }
    return ClaudeReplayEvidence(endpoint = endpoint, model = body.getValue("model").jsonPrimitive.content,
        prefixDigest = prefixDigest(body, messages, previousContent), blockDigest = claudeBlockDigest(response[index]),
        previousThinkingDigest = previous?.let(::claudeBlockDigest))
}

private fun digest(value: JsonElement): String {
    fun canonical(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(element.map(::canonical))
        else -> element
    }
    return MessageDigest.getInstance("SHA-256").digest(canonical(value).toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
