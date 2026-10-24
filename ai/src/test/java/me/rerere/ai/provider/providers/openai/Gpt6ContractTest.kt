package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.core.*
import me.rerere.ai.provider.*
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.testsupport.canonicalMultiRoundToolTurn
import me.rerere.ai.testsupport.toModelRequests
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class Gpt6ContractTest {
    private val ids = listOf("gpt-6-astra", "gpt-6.1-sol", "gpt-6-sol", "gpt-6-luna")
    private val chat = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    private val response = ResponseAPI(OkHttpClient())
    private val tool = FrozenToolDefinition("lookup", "lookup", buildJsonObject { put("type", "object") }, "")
    private fun body(id: String, level: ReasoningLevel, responses: Boolean, host: String = "api.openai.com",
                     stream: Boolean = false, tools: Boolean = false, custom: List<CustomBody> = emptyList(),
                     temperature: Float? = null): JsonObject {
        val params = TextGenerationParams(model = Model(modelId = id, abilities = listOf(ModelAbility.TOOL)),
            reasoningLevel = level, tools = if (tools) listOf(tool) else emptyList(), customBody = custom, temperature = temperature)
        val provider = ProviderSetting.OpenAI(baseUrl = "https://$host/v1", useResponseApi = responses)
        val messages = listOf(ModelRequestMessage.user("hello"))
        return if (responses) response.buildRequestBody(provider, messages, params, stream)
        else chat.buildChatCompletionRequest(messages, params, provider, stream)
    }

    @Test fun `registry only grants verified identities image input and tool reasoning`() {
        for (id in ids) {
            assertTrue(ModelRegistry.OPENAI_GPT_6_SERIES.match(id))
            assertEquals(listOf(Modality.TEXT, Modality.IMAGE), ModelRegistry.MODEL_INPUT_MODALITIES.getData(id))
            assertEquals(listOf(Modality.TEXT), ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(id))
            assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), ModelRegistry.MODEL_ABILITIES.getData(id))
            for (suffix in listOf("-preview", "-20261001", "-chat")) assertFalse(ModelRegistry.OPENAI_GPT_6_SERIES.match(id + suffix))
        }
        for (id in listOf("gpt-6", "gpt-6.1-luna", "openai/gpt-6-astra", "gpt-6.10-sol")) {
            assertFalse(ModelRegistry.OPENAI_GPT_6_SERIES.match(id))
        }
    }

    @Test fun `effort stays exact on both protocols and transports without a reasoning capability toggle`() {
        for (id in ids) for (responses in listOf(false, true)) for (stream in listOf(false, true)) {
            for (host in listOf("api.openai.com", "relay.example.com")) for (level in ReasoningLevel.entries) {
                if (level == ReasoningLevel.OFF && ModelRegistry.GPT_6_REQUIRED_REASONING.match(id)) {
                    assertThrows(IllegalArgumentException::class.java) { body(id, level, responses, host, stream) }
                } else {
                    val result = body(id, level, responses, host, stream)
                    val effort = if (responses) (result["reasoning"] as? JsonObject)?.get("effort") else result["reasoning_effort"]
                    assertEquals(if (level == ReasoningLevel.AUTO) null else JsonPrimitive(level.effort), effort)
                }
            }
        }
    }

    @Test fun `tool calls require responses except Sol and Luna with none`() {
        for (id in ids) {
            assertTrue(body(id, ReasoningLevel.HIGH, true, tools = true)["tools"]!!.jsonArray.isNotEmpty())
            for (level in listOf(ReasoningLevel.AUTO, ReasoningLevel.HIGH)) {
                val error = assertThrows(IllegalArgumentException::class.java) { body(id, level, false, tools = true) }
                assertTrue(error.message.orEmpty().contains("gpt6_tools_require_responses"))
            }
            if (ModelRegistry.GPT_6_OPTIONAL_REASONING.match(id)) {
                assertTrue(body(id, ReasoningLevel.OFF, false, tools = true)["tools"]!!.jsonArray.isNotEmpty())
            }
        }
    }

    @Test fun `sampling and custom effort cannot bypass final request constraints`() {
        for (id in ids) for (responses in listOf(false, true)) {
            assertThrows(IllegalArgumentException::class.java) { body(id, ReasoningLevel.AUTO, responses, temperature = 0.5f) }
            for (field in listOf("temperature", "top_p", "top_logprobs") + if (responses) emptyList() else listOf("logprobs")) {
                assertThrows(IllegalArgumentException::class.java) {
                    body(id, ReasoningLevel.HIGH, responses, custom = listOf(CustomBody(field, JsonPrimitive(1))))
                }
            }
            for (effort in listOf("minimal", "ultra", "auto")) {
                val custom = if (responses) CustomBody("reasoning", buildJsonObject { put("effort", effort) })
                    else CustomBody("reasoning_effort", JsonPrimitive(effort))
                assertThrows(IllegalArgumentException::class.java) { body(id, ReasoningLevel.AUTO, responses, custom = listOf(custom)) }
            }
            if (ModelRegistry.GPT_6_OPTIONAL_REASONING.match(id)) {
                assertEquals(JsonPrimitive(0.5f), body(id, ReasoningLevel.OFF, responses, temperature = 0.5f)["temperature"])
            }
        }
    }

    @Test fun `historic tool messages are validated even with no current tool declarations`() {
        for (id in ids) {
            val history = canonicalMultiRoundToolTurn().toModelRequests()
            val params = TextGenerationParams(model = Model(modelId = id), reasoningLevel = ReasoningLevel.HIGH)
            val provider = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1")
            val error = assertThrows(IllegalArgumentException::class.java) {
                chat.buildChatCompletionRequest(history, params, provider)
            }
            assertTrue(error.message.orEmpty().contains("gpt6_tools_require_responses"))
            val replay = response.buildRequestBody(provider, history, params, false)["input"]!!.jsonArray
            assertEquals(2, replay.count { it.jsonObject["type"] == JsonPrimitive("function_call") })
            assertEquals(2, replay.count { it.jsonObject["type"] == JsonPrimitive("function_call_output") })
            if (ModelRegistry.GPT_6_OPTIONAL_REASONING.match(id)) {
                assertTrue(chat.buildChatCompletionRequest(history, params.copy(reasoningLevel = ReasoningLevel.OFF), provider)
                    ["messages"]!!.jsonArray.any { it.jsonObject["role"] == JsonPrimitive("tool") })
            }
        }
    }
}
