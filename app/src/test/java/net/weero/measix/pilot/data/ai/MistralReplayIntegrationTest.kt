package net.weero.measix.pilot.data.ai

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.util.KeyRoulette
import net.weero.measix.pilot.data.ai.request.RequestAssembler
import net.weero.measix.pilot.service.runtime.TurnTransition
import net.weero.measix.pilot.service.turn.StepOutputAccumulator
import net.weero.measix.pilot.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class MistralReplayIntegrationTest {
    @Test fun `stream and full result replay alternating thinking and text in the original tool envelope`() = runBlocking {
        val lead = """{"type":"text","text":"Lead"}"""
        val thought = """{"type":"thinking","thinking":[{"type":"text","text":"Think"}]}"""
        val closing = """{"type":"thinking","thinking":[{"type":"text","text":"Check"}]}"""
        val tail = """{"type":"text","text":"Answer"}"""
        val tool = """"tool_calls":[{"index":0,"id":"abc123456","type":"function","function":{"name":"lookup","arguments":"{}"}}]"""
        val full = """{"choices":[{"message":{"content":[$lead,$thought,$tail,$closing],$tool},"finish_reason":"tool_calls"}]}"""
        val events = listOf(
            """{"choices":[{"delta":{"content":[$lead],$tool}}]}""",
            """{"choices":[{"delta":{"content":[$thought]}}]}""",
            """{"choices":[{"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":" further"}]},$tail,$closing]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            "[DONE]",
        ).joinToString("") { "data: $it\n\n" }
        val calls = CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Buffer().apply { requireNotNull(request.body).writeTo(this) }.readUtf8()
            calls += body
            val stream = Json.parseToJsonElement(body).jsonObject["stream"]?.jsonPrimitive?.content == "true"
            val response = if (stream) events.toResponseBody("text/event-stream".toMediaType())
                else full.toResponseBody("application/json".toMediaType())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(response).build()
        }.build()
        val api = ChatCompletionsAPI(client, KeyRoulette.default())
        val setting = ProviderSetting.OpenAI(baseUrl = "https://api.mistral.ai/v1", apiKey = "fixture", includeHistoryReasoning = true)
        val params = TextGenerationParams(Model(modelId = "magistral-medium-latest"))
        val user = listOf(ModelRequestMessage.user("go"))
        try {
            for (stream in listOf(true, false)) withTimeout(10_000) {
                val accumulator = StepOutputAccumulator()
                var active = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(TurnTransition.openStep(0)))
                if (stream) api.streamText(setting, user, params).collect {
                    active = accumulator.accumulate(active, it, null)
                } else active = accumulator.accumulate(active, api.generateText(setting, user, params), null)
                assertEquals(listOf("Text", "Reasoning", "Text", "Reasoning", "Tool"),
                    active.parts.drop(1).map { it.javaClass.simpleName })
                val completed = active.copy(parts = active.parts.map {
                    if (it is UIMessagePart.Tool) it.copy(resultStatus = ToolResultStatus.COMPLETED,
                        output = listOf(UIMessagePart.Text("found"))) else it
                })
                val restored = JsonInstant.decodeFromString<UIMessage>(JsonInstant.encodeToString(completed))
                api.generateText(setting, user + RequestAssembler().assemble(listOf(restored)).providerMessages, params)
                val messages = Json.parseToJsonElement(calls.last()).jsonObject.getValue("messages").jsonArray.map { it.jsonObject }
                assertEquals(listOf("user", "assistant", "tool"), messages.map { it.getValue("role").jsonPrimitive.content })
                val expected = if (stream) thought.replace("Think", "Think further") else thought
                assertEquals(Json.parseToJsonElement("[$lead,$expected,$tail,$closing]"), messages[1]["content"])
                assertEquals("abc123456", messages[2].getValue("tool_call_id").jsonPrimitive.content)
                assertEquals("found", messages[2].getValue("content").jsonPrimitive.content)
            }
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
