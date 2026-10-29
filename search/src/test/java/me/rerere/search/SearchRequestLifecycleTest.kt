package me.rerere.search

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRequestLifecycleTest {
    @Test
    fun `cancelling search while waiting for headers cancels its real call`() = runBlocking {
        checkCancellation(bodyStarted = false)
    }

    @Test
    fun `cancelling search waits for its real response body reader to exit`() = runBlocking {
        checkCancellation(bodyStarted = true)
    }

    private suspend fun checkCancellation(bodyStarted: Boolean) = kotlinx.coroutines.coroutineScope {
        val reached = CompletableDeferred<Unit>()
        val readingBody = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val call = AtomicReference<Call>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                if (bodyStarted) {
                    exchange.sendResponseHeaders(200, 1024)
                    exchange.responseBody.write('{'.code)
                    exchange.responseBody.flush()
                }
                reached.complete(Unit)
                release.await(10, TimeUnit.SECONDS)
            } finally {
                exchange.close()
            }
        }
        server.start()
        val previous = SearchService.httpClient
        val client = OkHttpClient.Builder()
            .readTimeout(1, TimeUnit.MINUTES)
            .eventListener(object : EventListener() {
                override fun callStart(started: Call) { call.set(started) }
                override fun responseBodyStart(started: Call) { readingBody.complete(Unit) }
            })
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder()
                    .url("http://127.0.0.1:${server.address.port}/").build())
            }.build()
        SearchService.httpClient = client
        val request = launch { search() }
        try {
            withTimeout(5_000) {
                reached.await()
                if (bodyStarted) readingBody.await()
            }
            withTimeout(2_000) { request.cancelAndJoin() }
            assertTrue("search cancellation must cancel the underlying HTTP call", call.get().isCanceled())
        } finally {
            release.countDown()
            request.cancelAndJoin()
            SearchService.httpClient = previous
            server.stop(0)
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `search and scrape close rejected response bodies`() = runBlocking {
        for (scrape in listOf(false, true)) {
            var closed = false
            val body = object : ResponseBody() {
                private val bytes = object : ForwardingSource(Buffer().writeUtf8("quota exceeded")) {
                    override fun close() { closed = true; super.close() }
                }.buffer()
                override fun contentType() = null
                override fun contentLength() = 14L
                override fun source(): BufferedSource = bytes
            }
            val previous = SearchService.httpClient
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(429).message("Too Many Requests").body(body).build()
            }.build()
            SearchService.httpClient = client
            try {
                val result = if (scrape) TavilySearchService.scrape(
                    buildJsonObject { put("url", "https://example.test") },
                    SearchCommonOptions(), SearchServiceOptions.TavilyOptions(apiKey = "test-only"),
                ) else search()
                assertTrue(result.exceptionOrNull() is SearchHttpException)
                assertTrue("rejected ${if (scrape) "scrape" else "search"} body must close", closed)
            } finally {
                SearchService.httpClient = previous
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test
    fun `malformed SearXNG response retains the original parser diagnostic`() = runBlocking {
        val previous = SearchService.httpClient
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("{invalid-json".toResponseBody()).build()
        }.build()
        SearchService.httpClient = client
        try {
            val failure = SearXNGService.search(
                buildJsonObject { put("query", "test") }, SearchCommonOptions(),
                SearchServiceOptions.SearXNGOptions(url = "https://search.test"),
            ).exceptionOrNull()
            assertTrue("Expected original parser failure: $failure", failure is kotlinx.serialization.SerializationException)
            assertTrue(failure?.message.orEmpty().isNotBlank())
        } finally {
            SearchService.httpClient = previous
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    private suspend fun search() = TavilySearchService.search(
        buildJsonObject { put("query", "test") },
        SearchCommonOptions(), SearchServiceOptions.TavilyOptions(apiKey = "test-only"),
    )
}
