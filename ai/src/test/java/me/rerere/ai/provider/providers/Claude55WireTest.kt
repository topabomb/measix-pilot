package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.serialization.json.*
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After

class Claude55WireTest {
    @Test fun `incomplete responses never publish new replay evidence or exclusions`() = runBlocking {
        for (reason in listOf("max_tokens", "unknown_reason", null)) {
            val reasonJson = reason?.let { "\"$it\"" } ?: "null"
            val body = """{"model":"claude-opus-5-5","content":[{"type":"thinking","thinking":"partial","signature":"sig"},{"type":"text","text":"partial"}],"stop_reason":$reasonJson}"""
            val events = listOf(
                """{"type":"message_start","message":{"model":"claude-opus-5-5"}}""",
                """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"partial","signature":"sig"}}""",
                """{"type":"content_block_stop","index":0}""",
                """{"type":"message_delta","delta":{"stop_reason":$reasonJson}}""",
                """{"type":"message_stop"}""",
            ).joinToString("") { "data: $it\n\n" }
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val stream = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8().contains("\"stream\":true")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body((if (stream) events else body).toResponseBody((if (stream) "text/event-stream" else "application/json").toMediaType())).build()
            }.build()
            try {
                val provider = ClaudeProvider(client)
                val setting = ProviderSetting.Claude(baseUrl = "https://example.com", apiKey = "fixture")
                val params = TextGenerationParams(Model(modelId = "claude-opus-5-5"), reasoningLevel = ReasoningLevel.AUTO)
                val input = listOf(ModelRequestMessage.user("request"))
                val failure = runCatching { provider.generateText(setting, input, params) }.exceptionOrNull()
                assertTrue(failure is ProviderResponseException)
                val message = (failure as ProviderResponseException).response.choices.single().message!!
                assertNull(message.providerMetadata)
                assertTrue(message.parts.filterIsInstance<UIMessagePart.Reasoning>().all { it.metadataAs<ClaudeReasoningMetadata>()?.claudeReplay == null })
                val chunks = mutableListOf<MessageChunk>()
                assertNotNull(runCatching { provider.streamText(setting, input, params).collect { chunks += it } }.exceptionOrNull())
                assertTrue(chunks.isNotEmpty())
                assertTrue(chunks.flatMap { it.choices }.all { it.delta?.providerMetadata == null })
                assertTrue(chunks.flatMap { it.choices }.flatMap { it.delta?.parts.orEmpty() }
                    .filterIsInstance<UIMessagePart.Reasoning>().all { it.metadataAs<ClaudeReasoningMetadata>()?.claudeReplay == null })
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }

    @Before fun logging() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }
    @After fun clearLogging() { unmockkStatic(Log::class) }

