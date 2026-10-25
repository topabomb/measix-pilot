package net.weero.measix.pilot.service.files

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.enterprise.WorkspaceText
import org.junit.Assert.*
import org.junit.Test

class FileBrowserReadCacheTest {
    private fun directory(path: String) = BrowserDirectory(path, emptyList(), null, null)
    @Test fun `directory cache expires and still revalidates original authority on hits`() = runTest {
        var now = 0L
        val cache = FileBrowserReadCache { now }
        var allowed = true
        var reads = 0
        suspend fun read() = cache.directory("docs", { check(allowed) { "revoked" } }) { reads++; directory("docs") }
        read(); read(); assertEquals(1, reads)
        now = 15_000
        read(); assertEquals(2, reads)
        allowed = false
        try { read(); fail() } catch (error: IllegalStateException) { assertEquals("revoked", error.message) }
        assertEquals(2, reads)
    }

    @Test fun `write attempts invalidate before and after and stale reads cannot refill snapshots`() = runTest {
        val cache = FileBrowserReadCache()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val slow = async { cache.directory("docs", {}) { started.complete(Unit); finish.await(); directory("docs") } }
        started.await()
        try { cache.writing { throw java.io.IOException("unknown write") }; fail() } catch (_: java.io.IOException) { }
        finish.complete(Unit); slow.await()
        var reads = 0
        repeat(2) { cache.directory("docs", {}) { reads++; directory("docs") } }
        assertEquals(1, reads)
        cache.invalidate()
        cache.directory("docs", {}) { reads++; directory("docs") }
        assertEquals(2, reads)
    }

    @Test fun `text cache retains its ETag and does not cache unversioned documents`() = runTest {
        var now = 0L
        val cache = FileBrowserReadCache { now }
        val original = BrowserFile("a.md", false, 4, null, "\"v1\"")
        var reads = 0
        suspend fun read(file: BrowserFile) = cache.text(file, {}) {
            reads++
            BrowserTextDocument(file, WorkspaceText.decode("text".toByteArray()))
        }
        assertEquals(original, read(original).file)
        read(original); assertEquals(1, reads)
        read(original.copy(etag = "\"v2\"")); assertEquals(2, reads)
        now = 30_000
        read(original); assertEquals(3, reads)
        repeat(2) { read(original.copy(etag = null, versioned = false)) }
        assertEquals(5, reads)
    }

    @Test fun `directory metadata is bounded and old locations can be fetched again`() = runTest {
        val cache = FileBrowserReadCache()
        repeat(40) { cache.directory("$it", {}) { directory("$it") } }
        var reads = 0
        cache.directory("0", {}) { reads++; directory("0") }
        assertEquals(1, reads)
    }
}
