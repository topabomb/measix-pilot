package me.rerere.ai.provider.providers

import kotlinx.serialization.json.*
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.RequestCredentials
import me.rerere.ai.ui.*
import org.junit.Assert.*
import org.junit.Test

class ClaudeThinkingReplayTest {
    private val endpoint = "https://example.com/messages"
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun text(value: String) = buildJsonObject { put("type", "text"); put("text", value) }
    private fun thinking(value: String) = buildJsonObject { put("type", "thinking"); put("thinking", ""); put("signature", value) }
    private fun message(role: String, parts: List<JsonObject>) = buildJsonObject {
        put("role", role); put("content", JsonArray(parts))
    }
    private fun body(messages: List<JsonObject> = listOf(message("user", listOf(text("request"))))) = buildJsonObject {
        put("model", "claude-opus-5-5"); put("messages", JsonArray(messages)); put("system", JsonArray(listOf(text("system"))))
        put("tools", JsonArray(listOf(obj("""{"name":"tool","input_schema":{"type":"object","properties":{"cache_control":{"type":"string"}}}}"""))))
    }
    private fun append(request: JsonObject, response: List<JsonObject>): JsonObject {
        val annotated = response.mapIndexed { index, block ->
            if (block["type"]?.jsonPrimitive?.content !in setOf("thinking", "redacted_thinking")) block else
                JsonObject(block + (CLAUDE_REPLAY_ANNOTATION to Json.encodeToJsonElement(claudeReplayEvidence(request, endpoint, response, index))))
        }
        return JsonObject(request + ("messages" to JsonArray(request.getValue("messages").jsonArray + message("assistant", annotated))))
    }
    private fun signatures(request: JsonObject) = request.getValue("messages").jsonArray.flatMap {
        it.jsonObject.getValue("content").jsonArray
    }.mapNotNull { it.jsonObject["signature"]?.jsonPrimitive?.content }

    @Test fun `unchanged final wire prefix preserves empty signed and redacted blocks but edits retire them`() {
        val sent = body()
        val response = listOf(thinking("a"), text("between"), obj("""{"type":"redacted_thinking","data":"opaque"}"""), text("answer"))
        val history = append(sent, response)
        val replay = filterClaudeThinking(history, endpoint)
        assertEquals(listOf("a"), signatures(replay.body))
        assertFalse(replay.body.toString().contains(CLAUDE_REPLAY_ANNOTATION))
        assertTrue(replay.body.toString().contains("opaque"))
        for (changed in listOf(JsonObject(history + ("system" to JsonPrimitive("edited"))),
            JsonObject(history + ("tools" to JsonArray(emptyList()))))) {
            val result = filterClaudeThinking(changed, endpoint)
            assertTrue(signatures(result.body).isEmpty())
            assertEquals(2, result.retiredDigests.size)
        }
        assertTrue(signatures(filterClaudeThinking(history, "https://other.example/messages").body).isEmpty())
    }

    @Test fun `only protocol cache markers are ignored and schema cache_control stays semantic`() {
        val history = append(body(), listOf(thinking("a"), text("answer")))
        val cache = obj("""{"type":"ephemeral"}""")
        val system = JsonArray(history.getValue("system").jsonArray.map { JsonObject(it.jsonObject + ("cache_control" to cache)) })
        val cached = JsonObject(history + ("system" to system) + ("cache_control" to cache))
        assertEquals(listOf("a"), signatures(filterClaudeThinking(cached, endpoint).body))
        val changed = Json.parseToJsonElement(history.toString().replace("\"type\":\"string\"", "\"type\":\"number\"")).jsonObject
        assertTrue(signatures(filterClaudeThinking(changed, endpoint).body).isEmpty())
    }

    @Test fun `oldest thinking can be removed but a middle gap cannot join disconnected chain`() {
        val history = append(body(), listOf(thinking("a"), thinking("b"), thinking("c"), text("answer")))
        fun without(signature: String): JsonObject {
            val messages = history.getValue("messages").jsonArray.map { raw ->
                val message = raw.jsonObject
                JsonObject(message + ("content" to JsonArray(message.getValue("content").jsonArray.filterNot {
                    it.jsonObject["signature"]?.jsonPrimitive?.content == signature
                })))
            }
            return JsonObject(history + ("messages" to JsonArray(messages)))
        }
        assertEquals(listOf("b", "c"), signatures(filterClaudeThinking(without("a"), endpoint).body))
        assertEquals(listOf("a"), signatures(filterClaudeThinking(without("b"), endpoint).body))
    }

