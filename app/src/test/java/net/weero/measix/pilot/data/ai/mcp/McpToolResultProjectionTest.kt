package net.weero.measix.pilot.data.ai.mcp

import com.sun.net.httpserver.HttpServer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolAttachmentResolution
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.OpenAIProvider
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.request.RequestAssembler
import net.weero.measix.pilot.data.ai.tools.LocatedToolCall
import net.weero.measix.pilot.data.ai.tools.ResolvedToolCall
import net.weero.measix.pilot.data.ai.tools.ToolCallRuntime
import net.weero.measix.pilot.data.ai.tools.ToolExecutionHooks
import net.weero.measix.pilot.data.ai.tools.TurnInteractionCapability
import net.weero.measix.pilot.data.ai.tools.freezeToolSet
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.utils.JsonInstant
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class McpToolResultProjectionTest {
    private data class Case(
        val name: String,
        val texts: List<String>,
        val structured: String?,
        val keepStructured: Boolean,
    ) {
        fun result(isError: Boolean) = CallToolResult(
            content = texts.map(::TextContent),
            structuredContent = structured?.let(Json::parseToJsonElement),
            isError = isError,
        )

        fun expected(isError: Boolean): List<UIMessagePart> = buildList {
            if (isError) {
                add(UIMessagePart.Text(buildJsonObject {
                    put("status", "failed")
                    put("reason", "remote_error")
                    if (keepStructured) put("structured_content", Json.parseToJsonElement(requireNotNull(structured)))
                }.toString()))
            }
            addAll(texts.map { UIMessagePart.Text(it) })
            if (!isError && keepStructured) {
                add(UIMessagePart.Text(buildJsonObject {
                    put("structured_content", Json.parseToJsonElement(requireNotNull(structured)))
                }.toString()))
            }
        }
    }

    private val cases = listOf(
        Case("identical object", listOf("""{"count":1}"""), """{"count":1}""", false),
        Case("nested keys whitespace and escapes", listOf(""" { "nested": {"b":null,"a":"\u4e2d"}, "ok":true } """),
            """{"ok":true,"nested":{"a":"中","b":null}}""", false),
        Case("numeric value", listOf("""{"n":1.0,"zero":-0,"large":9007199254740993}"""),
            """{"large":9007199254740993.0,"zero":0,"n":1e0}""", false),
        Case("different content", listOf("""{"count":1}"""), """{"count":2}""", true),
        Case("unique structured field", listOf("""{"count":1}"""), """{"count":1,"next":"cursor"}""", true),
        Case("large integer difference", listOf("""{"n":9007199254740992}"""), """{"n":9007199254740993}""", true),
        Case("array order", listOf("""{"items":[1,2]}"""), """{"items":[2,1]}""", true),
        Case("number and string", listOf("""{"value":"1"}"""), """{"value":1}""", true),
        Case("boolean and number", listOf("""{"value":true}"""), """{"value":1}""", true),
        Case("null and absent", listOf("""{"value":null}"""), "{}", true),
        Case("text only", listOf("original result"), null, false),
        Case("structured only", emptyList(), """{"count":1}""", true),
        Case("text summary", listOf("Found one item"), """{"count":1}""", true),
        Case("fenced JSON", listOf("```json\n{\"count\":1}\n```"), """{"count":1}""", true),
        Case("trailing prose", listOf("{\"count\":1} done"), """{"count":1}""", true),
        Case("invalid JSON", listOf("{\"count\":"), """{"count":1}""", true),
        Case("escaped line break", listOf("""{"text":"line\nbreak"}"""),
            """{"text":"line\nbreak"}""", false),
        Case("unescaped line break", listOf("{\"text\":\"line\nbreak\"}"),
            """{"text":"line\nbreak"}""", true),
        Case("unescaped control character", listOf("{\"text\":\"a${'\u0001'}b\"}"),
            """{"text":"a\u0001b"}""", true),
        Case("unescaped control in object key", listOf("{\"a\nb\":1}"), """{"a\nb":1}""", true),
        Case("duplicate decoded keys", listOf("""{"count":2,"\u0063ount":1}"""), """{"count":1}""", true),
        Case("split JSON", listOf("{\"count\":", "1}"), """{"count":1}""", true),
        Case("matching block among other text", listOf("before", "{\"count\":1}", "after"), """{"count":1}""", false),
        Case("original repeated blocks remain", listOf("{\"count\":1}", "{\"count\":1}"), """{"count":1}""", false),
        Case("SDK array result", listOf("[1,true,null]"), "[1,true,null]", false),
        Case("SDK scalar result", listOf("42"), "42", false),
        Case("SDK string result", listOf("\"detail\""), "\"detail\"", false),
        Case("SDK boolean result without text", emptyList(), "true", true),
    )

    @Test
    fun `success and remote error outputs retain original content and only unique structured data`() = runBlocking {
        for (isError in listOf(false, true)) for (case in cases) {
            val tool = executeMcpResult(case.result(isError))
            assertEquals("${case.name}, isError=$isError", case.expected(isError), tool.output)
            assertEquals(if (isError) ToolResultStatus.FAILED else ToolResultStatus.COMPLETED, tool.resultStatus)
        }
    }

    @Test
    fun `Chat Completions and Responses send the exact projected results after history round trip`() = runBlocking {
        McpProviderRequestRecorder().use { recorder ->
            for (isError in listOf(false, true)) for (case in cases) {
                val tool = executeMcpResult(case.result(isError))
                val expected = case.expected(isError).filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }
                for (responses in listOf(false, true)) {
                    val body = recorder.capture(tool, responses)
                    assertEquals("${case.name}, isError=$isError, responses=$responses",
                        expected, recorder.toolOutput(body, responses).jsonPrimitive.content)
                }
            }
        }
    }

    @Test fun `serialized non-text content cannot stand in for the structured result`() = runBlocking {
        val original = McpJson.decodeFromString<CallToolResult>(
            """{"content":[{"type":"audio","data":"AAAA","mimeType":"audio/wav"}]}""",
        )
        val serialized = JsonInstant.encodeToString(original.content.single())
        val structured = Json.parseToJsonElement(serialized)
        for (isError in listOf(false, true)) {
            val tool = executeMcpResult(original.copy(structuredContent = structured, isError = isError))
            if (isError) {
                val envelope = Json.parseToJsonElement((tool.output.first() as UIMessagePart.Text).text).jsonObject
                assertEquals(structured, envelope["structured_content"])
                assertEquals(UIMessagePart.Text("[Remote MCP error non-text content omitted]"), tool.output.last())
            } else {
                assertEquals(listOf(UIMessagePart.Text(serialized), UIMessagePart.Text(buildJsonObject {
                    put("structured_content", structured)
                }.toString())), tool.output)
            }
        }
    }
}

