package me.rerere.ai.provider.providers

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ClaudeServerSearchTest {
    private val call = """{"type":"server_tool_use","id":"srv_1","name":"web_search","input":{"query":"weather"}}"""
    private val result = """{"type":"web_search_tool_result","tool_use_id":"srv_1","content":[{"type":"web_search_result","url":"https://example.com/weather","title":"Weather","encrypted_content":"opaque"}]}"""
    private val citation = """{"type":"web_search_result_location","url":"https://example.com/weather","title":"Weather","encrypted_index":"index","cited_text":"sunny"}"""
    private val setting = ProviderSetting.Claude(baseUrl = "https://example.com", apiKey = "fixture")
    private val params = TextGenerationParams(Model(modelId = "claude-sonnet-4-5", tools = setOf(BuiltInTools.Search)))

    @Before fun logging() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }
    @After fun cleanup() { unmockkStatic(Log::class) }

    @Test fun `complete search preserves order citations and opaque blocks only for original endpoint`() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val body = """{"model":"claude-sonnet-4-5","content":[$call,$result,{"type":"text","text":"sunny","citations":[$citation]}],"stop_reason":"end_turn"}"""
        withClient(body, "application/json", requests) { client ->
            val provider = ClaudeProvider(client)
            val input = ModelRequestMessage.user("weather?")
            val response = provider.generateText(setting, listOf(input), params).choices.single().message!!
            assertTrue(response.getTools().isEmpty())
            assertEquals(listOf("server_tool_use", "web_search_tool_result"), response.parts.filterIsInstance<UIMessagePart.ServerTool>()
                .map { it.block.getValue("type").jsonPrimitive.content })
            assertEquals(listOf(UIMessageAnnotation.UrlCitation("Weather", "https://example.com/weather")), response.annotations)
            assertEquals("web_search_20250305", requests.first().getValue("tools").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
            val history = listOf(input, ModelRequestMessage(response.role, response.parts, providerMetadata = response.providerMetadata))
            provider.generateText(setting, history, params)
            val content = requests.last().getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonArray
            assertEquals(Json.parseToJsonElement(call), content[0])
            assertEquals(Json.parseToJsonElement(result), content[1])
            assertEquals(Json.parseToJsonElement(citation), content[2].jsonObject.getValue("citations").jsonArray.single())
            provider.generateText(setting.copy(baseUrl = "https://other.example.com"), history, params)
            val other = requests.last().getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonArray
            assertEquals(listOf("text"), other.map { it.jsonObject.getValue("type").jsonPrimitive.content })
            assertFalse(other.toString().contains("encrypted_"))
        }
    }

    @Test fun `stream server input never becomes local tool and pause appears only at successful terminal`() = runBlocking {
        val events = listOf(
            """{"type":"message_start","message":{"model":"claude-sonnet-4-5","usage":{"input_tokens":10}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"srv_1","name":"web_search","input":{}}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"weather\"}"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":$result}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"message_delta","delta":{"stop_reason":"pause_turn"},"usage":{"output_tokens":7}}""",
            """{"type":"message_stop"}""",
        )
        val wire = events.joinToString("") { "data: $it\n\n" }
        withClient(wire, "text/event-stream") { client ->
            val chunks = ClaudeProvider(client).streamText(setting, listOf(ModelRequestMessage.user("weather?")), params).toList()
            assertTrue(chunks.flatMap { it.choices }.all { it.delta!!.getTools().isEmpty() })
            assertTrue(chunks.dropLast(1).all { it.choices.single().continuation == null })
            val last = chunks.last().choices.single()
            assertEquals(ProviderContinuation.CONTINUE_ASSISTANT, last.continuation)
            assertTrue(last.delta!!.isValidToUpload())
            val blocks = last.delta!!.parts.filterIsInstance<UIMessagePart.ServerTool>()
            assertTrue(blocks.all { it.complete })
            assertEquals(Json.parseToJsonElement(call), blocks.first().block)
            assertEquals(listOf(0, 1), last.contentBlockSlots.map { it.index })
        }
        withClient(events.dropLast(1).joinToString("") { "data: $it\n\n" }, "text/event-stream") { client ->
            val chunks = mutableListOf<MessageChunk>()
            val failure = runCatching { ClaudeProvider(client).streamText(setting, listOf(ModelRequestMessage.user("weather?")), params).collect { chunks += it } }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(chunks.all { it.choices.single().continuation == null })
            assertTrue(chunks.flatMap { it.choices.single().delta!!.parts }.filterIsInstance<UIMessagePart.ServerTool>().none { it.complete })
        }
    }

    @Test fun `stream citations arrive as metadata update without repeating text and errors remain results`() {
        val provider = ClaudeProvider(OkHttpClient())
        val state = ClaudeStreamState(endpoint = "https://example.com/messages")
        fun event(raw: String) = provider.parseStreamEvent(Json.parseToJsonElement(raw).jsonObject, state)
        event("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"sunny"}}""")
        event("""{"type":"content_block_delta","index":0,"delta":{"type":"citations_delta","citation":$citation}}""")
        event("""{"type":"content_block_stop","index":0}""")
        event("""{"type":"content_block_start","index":1,"content_block":{"type":"web_search_tool_result","tool_use_id":"srv_1","content":{"type":"web_search_tool_result_error","error_code":"max_uses_exceeded"}}}""")
        event("""{"type":"content_block_stop","index":1}""")
        event("""{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""")
        val delta = event("""{"type":"message_stop"}""").choices.single().delta!!
        assertEquals("", delta.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertEquals(1, delta.annotations.size)
        assertTrue(delta.parts.filterIsInstance<UIMessagePart.ServerTool>().single().block.toString().contains("max_uses_exceeded"))
    }

    @Test fun `persisted terminal history retains complete prefix and drops server state from incomplete tail`() {
        val source = ServerToolSource(ServerToolProtocol.CLAUDE_MESSAGES, "https://example.com/messages")
        val block = UIMessagePart.ServerTool(source, Json.parseToJsonElement(call).jsonObject, complete = true)
        val sampled = StepModelResult("pause_turn", StepUsage(), 1, null, null,
            me.rerere.ai.core.UsageCompleteness.NONE, null, ProviderContinuation.CONTINUE_ASSISTANT)
        val message = UIMessage(role = me.rerere.ai.core.MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Step(kotlin.uuid.Uuid.random(), 0, kotlin.time.Clock.System.now(), sampled, StepOutcome.Continue),
            block,
            UIMessagePart.Step(kotlin.uuid.Uuid.random(), 1, kotlin.time.Clock.System.now(), sampled, StepOutcome.Incomplete),
            block,
            UIMessagePart.Text("partial", ClaudeTextMetadata(JsonArray(listOf(Json.parseToJsonElement(citation))), source).toMetadata()),
        ), terminalStatus = MessageTerminalStatus.INCOMPLETE)
        val persisted = Json.decodeFromString(UIMessage.serializer(), Json.encodeToString(UIMessage.serializer(), message))
        val replay = persisted.replaySafeProjection()!!
        assertEquals(1, replay.parts.filterIsInstance<UIMessagePart.ServerTool>().size)
        assertTrue(replay.parts.filterIsInstance<UIMessagePart.Text>().all { it.metadataAs<ClaudeTextMetadata>()?.claudeCitations == null })
        assertTrue(replay.toText().contains("partial"))
        val display = block.presentation as ServerToolPresentation.WebSearch
        assertEquals("weather", display.query)
        assertTrue(display.links.isEmpty())
    }

    private suspend fun withClient(body: String, mime: String, requests: MutableList<JsonObject> = mutableListOf(), action: suspend (OkHttpClient) -> Unit) {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += Json.parseToJsonElement(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).jsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody(mime.toMediaType())).build()
        }.build()
        try { action(client) } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
}
