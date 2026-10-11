package net.weero.measix.pilot.data.webdav

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
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
class WebDavClientTest {
    private val client = WebDavClient()
    private fun server(handler: HttpExchange.() -> Unit) = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> exchange.use { it.handler() } }
        start()
    }
    private fun HttpServer.root() = "http://127.0.0.1:${address.port}/dav/"
    private fun HttpExchange.reply(status: Int, text: String) {
        val bytes = text.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.write(bytes)
    }
    private fun response(href: String, directory: Boolean = false, size: Long = 7): String =
        """<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype>${if (directory) "<d:collection/>" else ""}</d:resourcetype><d:getcontentlength>$size</d:getcontentlength><d:getetag>"v1"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""

    @Test fun `logical names encode once and hrefs remain within the exact root`() {
        val paths = WebDavPaths("https://dav.example/root/")
        val name = "Chinese space %25.txt"
        assertEquals(name, paths.pathFromHref(paths.url(name).toString()))
        assertTrue(paths.url("100%.txt").toString().endsWith("100%25.txt"))
        for (path in listOf("../secret", "/absolute", "a/../b", "a//b", "a\\b")) {
            assertThrows(IllegalArgumentException::class.java) { paths.url(path) }
        }
        for (href in listOf("https://other.example/root/file", "https://dav.example/root2/file",
            "https://dav.example/root/%2e%2e/file", "https://dav.example/root/a%2fb", "https://dav.example/root/?x=y")) {
            assertThrows(IllegalArgumentException::class.java) { paths.pathFromHref(href) }
        }
    }

    @Test fun `directory parsing identifies self by href and supports more than two thousand entries`() = runBlocking {
        val xml = "<d:multistatus xmlns:d=\"DAV:\">" +
            (0..2001).joinToString("") { response("/dav/file$it.txt", size = 4_294_967_297L) } +
            response("/dav/", directory = true) + "</d:multistatus>"
        val server = server {
            assertEquals("PROPFIND", requestMethod)
            assertEquals("Basic test", requestHeaders.getFirst("Authorization"))
            assertEquals("1", requestHeaders.getFirst("Depth"))
            responseHeaders.set("Content-Type", "application/xml")
            reply(207, xml)
        }
        try {
            val directory = client.list(server.root(), "Basic test")
            assertEquals(2002, directory.files.size)
            assertTrue(directory.self!!.directory)
            assertEquals(4_294_967_297L, directory.files.first().size)
        } finally { server.stop(0) }
    }

    @Test fun `listing refuses partial permissions and out of root href instead of silently omitting files`() = runBlocking {
        for (member in listOf(
            "<d:response><d:href>/dav/denied</d:href><d:status>HTTP/1.1 403 Forbidden</d:status></d:response>",
            response("/outside/file.txt"),
            response("/dav/./file.txt"),
            response("/dav/%2e/file.txt"),
            response("/dav/file.txt").replace("<d:status>HTTP/1.1 200 OK</d:status>", ""),
            response("/dav/file.txt").replace("HTTP/1.1 200 OK", "not a status"),
            "<d:response><d:propstat><d:prop/><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>",
        )) {
            val server = server { reply(207, "<d:multistatus xmlns:d=\"DAV:\">${response("/dav/", true)}$member</d:multistatus>") }
            try {
                try { client.list(server.root(), "Basic test"); fail("Must reject incomplete listing") }
                catch (_: IOException) { }
                catch (_: IllegalArgumentException) { }
            } finally { server.stop(0) }
        }
    }

    @Test fun `unquoted DAV property tag becomes a conditional HTTP tag without bypassing version checks`() = runBlocking {
        var returnedTag = "\"v1\""
        val server = server {
            if (requestMethod == "PROPFIND") {
                reply(207, "<d:multistatus xmlns:d=\"DAV:\">" +
                    response("/dav/file").replace("<d:getetag>\"v1\"</d:getetag>", "<d:getetag>v1</d:getetag>") + "</d:multistatus>")
            } else {
                assertEquals("\"v1\"", requestHeaders.getFirst("If-Match"))
                responseHeaders.set("ETag", returnedTag)
                reply(200, "payload")
            }
        }
        try {
            val tag = requireNotNull(client.stat(server.root(), "Basic test", "file")).etag
            assertEquals("\"v1\"", tag)
            val output = ByteArrayOutputStream()
            assertEquals(7L, client.download(server.root(), "Basic test", "file", output, tag))
            assertEquals("payload", output.toString())
            returnedTag = "\"v2\""
            output.reset()
            try { client.download(server.root(), "Basic test", "file", output, tag); fail("Different HTTP tag must still be refused") }
            catch (expected: IOException) { assertEquals("webdav_file_changed", expected.message) }
            assertEquals(0, output.size())
        } finally { server.stop(0) }
    }

    @Test fun `weak or malformed property tags cannot become strong overwrite versions`() = runBlocking {
        for ((raw, expected) in listOf("W/v1" to "W/\"v1\"", "W/\"v1\"" to "W/\"v1\"", "\"v1" to "\"v1", "two tags" to "two tags", "" to "",
            "中文" to "中文", "\"中文\"" to "\"中文\"", "😀" to "😀", "\u0080" to "\u0080", "\"\u0080\"" to "\"\u0080\"")) {
            val server = server { reply(207, "<d:multistatus xmlns:d=\"DAV:\">" +
                response("/dav/file").replace("<d:getetag>\"v1\"</d:getetag>", "<d:getetag>$raw</d:getetag>") + "</d:multistatus>") }
            try {
                val tag = requireNotNull(client.stat(server.root(), "Basic test", "file")).etag
                if (raw.isNotEmpty()) assertEquals(expected, tag)
                assertFalse(WebDavClient.strongEtag(tag))
            } finally { server.stop(0) }
        }
    }

    @Test fun `optional unavailable property does not invalidate successful resource properties`() = runBlocking {
        val item = response("/dav/", true).replace("</d:response>",
            "<d:propstat><d:prop><d:quota-used-bytes/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>")
        val server = server { reply(207, "<d:multistatus xmlns:d=\"DAV:\">$item</d:multistatus>") }
        try {
            assertTrue(client.list(server.root(), "Basic test").self!!.directory)
        } finally { server.stop(0) }
    }

    @Test fun `cancellation closes blocked local upload input and download output`() = runBlocking {
        for (upload in listOf(true, false)) {
            val entered = CountDownLatch(1)
            val closed = CountDownLatch(1)
            val server = server {
                if (upload) { requestBody.readBytes(); reply(201, "done") }
                else reply(200, "payload")
            }
            fun blockUntilClosed() {
                entered.countDown()
                if (!closed.await(10, TimeUnit.SECONDS)) throw IOException("Local stream was not closed")
                throw IOException("closed")
            }
            val input = object : InputStream() {
                override fun read(): Int { blockUntilClosed(); return -1 }
                override fun close() { closed.countDown() }
            }
            val output = object : OutputStream() {
                override fun write(value: Int) { blockUntilClosed() }
                override fun close() { closed.countDown() }
            }
            try {
                val job = launch(Dispatchers.IO) {
                    if (upload) client.upload(server.root(), "Basic test", "file", 1, { input })
                    else client.download(server.root(), "Basic test", "file", output)
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                withTimeout(3000) { job.cancelAndJoin() }
                assertEquals(0L, closed.count)
                assertTrue(job.isCancelled)
            } finally { closed.countDown(); server.stop(0) }
        }
    }

    @Test fun `cancellation while acquiring upload source closes it before reading`() = runBlocking {
        val acquiring = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        val reads = AtomicInteger()
        val server = server { requestBody.readBytes(); reply(201, "done") }
        try {
            val job = launch(Dispatchers.IO) {
                client.upload(server.root(), "Basic test", "file", 1, {
                    acquiring.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    object : InputStream() {
                        override fun read(): Int { reads.incrementAndGet(); return -1 }
                        override fun close() { closes.incrementAndGet() }
                    }
                })
            }
            assertTrue(acquiring.await(5, TimeUnit.SECONDS))
            job.cancel()
            release.countDown()
            withTimeout(3000) { job.join() }
            assertEquals(0, reads.get())
            assertTrue(closes.get() >= 1)
        } finally { release.countDown(); server.stop(0) }
    }

    @Test fun `upload is conditional one shot and redirect never sends credentials to another URL`() = runBlocking {
        val requests = AtomicInteger()
        val opens = AtomicInteger()
        val server = server {
            requests.incrementAndGet()
            assertEquals("PUT", requestMethod)
            assertEquals("*", requestHeaders.getFirst("If-None-Match"))
            assertEquals("payload", requestBody.bufferedReader().readText())
            responseHeaders.set("Location", "/outside/")
            reply(307, "redirect")
        }
        try {
            try {
                client.upload(server.root(), "Basic test", "file", 7, { opens.incrementAndGet(); "payload".byteInputStream() })
                fail("Redirect must require an explicit new target")
            } catch (error: DavHttpException) { assertEquals(307, error.status) }
            assertEquals(1, requests.get())
            assertEquals(1, opens.get())
        } finally { server.stop(0) }
    }

    @Test fun `copy supplies destination precondition and reports nested multi status failure`() = runBlocking {
        val server = server {
            assertEquals("COPY", requestMethod)
            assertEquals("\"source\"", requestHeaders.getFirst("If-Match"))
            assertEquals("T", requestHeaders.getFirst("Overwrite"))
            assertTrue(requestHeaders.getFirst("If").endsWith("([\"target\"])") )
            reply(207, """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/target/locked</d:href><d:status>HTTP/1.1 423 Locked</d:status></d:response></d:multistatus>""")
        }
        try {
            try {
                client.mutate(server.root(), "Basic test", "source", "COPY", "target", "\"source\"", "\"target\"")
                fail("207 with failure is not complete success")
            } catch (error: DavMultiStatusException) { assertEquals(listOf("target/locked" to 423), error.failures) }
        } finally { server.stop(0) }
    }

    @Test fun `download cancellation closes a blocked socket and finishes the operation`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = server {
            sendResponseHeaders(200, 100)
            responseBody.write(1)
            responseBody.flush()
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        try {
            val job = launch(Dispatchers.IO) { client.download(server.root(), "Basic test", "file", ByteArrayOutputStream()) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            withTimeout(3000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally { release.countDown(); server.stop(0) }
    }

    @Test fun `media seek beyond four GiB pins version length and exact partial range`() = runBlocking {
        val length = 4_294_967_296L + 1_048_576L
        val position = length - 8
        val server = server {
            responseHeaders.set("ETag", "\"media-v1\"")
            if (requestMethod == "HEAD") {
                responseHeaders.set("Content-Length", length.toString())
                sendResponseHeaders(200, -1)
            } else {
                assertEquals("bytes=$position-${length - 1}", requestHeaders.getFirst("Range"))
                assertEquals("\"media-v1\"", requestHeaders.getFirst("If-Match"))
                responseHeaders.set("Content-Range", "bytes $position-${length - 1}/$length")
                reply(206, "taildata")
            }
        }
        try {
            val metadata = client.mediaMetadata(server.root(), "Basic test", "video.mp4")
            assertEquals(length, metadata.length)
            assertEquals("taildata", client.mediaRange(server.root(), "Basic test", "video.mp4", metadata, position, 20).decodeToString())
        } finally { server.stop(0) }
    }

    @Test fun `encoded full and ranged representations are refused rather than saved or played`() = runBlocking {
        for (method in listOf("HEAD", "RANGE", "GET")) {
            val server = server {
                responseHeaders.set("Content-Encoding", "gzip")
                responseHeaders.set("ETag", "\"v1\"")
                if (requestMethod == "HEAD") {
                    responseHeaders.set("Content-Length", "4")
                    sendResponseHeaders(200, -1)
                } else {
                    responseHeaders.set("Content-Range", "bytes 0-3/4")
                    reply(if (method == "RANGE") 206 else 200, "data")
                }
            }
            try {
                try {
                    when (method) {
                        "HEAD" -> client.mediaMetadata(server.root(), "Basic test", "video")
                        "RANGE" -> client.mediaRange(server.root(), "Basic test", "video", DavMediaMetadata(4, "\"v1\""), 0, 4)
                        else -> client.download(server.root(), "Basic test", "video", ByteArrayOutputStream())
                    }
                    fail("Encoded response must be rejected")
                } catch (failure: IllegalArgumentException) { assertEquals("webdav_unexpected_content_encoding", failure.message) }
            } finally { server.stop(0) }
        }
    }

    @Test fun `stat queries the exact file and only explicit not found means missing`() = runBlocking {
        val server = server {
            assertEquals("0", requestHeaders.getFirst("Depth"))
            if (requestURI.path == "/dav/file.txt") reply(207, "<d:multistatus xmlns:d=\"DAV:\">${response("/dav/file.txt")}</d:multistatus>")
            else reply(404, "missing")
        }
        try {
            assertEquals("file.txt", client.stat(server.root(), "Basic test", "file.txt")!!.path)
            assertNull(client.stat(server.root(), "Basic test", "missing.txt"))
        } finally { server.stop(0) }
    }
}