/** Exercises the executor through the same typed failure bridge and Runtime used by the coordinator. */
internal suspend fun executeMcpResult(
    result: CallToolResult,
    artifactStore: ArtifactStore = mockk(),
    onArtifactCreated: (OwnedArtifact) -> Unit = {},
): UIMessagePart.Tool {
    val client = mockk<Client>()
    coEvery { client.callTool(any<CallToolRequest>(), any<RequestOptions>()) } returns result
    val definition = Tool(name = "mcp__test__query", description = "query", execute = {
        when (val outcome = McpToolCallExecutor(artifactStore).execute(
            ConfigurationScope.Personal, McpInvocationLease(client, "Test", 1), "query", JsonObject(emptyMap()),
            onArtifactCreated,
        )) {
            is McpInvocationOutcome.Succeeded -> outcome.content
            is McpInvocationOutcome.Failed -> throw outcome.failure
        }
    })
    val source = UIMessagePart.Tool(
        localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call",
        toolName = definition.name, input = "{}",
    )
    val runtime = ToolCallRuntime(Json)
    val preparation = runtime.prepareBatch(
        messageId = Uuid.random(), calls = listOf(LocatedToolCall(0, source)),
        toolIndex = freezeToolSet(listOf(definition)).bindingsByName, availability = TurnInteractionCapability.FULL,
    )
    val outcome = runtime.execute((preparation.resolvedCalls.single() as ResolvedToolCall.Executable).call,
        ToolExecutionHooks(resolveAttachments = { ToolAttachmentResolution() }, reportMetadata = { _, _ -> },
            reportChildRun = {}, registerUnpublishedResource = {}))
    coVerify(exactly = 1) { client.callTool(any<CallToolRequest>(), any<RequestOptions>()) }
    return source.copy(
        output = outcome.output, resultStatus = outcome.resultStatus, clientDiagnostic = outcome.clientDiagnostic,
    )
}

internal class McpProviderRequestRecorder : AutoCloseable {
    private val bodies = CopyOnWriteArrayList<Pair<String, String>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val provider = OpenAIProvider(client)

    init {
        server.createContext("/") { exchange -> exchange.use {
            val path = it.requestURI.path
            bodies += path to it.requestBody.use { body -> body.readBytes().toString(Charsets.UTF_8) }
            val response = if (path == "/responses") {
                """{"id":"next","model":"test","status":"completed","output":[]}"""
            } else {
                """{"id":"next","model":"test","choices":[
                    {"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}"""
            }
            it.responseHeaders.set("Content-Type", "application/json")
            val bytes = response.toByteArray(Charsets.UTF_8)
            it.sendResponseHeaders(200, bytes.size.toLong())
            it.responseBody.use { body -> body.write(bytes) }
        } }
        server.start()
    }

    suspend fun capture(
        tool: UIMessagePart.Tool,
        responses: Boolean,
        params: TextGenerationParams = TextGenerationParams(Model(modelId = "test")),
    ): JsonObject {
        val history = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        val restored = JsonInstant.decodeFromString<UIMessage>(JsonInstant.encodeToString(history))
        assertEquals(history, restored)
        val messages = RequestAssembler().assemble(listOf(restored)).providerMessages
        val count = bodies.size
        withTimeout(10_000) {
            provider.generateText(
                ProviderSetting.OpenAI(baseUrl = "http://127.0.0.1:${server.address.port}", apiKey = "test",
                    useResponseApi = responses), messages, params,
            )
        }
        assertEquals(count + 1, bodies.size)
        val (path, body) = bodies.last()
        assertEquals(if (responses) "/responses" else "/chat/completions", path)
        return Json.parseToJsonElement(body).jsonObject
    }

    fun toolOutput(body: JsonObject, responses: Boolean): JsonElement = if (responses) {
        body.getValue("input").jsonArray.single {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }.jsonObject.getValue("output")
    } else {
        body.getValue("messages").jsonArray.single {
            it.jsonObject["role"]?.jsonPrimitive?.content == "tool"
        }.jsonObject.getValue("content")
    }

    override fun close() {
        server.stop(0)
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
    }
}
