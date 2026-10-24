package me.rerere.search

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class TavilyAnswerPolicyTest {
    @Test
    fun `old options preserve advanced answers and disabled option survives round trip`() {
        val json = Json { encodeDefaults = true }
        val old = json.decodeFromString(SearchServiceOptions.TavilyOptions.serializer(), "{}")
        assertTrue(old.includeAnswer)
        val disabled = old.copy(includeAnswer = false)
        assertEquals(disabled, json.decodeFromString(SearchServiceOptions.TavilyOptions.serializer(),
            json.encodeToString(SearchServiceOptions.TavilyOptions.serializer(), disabled)))
    }

    @Test
    fun `answer option controls outgoing JSON and suppresses unsolicited generated answer`() = runBlocking {
        val previous = SearchService.httpClient
        val requests = mutableListOf<JsonObject>()
        SearchService.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()
            synchronized(requests) { requests += Json.parseToJsonElement(body).jsonObject }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"query":"test","answer":"Generated summary","results":[{"title":"Source","url":"https://example.test/source","content":"Original text","score":1.0}]}""".toResponseBody())
                .build()
        }.build()
        try {
            for (include in listOf(true, false)) {
                val result = TavilySearchService.search(buildJsonObject { put("query", "test") }, SearchCommonOptions(),
                    SearchServiceOptions.TavilyOptions(apiKey = "test-only", includeAnswer = include)).getOrThrow()
                assertEquals(if (include) "Generated summary" else null, result.answer)
                assertEquals("Original text", result.items.single().text)
                assertEquals("https://example.test/source", result.items.single().url)
            }
            assertEquals(JsonPrimitive("advanced"), requests[0]["include_answer"])
            assertEquals(JsonPrimitive(false), requests[1]["include_answer"])
        } finally {
            SearchService.httpClient.dispatcher.executorService.shutdown()
            SearchService.httpClient.connectionPool.evictAll()
            SearchService.httpClient = previous
        }
    }
}