    @Test fun `stream signatures retain block identity and proof records final response source`() = runBlocking {
        val events = listOf(
            """{"type":"message_start","message":{"model":"claude-opus-5-5"}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"complete"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":"answer"}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            """{"type":"message_stop"}""",
        ).joinToString("") { "data: $it\n\n" }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request().newBuilder().url("https://actual.example/relay").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(events.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        try {
            val provider = ClaudeProvider(client)
            val params = TextGenerationParams(Model(modelId = "claude-opus-5-5"), reasoningLevel = ReasoningLevel.AUTO)
            val setting = ProviderSetting.Claude(baseUrl = "https://initial.example", apiKey = "fixture")
            suspend fun collect() = provider.streamText(setting, listOf(ModelRequestMessage.user("request")), params).toList()
            val chunks = collect()
            val reasoning = chunks.flatMap { it.choices }.filter { it.delta?.parts?.singleOrNull() is UIMessagePart.Reasoning }
            assertEquals(1, reasoning.flatMap { it.contentBlockSlots }.distinct().size)
            val metadata = reasoning.fold(null as JsonObject?) { before, choice -> mergeReasoningPartMetadata(before, choice.delta!!.parts.single().metadata) }
            val part = UIMessagePart.Reasoning("", kotlin.time.Instant.fromEpochMilliseconds(1), metadata = metadata)
            val saved = part.metadataAs<ClaudeReasoningMetadata>()!!
            assertEquals("sig-complete", saved.signature)
            assertEquals("https://actual.example/relay", saved.claudeReplay?.endpoint)
            assertEquals("claude-opus-5-5", saved.claudeReplay?.model)
            assertEquals(claudeBlockDigest(Json.parseToJsonElement("""{"type":"thinking","thinking":"","signature":"sig-complete"}""").jsonObject), saved.claudeReplay?.blockDigest)
            assertTrue(partsAreValidToUpload(listOf(part)))
            assertNotNull(chunks.last().choices.single().delta!!.metadataAs<ClaudeResponseMetadata>())
            val nextSlot = collect().flatMap { it.choices }.flatMap { it.contentBlockSlots }.first()
            assertNotEquals(reasoning.first().contentBlockSlots.first().responseId, nextSlot.responseId)
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun `same response interleaved tools and thinking replay before one result batch`() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = Json.parseToJsonElement(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).jsonObject
            requests += body
            val content = if (requests.size == 1) """[{"type":"thinking","thinking":"","signature":"first"},{"type":"tool_use","id":"a","name":"a","input":{}},{"type":"thinking","thinking":"second","signature":"second"},{"type":"tool_use","id":"b","name":"b","input":{}}]"""
                else """[{"type":"text","text":"done"}]"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"id":"response","model":"claude-opus-5-5","content":$content,"stop_reason":"${if (requests.size == 1) "tool_use" else "end_turn"}"}""".toResponseBody()).build()
        }.build()
        try {
            val provider = ClaudeProvider(client)
            val setting = ProviderSetting.Claude(baseUrl = "https://example.com", apiKey = "fixture")
            val model = Model(modelId = "claude-opus-5-5", abilities = listOf(ModelAbility.REASONING, ModelAbility.TOOL))
            val input = ModelRequestMessage.user("request")
            val response = provider.generateText(setting, listOf(input), TextGenerationParams(model, reasoningLevel = ReasoningLevel.AUTO)).choices.single().message!!
            val completed = response.parts.map { part -> if (part is UIMessagePart.Tool) part.copy(
                resultStatus = ToolResultStatus.COMPLETED, output = listOf(UIMessagePart.Text("result ${part.toolName}"))) else part }
            val copiedModel = model.copy(id = me.rerere.common.configuration.ConfigurationReference.random())
            provider.generateText(setting, listOf(input, ModelRequestMessage(response.role, completed, model.id, response.providerMetadata)), TextGenerationParams(copiedModel, reasoningLevel = ReasoningLevel.AUTO))
            val messages = requests.last().getValue("messages").jsonArray
            assertEquals(3, messages.size)
            assertEquals(listOf("thinking", "tool_use", "thinking", "tool_use"), messages[1].jsonObject.getValue("content").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content })
            assertEquals(2, messages[2].jsonObject.getValue("content").jsonArray.size)
            assertFalse(requests.last().toString().contains(CLAUDE_REPLAY_ANNOTATION))
            assertTrue(completed.filterIsInstance<UIMessagePart.Reasoning>().all { it.metadataAs<ClaudeReasoningMetadata>()?.claudeReplay != null })
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun `unsupported final custom parameters fail before any HTTP call even without reasoning capability`() = runBlocking {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { calls++; error("unexpected IO") }.build()
        try {
            val provider = ClaudeProvider(client)
            val setting = ProviderSetting.Claude(baseUrl = "https://example.com", apiKey = "fixture")
            val model = Model(modelId = "claude-opus-5-5", abilities = emptyList())
            for (params in listOf(TextGenerationParams(model, reasoningLevel = ReasoningLevel.OFF),
                TextGenerationParams(model, customBody = listOf(CustomBody("temperature", JsonPrimitive(0.5)))),
                TextGenerationParams(model, customBody = listOf(CustomBody("thinking", Json.parseToJsonElement("""{"type":"disabled"}""")))))) {
                try { provider.generateText(setting, listOf(ModelRequestMessage.user("request")), params); fail("expected validation failure") }
                catch (expected: IllegalArgumentException) { assertTrue(expected.message.orEmpty().startsWith("unsupported_")) }
            }
            assertEquals(0, calls)
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
}
