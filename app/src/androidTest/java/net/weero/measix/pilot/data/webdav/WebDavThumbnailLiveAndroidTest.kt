package net.weero.measix.pilot.data.webdav

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.weero.measix.pilot.service.files.BrowserFile
import net.weero.measix.pilot.service.files.FileBrowserThumbnails
import net.weero.measix.pilot.service.files.FileThumbnailKey
import okhttp3.Credentials
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real Runcove DAV with an isolated fixture directory. This test never writes to the server. */
class WebDavThumbnailLiveAndroidTest {
    @Test fun nativeClientReadsTwelveRepresentationsAndReusesTheWarmViewport() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("webdavThumbnailLive") == "true")
        val root = requireNotNull(arguments.getString("webdavLiveRoot"))
        val authorization = Credentials.basic(requireNotNull(arguments.getString("webdavLiveUser")),
            requireNotNull(arguments.getString("webdavLivePassword")))
        val client = WebDavClient()
        val sourceNames = (1..11).map { "pilot-preview-${it.toString().padStart(2, '0')}.png" } + "pilot-preview-video.mp4"
        val files = client.list(root, authorization).files.filter { it.path in sourceNames }.map {
            BrowserFile(it.path, it.directory, it.size, it.modified?.toString(), it.etag)
        }
        assertEquals(sourceNames.toSet(), files.map { it.path }.toSet())
        val keys = files.map { requireNotNull(FileThumbnailKey.from(it)) }.toSet()
        var capabilityCalls = 0
        var thumbnailCalls = 0
        var transferred = 0
        val owner = FileBrowserThumbnails(this, {},
            discover = { capabilityCalls++; client.thumbnailCapability(root, authorization, null) },
            fetch = { space, key ->
                thumbnailCalls++
                client.thumbnail(root, authorization, space, key.path, key.sourceVersion).also { transferred += it.size }
            })
        try {
            val started = android.os.SystemClock.elapsedRealtime()
            owner.visible(files)
            val first = withTimeout(30_000) { owner.images.first { it.isNotEmpty() } }
            val firstMs = android.os.SystemClock.elapsedRealtime() - started
            assertTrue(first.keys.all { it in keys })
            val ready = withTimeout(40_000) { owner.images.first { it.keys == keys } }
            val allMs = android.os.SystemClock.elapsedRealtime() - started
            ready.values.forEach { image ->
                image.requireAccess()
                val bytes = image.readBytes()
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                try { assertTrue(bitmap.width in 1..256 && bitmap.height in 1..256) } finally { bitmap.recycle() }
            }
            owner.visible(emptyList())
            owner.visible(files.reversed())
            assertEquals(keys, owner.images.value.keys)
            val video = client.mediaMetadata(root, authorization, sourceNames.last())
            assertEquals(1024, client.mediaRange(root, authorization, sourceNames.last(), video, 0, 1024).size)
            assertEquals(1, capabilityCalls)
            assertEquals(12, thumbnailCalls)
            File(instrumentation.targetContext.cacheDir, "webdav-thumbnail-live-evidence.json").writeText(
                JSONObject().put("server", arguments.getString("webdavServerVersion"))
                    .put("options", capabilityCalls).put("thumbnailGets", thumbnailCalls)
                    .put("firstMs", firstMs).put("allMs", allMs).put("encodedBytes", transferred)
                    .put("videoRangeBytes", 1024).toString(),
            )
        } finally { owner.close() }
    }
}
