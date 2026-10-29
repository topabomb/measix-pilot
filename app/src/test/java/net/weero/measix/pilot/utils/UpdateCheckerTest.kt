package net.weero.measix.pilot.utils

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun `one-shot state flow does not restart after collectors leave`() = runBlocking {
        var starts = 0
        val state = flow {
            starts++
            emit(1)
        }.stateInOnce(this, 0)

        assertEquals(1, state.first { it == 1 })
        assertEquals(1, state.first { it == 1 })
        assertEquals(1, starts)
    }

    @Test
    fun `HTTP and decoding failures retain their diagnostics and close the response`() = runTest {
        for (code in listOf(503, 200)) {
            val body = spyk("{invalid-json".toResponseBody())
            val response = Response.Builder()
                .request(Request.Builder().url("https://update.test/version.json").build())
                .protocol(Protocol.HTTP_1_1).code(code).message("Upstream unavailable")
                .body(body).build()
            val call = mockk<Call>(relaxed = true)
            every { call.execute() } returns response
            val client = mockk<OkHttpClient>()
            every { client.newCall(any()) } returns call

            val states = UpdateChecker(client, mockk()).checkUpdate().toList()

            assertEquals(UiState.Loading, states.first())
            val error = (states.last() as UiState.Error).error
            if (code == 503) {
                assertTrue(error is IOException)
                assertEquals("Update check failed: HTTP 503 Upstream unavailable", error.message)
            } else {
                assertTrue("Expected original parser failure: $error", error is SerializationException)
            }
            verify(exactly = 1) { body.close() }
        }
    }

    @Test
    fun `cancelling an update body read closes the real call without publishing failure`() = runBlocking {
        val readingBody = CompletableDeferred<Call>()
        val networkFinished = CompletableDeferred<Unit>()
        val releaseServer = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 1024)
                exchange.responseBody.write('{'.code)
                exchange.responseBody.flush()
                releaseServer.await(10, TimeUnit.SECONDS)
            } finally {
                exchange.close()
            }
        }
        server.start()
        val client = OkHttpClient.Builder()
            .readTimeout(1, TimeUnit.MINUTES)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder()
                    .url("http://127.0.0.1:${server.address.port}/version.json").build())
            }
            .eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) { readingBody.complete(call) }
                override fun callFailed(call: Call, ioe: IOException) { networkFinished.complete(Unit) }
            })
            .build()
        val states = mutableListOf<UiState<UpdateInfo>>()
        val collecting = launch { UpdateChecker(client, mockk()).checkUpdate().toList(states) }
        try {
            val call = withTimeout(5_000) { readingBody.await() }
            withTimeout(5_000) {
                collecting.cancelAndJoin()
                networkFinished.await()
            }
            assertTrue(call.isCanceled())
            assertTrue(collecting.isCancelled)
            assertTrue(states.none { it is UiState.Error })
        } finally {
            releaseServer.countDown()
            collecting.cancelAndJoin()
            server.stop(0)
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
