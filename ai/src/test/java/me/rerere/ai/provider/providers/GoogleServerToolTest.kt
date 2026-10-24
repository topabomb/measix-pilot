package me.rerere.ai.provider.providers

import kotlinx.serialization.json.*
import me.rerere.ai.core.*
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class GoogleServerToolTest {
    @Test fun `mixed tools reject AUTO without imposing an undocumented mode allowlist`() {
        val tool = Tool(name = "local", description = "fixture", parameters = { buildJsonObject { put("type", "object") } }, execute = { emptyList() }).freeze()
        for (mode in listOf("AUTO", "VALIDATED", "ANY", "NONE")) {
            val params = TextGenerationParams(Model(modelId = "gemini-test", abilities = listOf(ModelAbility.TOOL), tools = setOf(BuiltInTools.Search)),
                tools = listOf(tool), customBody = listOf(CustomBody("toolConfig", buildJsonObject {
                    put("functionCallingConfig", buildJsonObject { put("mode", mode) })
                })))
            if (mode == "AUTO") assertThrows(IllegalArgumentException::class.java) {
                provider.buildCompletionRequestBody(listOf(ModelRequestMessage.user("go")), params, profile)
            } else {
                val body = provider.buildCompletionRequestBody(listOf(ModelRequestMessage.user("go")), params, profile)
                assertEquals(mode, body["toolConfig"]!!.jsonObject["functionCallingConfig"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
            }
        }
    }

    private val provider = GoogleProvider(OkHttpClient())
    private val profile = "google:developer:test.example"
    private fun raw(key: String) = Json.parseToJsonElement("""{"$key":{"id":"same","toolType":"GOOGLE_SEARCH_WEB","${if (key == "toolCall") "args" else "response"}":{}},"thoughtSignature":"signature-$key"}""").jsonObject
    private fun server(key: String) = provider.parseMessagePart(raw(key), "gemini-test", profile) as UIMessagePart.ServerTool

    @Test fun `Google search exposes a typed readonly query without interpreting server HTML`() {
        val part = provider.parseMessagePart(Json.parseToJsonElement("""{"toolCall":{"toolType":"GOOGLE_SEARCH_WEB","args":{"queries":["one","two"]}}}""").jsonObject, "gemini-test", profile) as UIMessagePart.ServerTool
        assertEquals("one\ntwo", (part.presentation as ServerToolPresentation.WebSearch).query)
        assertTrue((part.presentation as ServerToolPresentation.WebSearch).links.isEmpty())
        assertFalse(part.complete)
    }

    @Test fun `server parts retain order repeated ids signatures and sampling boundaries through persistence`() {
        val tool = UIMessagePart.Tool(Uuid.random(), Uuid.random(), "same", "local", "{}",
            output = listOf(UIMessagePart.Text("result")), resultStatus = ToolResultStatus.COMPLETED,
            metadata = GoogleThoughtMetadata(functionCallId = "same", sourceModelId = "gemini-test", sourceProfile = profile).toMetadata())
        val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            server("toolCall").copy(complete = true), tool, server("toolResponse").copy(complete = true),
            server("toolCall").copy(complete = true), server("toolResponse").copy(complete = true), UIMessagePart.Text("tail"),
        ))
        val restored = me.rerere.ai.util.json.decodeFromString<UIMessage>(me.rerere.ai.util.json.encodeToString(original))
        val request = ModelRequestMessage(MessageRole.ASSISTANT, restored.parts, samplingPartCounts = listOf(5, 1))
        val contents = provider.buildContents(listOf(request), RequestMediaCapabilities.NONE, "gemini-test", profile)
        assertEquals(listOf("model", "user", "model"), contents.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        val parts = contents[0].jsonObject["parts"]!!.jsonArray
        assertEquals(raw("toolCall"), parts[0])
        assertTrue("functionCall" in parts[1].jsonObject)
        assertEquals(raw("toolResponse"), parts[2])
        assertEquals(raw("toolCall"), parts[3])
        assertEquals(raw("toolResponse"), parts[4])
        assertEquals(1, contents[1].jsonObject["parts"]!!.jsonArray.size)
        val switched = provider.buildContents(listOf(request), RequestMediaCapabilities.NONE, "other-model", profile).toString()
        assertFalse(switched.contains("toolCall"))
        assertFalse(switched.contains("signature-tool"))
    }

    @Test fun `stream server records stay separate and are replayable only at successful terminal`() {
        for (finish in listOf("STOP", "MAX_TOKENS", "SAFETY", null)) {
            val state = GoogleContentState()
            fun choice(parts: List<UIMessagePart>, reason: String? = null) = UIMessageChoice(
                0, UIMessage(role = MessageRole.ASSISTANT, parts = parts), null, reason)
            val first = state.decorate(choice(listOf(UIMessagePart.Text("before"), server("toolCall"))))
            val second = state.decorate(choice(listOf(UIMessagePart.Text("between"), server("toolResponse"), server("toolCall"))))
            assertNotEquals(first.contentBlockSlots[0], second.contentBlockSlots[0])
            assertNotEquals(first.contentBlockSlots[1], second.contentBlockSlots[2])
            assertTrue(second.delta!!.parts.filterIsInstance<UIMessagePart.ServerTool>().none { it.complete })
            val terminal = state.decorate(choice(emptyList(), finish))
            assertEquals(if (finish == "STOP") 3 else 0, terminal.delta!!.parts.size)
            if (finish == "STOP") {
                assertTrue(terminal.delta!!.parts.filterIsInstance<UIMessagePart.ServerTool>().all { it.complete })
                assertEquals(listOf(first.contentBlockSlots[1], second.contentBlockSlots[1], second.contentBlockSlots[2]), terminal.contentBlockSlots)
            }
        }
    }

    @Test fun `full response uses ordered slots without duplicating complete server parts`() {
        val parts = listOf(server("toolCall").copy(complete = true), UIMessagePart.Text("middle"), server("toolResponse").copy(complete = true))
        val choice = GoogleContentState().decorate(UIMessageChoice(0, null,
            UIMessage(role = MessageRole.ASSISTANT, parts = parts), "STOP"))
        assertEquals(parts, choice.message!!.parts)
        assertNull(choice.delta)
        assertEquals(3, choice.contentBlockSlots.distinct().size)
        val state = GoogleContentState()
        val thought = UIMessagePart.Reasoning("a", metadata = GoogleThoughtMetadata(thought = true).toMetadata())
        fun delta(part: UIMessagePart) = UIMessageChoice(0, UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part)), null, null)
        assertEquals(state.decorate(delta(thought)).contentBlockSlots, state.decorate(delta(thought.copy(reasoning = "b"))).contentBlockSlots)
    }
}
