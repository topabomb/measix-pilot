package net.weero.measix.pilot.data.webdav

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebDavConnectionRecoveryTest {
    @Test fun `download recovers a closed pooled connection before writing the exact body`() = runBlocking {
        val closed = CountDownLatch(1)
        val requests = AtomicInteger()
        sockets { socket ->
            socket.readRequest()
            requests.incrementAndGet()
            socket.respond("payload")
            socket.close()
            closed.countDown()
        }.use { server ->
            val client = WebDavClient()
            repeat(2) {
                val output = ByteArrayOutputStream()
                assertEquals(7L, client.download(server.root, "", "file", output))
                assertEquals("payload", output.toString("UTF-8"))
                assertTrue(closed.await(5, TimeUnit.SECONDS))
            }
            assertEquals(2, requests.get())
        }
    }

    @Test fun `body EOF is not replayed after bytes reach the output`() = runBlocking {
        val requests = AtomicInteger()
        sockets { socket ->
            socket.readRequest()
            requests.incrementAndGet()
            socket.respond("par", length = 7)
        }.use { server ->
            val output = ByteArrayOutputStream()
            try {
                WebDavClient().download(server.root, "", "file", output)
                fail("Truncated body must fail")
            } catch (_: IOException) { }
            assertEquals("par", output.toString("UTF-8"))
            assertEquals(1, requests.get())
        }
    }

    @Test fun `cancellation closes the replacement connection while waiting for its response`() = runBlocking {
        val requests = AtomicInteger()
        val firstClosed = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondClosed = CountDownLatch(1)
        sockets { socket ->
            socket.readRequest()
            if (requests.incrementAndGet() == 1) {
                socket.respond("warm")
                socket.close()
                firstClosed.countDown()
            } else {
                secondStarted.countDown()
                assertEquals(-1, socket.getInputStream().read())
                secondClosed.countDown()
            }
        }.use { server ->
            val client = WebDavClient()
            client.download(server.root, "", "file", ByteArrayOutputStream())
            assertTrue(firstClosed.await(5, TimeUnit.SECONDS))
            val job = launch(Dispatchers.IO) { client.download(server.root, "", "file", ByteArrayOutputStream()) }
            try {
                assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
                withTimeout(3000) { job.cancelAndJoin() }
                assertTrue(secondClosed.await(3, TimeUnit.SECONDS))
                assertTrue(job.isCancelled)
                assertEquals(2, requests.get())
            } finally { job.cancelAndJoin() }
        }
    }

    @Test fun `no write method is replayed for immediate service unavailable followup`() = runBlocking {
        for (method in listOf("PUT", "MKCOL", "COPY", "MOVE", "DELETE")) {
            val requests = AtomicInteger()
            val opens = AtomicInteger()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange -> exchange.use {
                    requests.incrementAndGet()
                    assertEquals(method, it.requestMethod)
                    it.requestBody.readBytes()
                    it.responseHeaders.set("Retry-After", "0")
                    it.sendResponseHeaders(503, -1)
                } }
                start()
            }
            try {
                val root = "http://127.0.0.1:${server.address.port}/"
                val client = WebDavClient()
                try {
                    when (method) {
                        "PUT" -> client.upload(root, "", "file", 1, { opens.incrementAndGet(); byteArrayOf(1).inputStream() })
                        "MKCOL" -> client.mkdir(root, "", "folder")
                        else -> client.mutate(root, "", "file", method, if (method == "DELETE") null else "target")
                    }
                    fail("503 must remain an explicit failure")
                } catch (failure: DavHttpException) { assertEquals(503, failure.status) }
                assertEquals(method, 1, requests.get())
                assertEquals(if (method == "PUT") 1 else 0, opens.get())
            } finally { server.stop(0) }
        }
    }

    private class SocketServer(handler: (Socket) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0, 5, java.net.InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        private val worker = executor.submit {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (failure: IOException) {
                    if (server.isClosed) break else throw failure
                }
                socket.use { it.soTimeout = 5000; handler(it) }
            }
        }
        val root = "http://127.0.0.1:${server.localPort}/"
        override fun close() {
            server.close()
            try { worker.get(7, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
        }
    }

    private fun sockets(handler: (Socket) -> Unit) = SocketServer(handler)
    private fun Socket.readRequest() {
        val reader = getInputStream().bufferedReader()
        while (!reader.readLine().isNullOrEmpty()) { }
    }
    private fun Socket.respond(body: String, length: Int = body.length) {
        getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Length: $length\r\n\r\n$body".toByteArray())
            flush()
        }
    }
}
