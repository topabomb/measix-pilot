package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.ui.*
import me.rerere.ai.core.MessageRole
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ResponseDisplayStateTest {
    @Test fun `repeated completed image preserves its result and display slot`() {
        val api = ResponseAPI(OkHttpClient())
        val state = ResponseStreamState()
        fun done(result: String) = Json.parseToJsonElement("""{"type":"response.output_item.done","output_index":0,"item":{"type":"image_generation_call","id":"img","result":"$result"}}""").jsonObject
        val first = api.parseResponseDelta(done("QUJD"), state)!!.choices.single()
        val repeated = api.parseResponseDelta(done("QUJD"), state)!!.choices.single()
        assertEquals(first.contentBlockSlots, repeated.contentBlockSlots)
        assertEquals(first.delta!!.parts, repeated.delta!!.parts)
        for (replacement in listOf("changed", "", " ")) {
            assertThrows(IllegalStateException::class.java) { api.parseResponseDelta(done(replacement), state) }
        }
        assertEquals("QUJD", state.outputItems().single()["result"]!!.jsonPrimitive.content)
    }

    @Test fun `terminal only image is emitted and an announced image retains its display slot`() {
        val api = ResponseAPI(OkHttpClient())
        for (announce in listOf(false, true)) {
            val state = ResponseStreamState()
            val added = if (announce) api.parseResponseDelta(Json.parseToJsonElement("""{"type":"response.output_item.added","output_index":0,"item":{"type":"image_generation_call","id":"img"}}""").jsonObject, state) else null
            val terminal = api.parseResponseDelta(Json.parseToJsonElement("""{"type":"response.completed","response":{"output":[{"type":"image_generation_call","id":"img","status":"completed","result":"QUJD"}]}}""").jsonObject, state)!!.choices.single()
            assertEquals("QUJD", (terminal.delta!!.parts.single() as UIMessagePart.Image).url)
            if (announce) assertEquals(added!!.choices.single().contentBlockSlots, terminal.contentBlockSlots)
        }
    }

    @Test fun `successful image terminal rejects absent blank changed and unfinished results`() {
        val api = ResponseAPI(OkHttpClient())
        for (result in listOf("null", "\"\"", "\" \"", "42")) {
            val output = """{"type":"image_generation_call","id":"img","result":$result}"""
            assertThrows(IllegalStateException::class.java) {
                api.parseResponseOutput(Json.parseToJsonElement("""{"status":"completed","output":[$output]}""").jsonObject)
            }
            assertThrows(IllegalStateException::class.java) {
                api.parseResponseDelta(Json.parseToJsonElement("""{"type":"response.completed","response":{"output":[$output]}}""").jsonObject)
            }
        }
        for (output in listOf("[]", """[{"type":"image_generation_call","id":"img","result":"changed"}]""",
            """[{"type":"image_generation_call","id":"img","result":"QUJD","status":"in_progress"}]""")) {
            val state = ResponseStreamState()
            api.parseResponseDelta(Json.parseToJsonElement("""{"type":"response.output_item.done","output_index":0,"item":{"type":"image_generation_call","id":"img","result":"QUJD"}}""").jsonObject, state)
            assertThrows(IllegalStateException::class.java) {
                api.parseResponseDelta(Json.parseToJsonElement("""{"type":"response.completed","response":{"output":$output}}""").jsonObject, state)
            }
        }
    }

    private fun event(id: String?, index: Int?, content: Int = 0) = buildJsonObject {
        put("type", "response.output_text.delta")
        id?.let { put("item_id", it) }; index?.let { put("output_index", it) }; put("content_index", content)
    }
    private fun text() = UIMessageChoice(0, UIMessage.assistant("text"), null, null)

    @Test fun `late item id aliases stable output and content identities without combining separate content`() {
        val state = ResponseDisplayState()
        val initial = state.decorate(event(null, 0), text()).contentBlockSlots.single()
        assertEquals(initial, state.decorate(event("msg", 0), text()).contentBlockSlots.single())
        assertEquals(initial, state.decorate(event("msg", null), text()).contentBlockSlots.single())
        assertNotEquals(initial, state.decorate(event("msg", null, 1), text()).contentBlockSlots.single())
        assertNotEquals(initial, state.decorate(event("msg", null), text()).contentBlockSlots.single())
    }

    @Test fun `conflicting item and output aliases are rejected`() {
        val state = ResponseDisplayState()
        state.decorate(event("a", 0), text())
        state.decorate(event("b", 1), text())
        assertThrows(IllegalStateException::class.java) { state.decorate(event("a", 1), text()) }
        assertThrows(IllegalStateException::class.java) { state.decorate(event("a", 2), text()) }
        assertThrows(IllegalStateException::class.java) { state.decorate(event("c", 0), text()) }
    }

    @Test fun `metadata only added item binds a late display alias`() {
        val api = ResponseAPI(OkHttpClient())
        val state = ResponseStreamState()
        val first = api.parseResponseDelta(JsonObject(event(null, 0) + ("delta" to JsonPrimitive("a"))), state)!!.choices.single()
        api.parseResponseDelta(Json.parseToJsonElement("""{"type":"response.output_item.added","output_index":0,"item":{"type":"message","id":"msg"}}""").jsonObject, state)
        val second = api.parseResponseDelta(JsonObject(event("msg", null) + ("delta" to JsonPrimitive("b"))), state)!!.choices.single()
        assertEquals(first.contentBlockSlots, second.contentBlockSlots)
    }

    @Test fun `successful terminal cannot lose a server item or leave it active`() {
        val api = ResponseAPI(OkHttpClient())
        for (output in listOf("[]", """[{"type":"web_search_call","id":"ws","status":"searching"}]""")) {
            val state = ResponseStreamState(responseEndpoint = "https://api.openai.com/v1/responses")
            api.parseResponseDelta(Json.parseToJsonElement("""{"type":"response.output_item.added","output_index":0,"item":{"type":"web_search_call","id":"ws","status":"in_progress"}}""").jsonObject, state)
            assertThrows(IllegalStateException::class.java) {
                api.parseResponseDelta(Json.parseToJsonElement("""{"type":"response.completed","response":{"output":$output}}""").jsonObject, state)
            }
        }
    }

    @Test fun `complete response preserves server item position and clickable citation projection`() {
        val body = Json.parseToJsonElement("""{"status":"completed","output":[
          {"type":"web_search_call","id":"ws","status":"completed","action":{"type":"search","queries":["one","two"],"sources":[{"url":"https://example.com"}]}},
          {"type":"message","id":"msg","content":[{"type":"output_text","text":"answer","annotations":[{"type":"url_citation","url":"https://example.com","title":"Source"}]}]}
        ]}""").jsonObject
        val choice = ResponseAPI(OkHttpClient()).parseResponseOutput(body, responseEndpoint = "https://api.openai.com/v1/responses").choices.single()
        val message = choice.message!!
        assertEquals(2, choice.contentBlockSlots.size)
        val server = message.parts.first() as UIMessagePart.ServerTool
        assertTrue(server.complete)
        assertEquals("one\ntwo", (server.presentation as ServerToolPresentation.WebSearch).query)
        assertEquals(listOf(UIMessageAnnotation.UrlCitation("Source", "https://example.com")), message.annotations)
        assertEquals("answer", (message.parts.last() as UIMessagePart.Text).text)
        assertEquals(MessageRole.ASSISTANT, message.role)
        assertTrue(message.getTools().isEmpty())
    }
}
