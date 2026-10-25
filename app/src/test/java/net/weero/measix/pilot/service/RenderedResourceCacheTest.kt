package net.weero.measix.pilot.service

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RenderedResourceCacheTest {
    @Test fun `resource cache expires bytes and short lived failures without exhausting capacity`() = runTest {
        var now = 0L
        val cache = RenderedResourceCache(now = { now })
        var reads = 0
        suspend fun read() = cache.read("a", {}) { reads++; "text/css" to byteArrayOf(1) }
        read(); read(); assertEquals(1, reads)
        now = 30_000
        read(); assertEquals(2, reads)
        try { cache.read("missing", {}) { throw java.io.IOException("missing") }; fail() } catch (_: java.io.IOException) { }
        now += 5_000
        assertEquals(1, cache.read("missing", {}) { "text/css" to byteArrayOf(1) }.second.size)
        cache.close()
    }

    @Test fun `failed reads do not poison capacity for later valid resources`() = runTest {
        val cache = RenderedResourceCache(maxResources = 2)
        var calls = 0
        repeat(200) { n ->
            try { cache.read("$n", {}) { calls++; throw java.io.IOException("missing") }; fail() }
            catch (_: java.io.IOException) { }
        }
        assertEquals(200, calls)
        assertEquals(1, cache.read("valid", {}) { "text/css" to byteArrayOf(1) }.second.size)
        cache.close()
    }

    @Test fun `gallery traffic can exceed sixty four MiB while retained bytes stay bounded`() = runTest {
        val cache = RenderedResourceCache(maxBytes = 2L * 1024 * 1024)
        val bytes = ByteArray(1024 * 1024)
        var calls = 0
        suspend fun read(key: String) = cache.read(key, {}) { calls++; "image/png" to bytes }
        repeat(80) { read("$it") }
        read("79")
        assertEquals(80, calls)
        read("0")
        assertEquals(81, calls)
        cache.close()
    }

    @Test fun `inline data preserves percent encoded bytes and literal plus within limits`() {
        assertEquals("<svg>A+B</svg>", decodeRenderedInlineResource("data:image/svg+xml,%3Csvg%3EA+B%3C/svg%3E", 100).second.decodeToString())
        assertArrayEquals(byteArrayOf(0, -1), decodeRenderedInlineResource("data:image/png,%00%FF", 2).second)
        assertThrows(IllegalStateException::class.java) { decodeRenderedInlineResource("data:image/png,%00%FF", 1) }
        assertThrows(IllegalArgumentException::class.java) { decodeRenderedInlineResource("data:image/png,%XX", 10) }
    }
    @Test fun `duplicate failures do not repeatedly fetch until a new preview`() = runTest {
        val cache = RenderedResourceCache()
        var calls = 0
        repeat(3) {
            try { cache.read("missing", {}) { calls++; throw java.io.FileNotFoundException("missing") }; fail() }
            catch (_: java.io.FileNotFoundException) { }
        }
        assertEquals(1, calls)
        cache.close()
    }
    @Test fun `public stylesheet redirects use final base and never follow a private address or credential URL`() = runTest {
        val calls = mutableListOf<String>()
        val lookup: (String) -> List<java.net.InetAddress> = { listOf(java.net.InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))) }
        val transport = net.weero.measix.pilot.data.ai.attachments.RemoteHttpTransport { url, _ ->
            calls += url.toString()
            if (calls.size == 1) net.weero.measix.pilot.data.ai.attachments.RemoteHttpResponse(302,
                mapOf("Location" to listOf("https://cdn.example/v2/theme.css")), byteArrayOf())
            else net.weero.measix.pilot.data.ai.attachments.RemoteHttpResponse(200,
                mapOf("Content-Type" to listOf("text/css")), "a{background:url(img.png)}".toByteArray())
        }
        val result = readPublicRenderedResource("https://public.example/theme", 1024, "https://preview.invalid", lookup, transport)
        assertTrue(result.second.decodeToString().contains("https://cdn.example/v2/img.png"))
        assertEquals(2, calls.size)
        for (url in listOf("https://localhost/x.css", "https://user:password@public.example/x.css")) {
            try { readPublicRenderedResource(url, 1024, "https://preview.invalid", lookup, transport); fail() }
            catch (_: IllegalArgumentException) { }
        }
        try {
            readPublicRenderedResource("https://public.example/theme", 1024, "https://preview.invalid",
                { listOf(java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))) }, transport)
            fail()
        } catch (_: IllegalArgumentException) { }
        assertEquals(2, calls.size)
    }

    @Test fun `all viewers share four reads and duplicate resources share bytes`() = runTest {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val calls = AtomicInteger()
        val caches = List(3) { RenderedResourceCache() }
        val jobs = (0 until 24).map { n -> async {
            caches[n % 3].read("${n % 6}", {}) {
                calls.incrementAndGet()
                val count = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, count) }
                try { delay(100); "text/css" to byteArrayOf(1) }
                finally { active.decrementAndGet() }
            }
        } }
        jobs.awaitAll()
        assertEquals(4, peak.get())
        assertEquals(6, calls.get())
        caches.forEach { it.close() }
    }

    @Test fun `closing cancels queued and active readers without occupying the pool`() = runTest {
        val cache = RenderedResourceCache(maxResources = 4)
        val entered = AtomicInteger()
        val exited = AtomicInteger()
        val jobs = (0 until 8).map { n -> async {
            cache.read("$n", {}) {
                entered.incrementAndGet()
                try { awaitCancellation() } finally { exited.incrementAndGet() }
            }
        } }
        testScheduler.runCurrent()
        assertEquals(4, entered.get())
        cache.close()
        jobs.forEach { try { it.await(); fail() } catch (_: CancellationException) { } }
        assertEquals(4, exited.get())
        val fresh = RenderedResourceCache()
        assertEquals(1, fresh.read("next", {}) { "text/css" to byteArrayOf(1) }.second.size)
        fresh.close()
    }

    @Test fun `cache evicts by bytes and entry count without limiting gallery length`() = runTest {
        for (cache in listOf(RenderedResourceCache(maxBytes = 2), RenderedResourceCache(maxResources = 2))) {
            var calls = 0
            suspend fun read(key: String) = cache.read(key, {}) { calls++; "text/css" to byteArrayOf(1) }
            read("a"); read("b"); read("a"); read("c"); read("a")
            assertEquals(3, calls)
            read("b")
            assertEquals(4, calls)
            repeat(300) { read("later-$it") }
            assertEquals(304, calls)
            cache.close()
        }
    }

    @Test fun `more simultaneous gallery requests than admission slots wait and all finish`() = runTest {
        val cache = RenderedResourceCache(maxResources = 2)
        var calls = 0
        (0 until 300).map { n -> async {
            cache.read("$n", {}) { calls++; delay(1); "text/css" to byteArrayOf(1) }
        } }.awaitAll()
        assertEquals(300, calls)
        cache.close()
    }

    @Test fun `cache hits revalidate access and rejected results are never delivered`() = runTest {
        val cache = RenderedResourceCache()
        var allowed = true
        var calls = 0
        suspend fun read() = cache.read("image", { check(allowed) { "revoked" } }) { calls++; "image/png" to byteArrayOf(1) }
        read()
        allowed = false
        try { read(); fail() } catch (error: IllegalStateException) { assertEquals("revoked", error.message) }
        assertEquals(1, calls)
        cache.close()
    }

    @Test fun `CSS imports and relative assets use final stylesheet location without rewriting strings or comments`() {
        val css = """/* url(fake.png) */ @import "../base.css"; a { background:url('../images/a b.png'); content:"url(untouched)"; mask:url(#clip); }"""
        val result = absoluteStylesheetUrls(css, java.net.URI("https://cdn.example/theme/v2/main.css"))
        assertTrue(result.contains("https://cdn.example/theme/base.css"))
        assertTrue(result.contains("https://cdn.example/theme/images/a%20b.png"))
        assertTrue(result.contains("/* url(fake.png) */"))
        assertTrue(result.contains("content:\"url(untouched)\""))
        assertTrue(result.contains("url(\"#clip\")"))
        val imports = absoluteStylesheetUrls("@import\"nested.css\";@import/**/'other.css';", java.net.URI("https://cdn.example/v2/main.css"))
        assertTrue(imports.contains("https://cdn.example/v2/nested.css"))
        assertTrue(imports.contains("https://cdn.example/v2/other.css"))
        assertEquals("url(\"about:invalid\")", absoluteStylesheetUrls("url(".repeat(50_000), java.net.URI("https://cdn.example/a.css")))
        val data = "a".repeat(500_000)
        assertTrue(absoluteStylesheetUrls("a{background:url(\"data:image/png;base64,$data\")}", java.net.URI("https://cdn.example/a.css"),
            "https://preview.invalid").startsWith("a{background:url(\"https://preview.invalid/_preview_inline?"))
    }
}
