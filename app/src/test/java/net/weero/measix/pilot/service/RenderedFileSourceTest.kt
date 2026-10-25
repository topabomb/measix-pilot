package net.weero.measix.pilot.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RenderedFileSourceTest {
    @org.junit.Before fun logging() {
        io.mockk.mockkStatic(android.util.Log::class)
        io.mockk.every { android.util.Log.e(any(), any<String>()) } returns 0
    }
    @org.junit.After fun releaseLogging() { io.mockk.unmockkStatic(android.util.Log::class) }
    private fun source(read: suspend (String, Long) -> ByteArray = { _, _ -> byteArrayOf() }) =
        RenderedFileSource("pages/example.html", MutableStateFlow(Unit), {}, { it }, read)

    @Test fun `references decode once and keep the original document directory`() {
        val source = source()
        assertEquals("pages/theme.css", source.resourcePath("./theme.css?v=2#rule"))
        assertEquals("assets/image name.svg", source.resourcePath("../assets/image%20name.svg"))
        assertEquals("assets/root.js", source.resourcePath("/assets/root.js"))
        assertEquals("pages/literal%20name.css", source.resourcePath("literal%2520name.css"))
        assertEquals("/pages/example.html", source.documentUrlPath)
    }

    @Test fun `references cannot escape the file root or introduce another authority`() {
        listOf("../../secret", "//other/path", "file:///private", "https://other/path", "content://private/x",
            "image%2fname", "image%5cname", "image%00name", "../..", "bad\\path").forEach { reference ->
            assertThrows(IllegalArgumentException::class.java) { source().resourcePath(reference) }
        }
    }

    @Test fun `CSS keeps its MIME and bytes while executable resources are rejected`() = runBlocking {
        val source = source { path, _ -> path.toByteArray() }
        mapOf("theme.css" to "text/css").forEach { (name, mime) ->
            val result = source.readResource(name)
            assertEquals(mime, result.first)
            assertEquals("pages/$name", result.second.decodeToString())
        }
        assertEquals("image/svg+xml", renderedResourceMime("drawing.svg", byteArrayOf()))
        try { source.readResource("app.js"); fail() }
        catch (error: RenderedResourceException) { assertTrue(error.cause?.message.orEmpty().contains("rendered_resource_type_denied")) }
    }

    @Test fun `closing a borrowed source prevents late reads and late delivery`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val source = source { _, _ -> calls++; started.complete(Unit); release.await(); byteArrayOf(1) }
        val result = async { source.readResource("app.js") }
        started.await()
        source.close()
        release.complete(Unit)
        try { result.await(); fail() } catch (_: kotlinx.coroutines.CancellationException) { }
        try { source.readResource("app.js"); fail() } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(1, calls)
    }

    @Test fun `owner receives the limit and an oversized result cannot be published`() = runBlocking {
        val source = source { _, limit -> assertEquals(2L, limit); byteArrayOf(1, 2, 3) }
        try { source.readResource("app.js", 2); fail() }
        catch (error: RenderedResourceException) { assertTrue(error.cause?.message.orEmpty().contains("rendered_resource_size_limit")) }
    }

    @Test fun `timeout preserves diagnostic and a fresh preview can recover without closing file authority`() = kotlinx.coroutines.test.runTest {
        var slow = true
        val original = source { _, _ -> if (slow) kotlinx.coroutines.awaitCancellation() else "body{}".toByteArray() }
        val preview = original.newPreview()
        try { preview.readResource("theme.css"); fail() }
        catch (error: RenderedResourceException) { assertTrue(error.cause is java.net.SocketTimeoutException) }
        assertEquals(1, preview.resourceFailures.value.size)
        preview.close()
        original.requireAccess()
        slow = false
        val next = original.newPreview()
        assertEquals("body{}", next.readResource("theme.css").second.decodeToString())
        assertTrue(next.resourceFailures.value.isEmpty())
        next.close()
    }

    @Test fun `missing resource is local to the preview and does not revoke the source`() = runBlocking {
        val original = source { path, _ -> if (path.endsWith("missing.css")) throw java.io.FileNotFoundException(path) else byteArrayOf(1) }
        try { original.readResource("missing.css"); fail() } catch (_: RenderedResourceException) { }
        original.requireAccess()
        assertEquals(1, original.readResource("good.css").second.size)
        assertTrue(original.resourceFailures.value.single().cause is java.io.FileNotFoundException)
    }

    @Test fun `bounded diagnostics keep accepting new failures after the display window fills`() = runBlocking {
        val original = source { path, _ -> throw java.io.FileNotFoundException(path) }
        repeat(17) { index ->
            try { original.readResource("missing$index.css"); fail() } catch (_: RenderedResourceException) { }
        }
        assertEquals(16, original.resourceFailures.value.size)
        assertTrue(original.resourceFailures.value.last().message!!.endsWith("missing16.css"))
        assertFalse(original.resourceFailures.value.any { it.message!!.endsWith("missing0.css") })
    }
}
