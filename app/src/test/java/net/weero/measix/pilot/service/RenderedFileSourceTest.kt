package net.weero.measix.pilot.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RenderedFileSourceTest {
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

    @Test fun `CSS JS and SVG keep their MIME and raw bytes`() = runBlocking {
        val source = source { path, _ -> path.toByteArray() }
        mapOf("theme.css" to "text/css", "app.js" to "text/javascript", "drawing.svg" to "image/svg+xml").forEach { (name, mime) ->
            val result = source.readResource(name)
            assertEquals(mime, result.first)
            assertEquals("pages/$name", result.second.decodeToString())
        }
    }

    @Test fun `closing a borrowed source prevents late reads and late delivery`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val source = source { _, _ -> calls++; started.complete(Unit); release.await(); byteArrayOf(1) }
        val result = async { try { source.readResource("app.js"); null } catch (error: IllegalStateException) { error } }
        started.await()
        source.close()
        release.complete(Unit)
        assertEquals("rendered_file_closed", result.await()?.message)
        try { source.readResource("app.js"); fail() } catch (error: IllegalStateException) { assertEquals("rendered_file_closed", error.message) }
        assertEquals(1, calls)
    }

    @Test fun `owner receives the limit and an oversized result cannot be published`() = runBlocking {
        val source = source { _, limit -> assertEquals(2L, limit); byteArrayOf(1, 2, 3) }
        try { source.readResource("app.js", 2); fail() }
        catch (error: IllegalStateException) { assertEquals("rendered_resource_size_limit", error.message) }
    }
}
