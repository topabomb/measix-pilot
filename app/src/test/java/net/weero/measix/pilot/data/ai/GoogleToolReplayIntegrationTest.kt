package net.weero.measix.pilot.data.ai

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.ui.*
import me.rerere.ai.util.HttpException
import net.weero.measix.pilot.data.ai.request.RequestAssembler
import net.weero.measix.pilot.service.runtime.TurnTransition
import net.weero.measix.pilot.service.turn.StepOutputAccumulator
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class GoogleToolReplayIntegrationTest {
    private val call = """{"toolCall":{"id":"same","toolType":"GOOGLE_SEARCH_WEB","args":{"queries":["one"]}},"thoughtSignature":"server-call"}"""
    private val result = """{"toolResponse":{"id":"same","toolType":"GOOGLE_SEARCH_WEB","response":{"value":"found"}},"thoughtSignature":"server-result"}"""
    private fun function(name: String) = """{"functionCall":{"id":"same","name":"$name","args":{}},"thoughtSignature":"$name-signature"}"""
    private fun event(parts: List<String>, finish: String? = null) =
        """{"candidates":[{"index":0,"content":{"role":"model","parts":[${parts.joinToString()}]}${finish?.let { ",\"finishReason\":\"$it\"" }.orEmpty()}}]}"""

    @Test fun `SSE and JSON preserve mixed response order through real accumulator persistence assembler and replay`() = runBlocking {
        val parts = listOf(call, function("first"), """{"text":"between"}""", result, function("second"), call, result)
        for (stream in listOf(true, false)) withServer(parts.map { event(listOf(it)) } + event(emptyList(), "STOP"), event(parts, "STOP")) { provider, setting, bodies ->
            val params = TextGenerationParams(Model(modelId = "gemini-test"))
            val accumulator = StepOutputAccumulator()
            var active = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(TurnTransition.openStep(0)))
            withTimeout(10_000) {
                if (stream) provider.streamText(setting, listOf(ModelRequestMessage.user("go")), params).collect {
                    active = accumulator.accumulate(active, it, null)
                } else active = accumulator.accumulate(active, provider.generateText(setting, listOf(ModelRequestMessage.user("go")), params), null)
            }
            assertEquals(listOf("ServerTool", "Tool", "Text", "ServerTool", "Tool", "ServerTool", "ServerTool"), active.parts.drop(1).map { it.javaClass.simpleName })
            assertEquals(2, active.getTools().map { it.localCallId }.distinct().size)
            assertEquals(listOf("same", "same"), active.getTools().map { it.providerCallId })
            assertTrue(active.parts.filterIsInstance<UIMessagePart.ServerTool>().all { it.complete })
            val completed = active.copy(parts = active.parts.map {
                if (it is UIMessagePart.Tool) it.copy(resultStatus = ToolResultStatus.COMPLETED, output = listOf(UIMessagePart.Text("result-${it.toolName}"))) else it
            })
            val restored = net.weero.measix.pilot.utils.JsonInstant.decodeFromString<UIMessage>(net.weero.measix.pilot.utils.JsonInstant.encodeToString(completed))
            val requests = RequestAssembler().assemble(listOf(restored)).providerMessages
            assertEquals(listOf(7), requests.single().samplingPartCounts)
            withTimeout(10_000) { provider.generateText(setting, requests, params) }
            val contents = Json.parseToJsonElement(bodies.last()).jsonObject["contents"]!!.jsonArray
            assertEquals(listOf("model", "user"), contents.map { it.jsonObject["role"]!!.jsonPrimitive.content })
            assertEquals(JsonArray(parts.map { Json.parseToJsonElement(it) }), contents[0].jsonObject["parts"])
            val responses = contents[1].jsonObject["parts"]!!.jsonArray.map { it.jsonObject["functionResponse"]!!.jsonObject }
            assertEquals(listOf("first", "second"), responses.map { it["name"]!!.jsonPrimitive.content })
            assertEquals(listOf("same", "same"), responses.map { it["id"]!!.jsonPrimitive.content })

            val modelResult = StepModelResult("STOP", StepUsage(), 1, null, null, me.rerere.ai.core.UsageCompleteness.NONE, null)
            val history = restored.copy(terminalStatus = MessageTerminalStatus.FAILED, parts = restored.parts.map {
                if (it is UIMessagePart.Step) it.copy(modelResult = modelResult, outcome = StepOutcome.Continue) else it
            } + listOf(
                TurnTransition.openStep(1).copy(modelResult = modelResult, outcome = StepOutcome.Continue),
                TurnTransition.openStep(2).copy(modelResult = modelResult, outcome = StepOutcome.Failed),
                active.parts.filterIsInstance<UIMessagePart.ServerTool>().first().copy(complete = false),
                UIMessagePart.Text("failed tail"),
            ))
            val persisted = net.weero.measix.pilot.utils.JsonInstant.decodeFromString<UIMessage>(net.weero.measix.pilot.utils.JsonInstant.encodeToString(history))
            val projected = requireNotNull(persisted.replaySafeProjection())
            val retry = RequestAssembler().assemble(listOf(projected)).providerMessages
            assertEquals(listOf(7, 0), retry.single().samplingPartCounts.take(2))
            assertEquals(3, retry.single().samplingPartCounts.size)
            withTimeout(10_000) { provider.generateText(setting, retry, params) }
            val retryContents = Json.parseToJsonElement(bodies.last()).jsonObject["contents"]!!.jsonArray
            assertEquals(listOf("model", "user", "model"), retryContents.map { it.jsonObject["role"]!!.jsonPrimitive.content })
            assertEquals(contents[0], retryContents[0])
            assertEquals(contents[1], retryContents[1])
            assertTrue(retryContents[2].toString().contains("failed tail"))
            assertFalse(retryContents[2].toString().contains("toolCall"))
            assertFalse(retryContents[2].toString().contains("server-call"))
        }
    }

    @Test fun `incomplete failed and abruptly closed streams never publish replayable server blocks`() = runBlocking {
        for (finish in listOf("MAX_TOKENS", "SAFETY", null)) {
            val events = listOf(event(listOf(call)), event(listOf(result))) + listOfNotNull(finish?.let { event(emptyList(), it) })
            withServer(events, event(emptyList(), "STOP")) { provider, setting, _ ->
                val accumulator = StepOutputAccumulator()
                var active = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(TurnTransition.openStep(0)))
                var failed = false
                try {
                    withTimeout(10_000) {
                        provider.streamText(setting, listOf(ModelRequestMessage.user("go")), TextGenerationParams(Model(modelId = "gemini-test"))).collect {
                            active = accumulator.accumulate(active, it, null)
                        }
                    }
                } catch (_: HttpException) { failed = true }
                assertTrue(failed)
                assertEquals(2, active.parts.filterIsInstance<UIMessagePart.ServerTool>().size)
                assertTrue(active.parts.filterIsInstance<UIMessagePart.ServerTool>().none { it.complete })
            }
        }
    }

    private suspend fun withServer(events: List<String>, full: String, block: suspend (GoogleProvider, ProviderSetting.Google, List<String>) -> Unit) {
        val bodies = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val client = OkHttpClient()
        server.createContext("/") { exchange ->
            bodies += exchange.requestBody.use { String(it.readBytes(), Charsets.UTF_8) }
            val stream = exchange.requestURI.path.contains("streamGenerateContent")
            val body = if (stream) events.joinToString("") { "data: $it\n\n" } else full
            exchange.responseHeaders.add("Content-Type", if (stream) "text/event-stream" else "application/json")
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try { block(GoogleProvider(client), ProviderSetting.Google(baseUrl = "http://127.0.0.1:${server.address.port}", apiKey = "fixture"), bodies) }
        finally { server.stop(0); client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
    }
}