    @Test fun `retirement prevents resurrection and still allows a new chain from filtered prefix`() {
        val first = append(body(), listOf(thinking("old"), text("answer")))
        val edited = JsonObject(first + ("system" to JsonPrimitive("edited system")))
        val filtered = filterClaudeThinking(edited, endpoint)
        val next = JsonObject(filtered.body + ("messages" to JsonArray(filtered.body.getValue("messages").jsonArray + message("user", listOf(text("next"))))))
        val newHistory = append(next, listOf(thinking("new"), text("new answer")))
        assertEquals(listOf("new"), signatures(filterClaudeThinking(newHistory, endpoint, filtered.retiredDigests).body))
        assertTrue(signatures(filterClaudeThinking(first, endpoint, filtered.retiredDigests).body).isEmpty())
        val legacy = JsonObject(first + ("model" to JsonPrimitive("claude-3-7-sonnet")))
        assertEquals(listOf("old"), signatures(filterClaudeThinking(legacy, endpoint).body))
    }

    @Test fun `shrinking then expanding history cannot resurrect retired thinking after a successful request`() {
        val first = append(body(), listOf(thinking("first"), text("first answer")))
        val secondRequest = JsonObject(first + ("messages" to JsonArray(first.getValue("messages").jsonArray + message("user", listOf(text("next"))))))
        val full = append(secondRequest, listOf(thinking("second"), text("second answer")))
        val window = JsonObject(full + ("messages" to JsonArray(full.getValue("messages").jsonArray.takeLast(2))))
        val accepted = filterClaudeThinking(window, endpoint)
        assertTrue(signatures(accepted.body).isEmpty())
        assertEquals(setOf(claudeBlockDigest(thinking("second"))), accepted.retiredDigests)
        val afterSuccess = append(accepted.body, listOf(thinking("new"), text("new answer")))
        val expanded = JsonObject(afterSuccess + ("messages" to JsonArray(full.getValue("messages").jsonArray +
            afterSuccess.getValue("messages").jsonArray.last())))
        val replay = filterClaudeThinking(expanded, endpoint, accepted.retiredDigests)
        assertEquals(listOf("first"), signatures(replay.body))
        assertTrue(claudeBlockDigest(thinking("second")) in replay.retiredDigests)
    }

    @Test fun `final parameters enforce 5_5 defaults modes and effort without changing old models`() {
        assertThrows(IllegalArgumentException::class.java) { buildClaudeThinkingFields("claude-opus-5-5", ReasoningLevel.OFF, 4096) }
        assertEquals("between_tools", buildClaudeThinkingFields("claude-sonnet-5-5", ReasoningLevel.OFF, 4096).getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("xhigh", buildClaudeThinkingFields("claude-sonnet-5-5", ReasoningLevel.XHIGH, 4096).getValue("output_config").jsonObject.getValue("effort").jsonPrimitive.content)
        for (topP in listOf(0.99, 1.0)) {
            val result = validateClaudeRequestParameters(JsonObject(body() + ("temperature" to JsonPrimitive(1)) + ("top_p" to JsonPrimitive(topP))))
            assertFalse(result.containsKey("temperature")); assertFalse(result.containsKey("top_p"))
        }
        for (extra in listOf("""{"temperature":0.5}""", """{"top_p":"1"}""", """{"top_k":0}""",
            """{"thinking":{"type":"disabled"}}""", """{"tool_choice":{"type":"any"}}""", """{"output_config":{"effort":1}}""")) {
            assertThrows(IllegalArgumentException::class.java) { validateClaudeRequestParameters(JsonObject(body() + obj(extra))) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateClaudeRequestParameters(JsonObject(body() + obj("""{"model":"claude-sonnet-5-5","thinking":{"type":"between_tools"},"output_config":{"effort":"max"}}""")))
        }
        val old = JsonObject(body() + obj("""{"model":"claude-3-7-sonnet","temperature":0.5}"""))
        assertEquals(old, validateClaudeRequestParameters(old))
        val routed = claudeEndpointIdentity("https://unused.example", RequestCredentials.Routed("https://relay.example/execute?route=a&token=secret", "token"))
        assertFalse(routed.contains("secret")); assertFalse(routed.endsWith("/messages"))
        assertNotEquals(routed, claudeEndpointIdentity("https://unused.example", RequestCredentials.Routed("https://relay.example/execute?route=b&token=secret", "token")))
    }
}
