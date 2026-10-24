package me.rerere.ai.provider.providers.openai

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import me.rerere.ai.core.FrozenToolDefinition
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

class ResponseFunctionResultsTest {
    @Before fun logging() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }
    @After fun cleanup() { unmockkStatic(Log::class) }
    private val api = ResponseAPI(OkHttpClient())
    private val endpoint = "https://gateway.example/v1/responses"
    private val call = obj("""{"type":"function_call","id":"item_call","call_id":"call_1","name":"lookup","arguments":"{}"}""")
    private fun result(output: JsonElement = JsonPrimitive("")) = buildJsonObject {
        put("type", "function_call_output"); put("call_id", "call_1"); put("output", output)
    }
    private fun response(items: List<JsonObject>) = buildJsonObject {
        put("status", "completed"); put("output", JsonArray(items))
    }
    private fun parse(items: List<JsonObject>, names: Set<String> = setOf("lookup")) =
        api.parseResponseOutput(response(items), ResponseEndpointProfile.OPENAI_COMPATIBLE, names, endpoint)
            .choices.single().message!!
    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun event(type: String, item: JsonObject, index: Int) = buildJsonObject {
        put("type", type); put("item", item); put("output_index", index)
    }

    @Test fun `actual JSON and SSE requests bind result acceptance to sent tools and preserve source`() = runBlocking {
        for (stream in listOf(false, true)) {
            val result = result(JsonPrimitive("remote value"))
            val complete = response(listOf(result, call))
            val events = listOf(event("response.output_item.added", result, 0),
                event("response.output_item.added", call, 1), event("response.output_item.done", call, 1),
                buildJsonObject { put("type", "response.completed"); put("response", complete) })
            val body = if (stream) events.joinToString("") { "data: $it\n\n" } else complete.toString()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(body.toResponseBody((if (stream) "text/event-stream" else "application/json").toMediaType())).build()
            }.build()
            try {
                val transport = ResponseAPI(client)
                val setting = ProviderSetting.OpenAI(baseUrl = "https://gateway.example/v1", useResponseApi = true)
                val params = TextGenerationParams(Model(modelId = "fixture", abilities = listOf(ModelAbility.TOOL)),
                    tools = listOf(FrozenToolDefinition("lookup", "lookup", null, "")))
                suspend fun request(params: TextGenerationParams) = if (stream)
                    transport.streamText(setting, listOf(ModelRequestMessage.user("lookup")), params).toList().last().choices.single().delta!!
                else transport.generateText(setting, listOf(ModelRequestMessage.user("lookup")), params).choices.single().message!!
                val tool = request(params).getTools().single()
                assertTrue(tool.hasReplayResult)
                assertEquals(endpoint, tool.metadataAs<ResponseFunctionResultMetadata>()!!.responseFunctionResult!!.endpoint)
                assertNotNull(runCatching { request(params.copy(tools = emptyList())) }.exceptionOrNull())
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }

    @Test fun `empty and structured remote results survive serialization and replay exactly once`() {
        for (value in listOf(JsonPrimitive(""), obj("""{"rows":[1,2]}"""), JsonArray(emptyList()))) {
            val message = parse(listOf(call, result(value)))
            val tool = message.getTools().single()
            assertTrue(tool.hasReplayResult)
            assertEquals(ToolResultStatus.COMPLETED, tool.resultStatus)
            assertEquals(value, tool.metadataAs<ResponseFunctionResultMetadata>()!!.responseFunctionResult!!.output)
            val saved = Json.decodeFromString(UIMessage.serializer(), Json.encodeToString(UIMessage.serializer(), message))
            val replay = api.buildMessages(listOf(ModelRequestMessage(saved.role, saved.parts, providerMetadata = saved.providerMetadata)),
                ResponseEndpointProfile.OPENAI_COMPATIBLE)
            assertEquals(listOf(call, result(value)), replay.toList())
        }
    }

    @Test fun `remote result correlation rejects unknown ambiguous duplicate and malformed output`() {
        assertThrows(IllegalStateException::class.java) { parse(listOf(call, result()), emptySet()) }
        assertThrows(IllegalStateException::class.java) { parse(listOf(result())) }
        assertThrows(IllegalStateException::class.java) { parse(listOf(call, call, result())) }
        assertThrows(IllegalStateException::class.java) { parse(listOf(call, result(), result())) }
        assertThrows(IllegalStateException::class.java) { parse(listOf(call, result(JsonPrimitive(true)))) }
        assertThrows(IllegalStateException::class.java) { parse(listOf(call, JsonObject(result() - "output"))) }
    }

    @Test fun `result before call waits for successful terminal and targets the original tool slot`() {
        val state = ResponseStreamState(setOf("lookup"), endpoint)
        val result = result(obj("""{"ok":true}"""))
        assertNull(api.parseResponseDelta(event("response.output_item.added", result, 0), state))
        assertNull(api.parseResponseDelta(event("response.output_item.done", result, 0), state))
        val start = api.parseResponseDelta(event("response.output_item.added", call, 1), state)!!.choices.single()
        api.parseResponseDelta(event("response.output_item.done", call, 1), state)
        val terminal = api.parseResponseDelta(buildJsonObject {
            put("type", "response.completed"); put("response", response(listOf(result, call)))
        }, state)!!.choices.single()
        assertEquals(start.toolCallSlots, terminal.toolCallSlots)
        val merged = start.delta!!.getTools().single().merge(terminal.delta!!.getTools().single())
        assertEquals("lookup", merged.toolName)
        assertEquals("{}", merged.input)
        assertTrue(merged.hasReplayResult)
        assertEquals(endpoint, merged.metadataAs<ResponseFunctionResultMetadata>()!!.responseFunctionResult!!.endpoint)
    }

    @Test fun `conflicting repeated and terminal results fail without producing a result delta`() {
        for (terminalConflict in listOf(false, true)) {
            val state = ResponseStreamState(setOf("lookup"), endpoint)
            api.parseResponseDelta(event("response.output_item.added", call, 0), state)
            api.parseResponseDelta(event("response.output_item.done", call, 0), state)
            api.parseResponseDelta(event("response.output_item.added", result(), 1), state)
            assertThrows(IllegalStateException::class.java) {
                if (terminalConflict) api.parseResponseDelta(buildJsonObject {
                    put("type", "response.completed"); put("response", response(listOf(call, result(JsonPrimitive("different")))))
                }, state)
                else api.parseResponseDelta(event("response.output_item.done", result(JsonPrimitive("different")), 1), state)
            }
        }
    }

    @Test fun `failed response does not publish buffered remote result`() {
        val state = ResponseStreamState(setOf("lookup"), endpoint)
        api.parseResponseDelta(event("response.output_item.added", call, 0), state)
        api.parseResponseDelta(event("response.output_item.added", result(), 1), state)
        val failed = api.parseResponseDelta(buildJsonObject {
            put("type", "response.failed"); put("response", response(listOf(call, result())))
        }, state)!!
        assertTrue(failed.choices.all { it.delta?.getTools().orEmpty().none { it.hasReplayResult } })
        assertNotNull(state.prematureCloseError())
    }
}
