package net.weero.measix.pilot.service.files

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import net.weero.measix.pilot.data.files.MediaThumbnailReadException
import net.weero.measix.pilot.data.files.MediaThumbnailUnavailableException
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserThumbnailsTest {
    private fun file(name: String, etag: String = "\"first\"") = BrowserFile(name, false, null, null, etag)
    private fun key(file: BrowserFile) = requireNotNull(FileThumbnailKey.from(file))

    @Test fun `twelve visible versions share discovery and returning or switching view adds no traffic`() = runTest {
        var discovery = 0
        val reads = mutableListOf<FileThumbnailKey>()
        val files = (1..12).map { file("photo$it.png") }
        val owner = FileBrowserThumbnails(backgroundScope, {},
            discover = { discovery++; "space" },
            fetch = { _, key -> reads += key; byteArrayOf(1) })
        owner.visible(files + file("weak.png", "W/\"weak\"") + file("sound.mp3"))
        runCurrent()
        assertEquals(files.map(::key), reads)
        assertEquals(1, discovery)
        owner.visible(emptyList())
        owner.visible(files.reversed())
        runCurrent()
        assertEquals(12, reads.size)
        assertEquals(12, owner.images.value.size)
        assertEquals(1, discovery)
        owner.close()
        assertTrue(owner.images.value.isEmpty())
    }

    @Test fun `offscreen socket cleanup keeps its slot until it actually exits`() = runTest {
        val fileA = file("a.png")
        val fileB = file("b.png")
        val reads = mutableListOf<FileThumbnailKey>()
        val allowCleanup = CompletableDeferred<Unit>()
        val owner = FileBrowserThumbnails(backgroundScope, {}, { "space" }, { _, requested ->
            reads += requested
            if (requested == key(fileA)) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { allowCleanup.await() } }
            }
            byteArrayOf(1)
        })
        owner.visible(listOf(fileA))
        runCurrent()
        owner.visible(listOf(fileA, fileB))
        runCurrent()
        assertEquals(listOf(key(fileA)), reads)
        owner.visible(listOf(fileB))
        runCurrent()
        assertEquals(listOf(key(fileA)), reads)
        allowCleanup.complete(Unit)
        runCurrent()
        assertEquals(listOf(key(fileA), key(fileB)), reads)
        assertEquals(setOf(key(fileB)), owner.images.value.keys)
        owner.close()
    }

    @Test fun `connection cooldown governs new visible entries and busy is retried at most once`() = runTest {
        val files = listOf(file("busy.jpg"), file("later.mp4"))
        val reads = mutableListOf<Pair<FileThumbnailKey, Long>>()
        val owner = FileBrowserThumbnails(backgroundScope, {}, { "space" }, { _, requested ->
            reads += requested to testScheduler.currentTime
            if (requested == key(files.first())) throw MediaThumbnailReadException(429, 1_000, IOException("preview_busy"))
            byteArrayOf(1)
        }, now = { testScheduler.currentTime })
        owner.visible(files)
        runCurrent()
        assertEquals(listOf(key(files[0]) to 0L), reads)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(key(files[0]) to 0L, key(files[0]) to 1_000L), reads)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(key(files[1]) to 2_000L, reads.last())
        owner.visible(files.reversed())
        runCurrent()
        assertEquals(3, reads.size)
        owner.close()
    }

    @Test fun `cache enforces byte and entry limits and revokes prior sources on listing or binding change`() = runTest {
        val revisions = MutableStateFlow(1)
        var discovery = 0
        var bytes = 1024 * 1024
        val owner = FileBrowserThumbnails(backgroundScope, {}, { discovery++; "space" },
            { _, _ -> ByteArray(bytes) }, bindingChanges = revisions,
            currentBinding = { FileBrowserThumbnails.Binding("space", revisions.value.toLong(), null) })
        val files = (1..9).map { file("dir/$it.png") }
        owner.visible(files)
        runCurrent()
        assertEquals(8, owner.images.value.size)
        assertFalse(key(files.first()) in owner.images.value)
        val previous = owner.images.value.getValue(key(files.last()))
        val changed = file(files.last().path, "\"second\"")
        owner.directory(BrowserDirectory("dir", listOf(changed), null, null))
        owner.visible(listOf(changed))
        runCurrent()
        assertEquals(setOf(key(changed)), owner.images.value.keys)
        assertTrue(runCatching { previous.requireAccess() }.isFailure)
        owner.visible(emptyList())
        revisions.value = 2
        runCurrent()
        assertTrue(owner.images.value.isEmpty())
        bytes = 1
        owner.visible((1..134).map { file("again/$it.png") })
        runCurrent()
        assertEquals(128, owner.images.value.size)
        assertEquals(2, discovery)
        owner.close()
    }

    @Test fun `confirmed absence is reused even on directory refresh`() = runTest {
        var discovery = 0
        val owner = FileBrowserThumbnails(backgroundScope, {}, { discovery++; null }, { _, _ -> error("no image request") })
        owner.visible(listOf(file("a.png")))
        runCurrent()
        owner.visible(emptyList())
        owner.retryFailures()
        owner.visible(listOf(file("folder/b.png")))
        runCurrent()
        assertEquals(1, discovery)
        assertNull(owner.failure.value)
        owner.close()
    }

    @Test fun `visible image survives eviction even when offscreen images were accessed more recently`() = runTest {
        val cover = file("cover.png")
        val offscreen = (1..7).map { file("other$it.png") }
        val next = file("next.png")
        val acceptNext = CompletableDeferred<Unit>()
        val owner = FileBrowserThumbnails(backgroundScope, {}, { "space" }, { _, requested ->
            if (requested == key(next)) acceptNext.await()
            ByteArray(1024 * 1024)
        })
        owner.visible(listOf(cover) + offscreen)
        runCurrent()
        owner.visible(listOf(cover, next))
        runCurrent()
        offscreen.forEach { owner.images.value.getValue(key(it)).requireAccess() }
        acceptNext.complete(Unit)
        runCurrent()
        assertTrue(key(cover) in owner.images.value)
        assertTrue(key(next) in owner.images.value)
        assertFalse(key(offscreen.first()) in owner.images.value)
        assertEquals(8, owner.images.value.size)
        owner.close()
    }

    @Test fun `failure history overflow does not refetch the same visible items`() = runTest {
        val files = (1..134).map { file("missing$it.png") }
        val reads = mutableListOf<FileThumbnailKey>()
        val owner = FileBrowserThumbnails(backgroundScope, {}, { "space" }, { _, requested ->
            reads += requested
            if (reads.size > 2 * files.size) awaitCancellation()
            throw MediaThumbnailReadException(404, 0, IOException("preview_missing"))
        })
        owner.visible(files)
        runCurrent()
        assertEquals(files.map(::key), reads)
        owner.visible(emptyList())
        owner.visible(files.takeLast(1))
        runCurrent()
        assertEquals(files.size, reads.size)
        owner.retryFailures()
        runCurrent()
        assertEquals(files.size + 1, reads.size)
        assertEquals(key(files.last()), reads.last())
        owner.close()
    }

    @Test fun `temporary transmission failure waits for the next visit to retry`() = runTest {
        val file = file("photo.png")
        var reads = 0
        val owner = FileBrowserThumbnails(backgroundScope, {}, { "space" }, { _, _ ->
            if (++reads == 1) throw IOException("fixture network unavailable")
            byteArrayOf(1)
        })
        owner.visible(listOf(file))
        runCurrent()
        assertEquals(1, reads)
        assertTrue(owner.images.value.isEmpty())
        owner.visible(emptyList())
        owner.visible(listOf(file))
        runCurrent()
        assertEquals(2, reads)
        assertEquals(setOf(key(file)), owner.images.value.keys)
        owner.close()
    }

    @Test fun `busy discovery recovers once and a confirmed GET downgrade retires the entire session capability`() = runTest {
        val files = listOf(file("a.png"), file("b.png"), file("c.png"))
        var discoveries = 0
        val reads = mutableListOf<FileThumbnailKey>()
        val owner = FileBrowserThumbnails(backgroundScope, {}, {
            if (++discoveries == 1) throw MediaThumbnailReadException(429, 1_000, IOException("preview_busy"))
            "space"
        }, { _, requested ->
            reads += requested
            if (requested == key(files[1])) throw MediaThumbnailUnavailableException(IOException("preview_not_supported"))
            byteArrayOf(1)
        }, now = { testScheduler.currentTime })
        owner.visible(listOf(files.first()))
        runCurrent()
        assertEquals(1, discoveries)
        assertTrue(reads.isEmpty())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(setOf(key(files.first())), owner.images.value.keys)
        owner.visible(files)
        runCurrent()
        assertEquals(listOf(key(files[0]), key(files[1])), reads)
        assertTrue(owner.images.value.isEmpty())
        assertNull(owner.failure.value)
        owner.close()
    }
}
