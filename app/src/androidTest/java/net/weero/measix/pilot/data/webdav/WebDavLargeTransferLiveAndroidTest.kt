package net.weero.measix.pilot.data.webdav

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Credentials
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in against a dedicated standard DAV server; only a fresh test-owned directory is changed. */
@RunWith(AndroidJUnit4::class)
class WebDavLargeTransferLiveAndroidTest {
    @Test fun realDavTransfersBothFourGiBBoundariesWithBoundedStreams(): Unit = runBlocking(Dispatchers.IO) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("webdavLargeLive") == "true")
        val root = requireNotNull(arguments.getString("webdavLiveRoot"))
        val authorization = Credentials.basic(requireNotNull(arguments.getString("webdavLiveUser")),
            requireNotNull(arguments.getString("webdavLivePassword")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val base = File(context.cacheDir, "webdav-large-${UUID.randomUUID()}").apply { check(mkdir()) }
        val dav = WebDavClient()
        val folder = "android-large-${UUID.randomUUID()}"
        val evidence = JSONObject().put("server", arguments.getString("webdavServerVersion"))
        val cases = JSONArray()
        evidence.put("cases", cases)
        var created = false
        var failure: Throwable? = null
        try {
            // Two complete round trips move 16 GiB; emulator networking can sustain only a few MiB/s.
            withTimeout(90 * 60_000L) {
                dav.mkdir(root, authorization, folder)
                created = true
                val buffer = ByteArray(64 * 1024) { (it * 31 + it / 251).toByte() }
                val baseline = usedHeap()
                var peakHeap = baseline
                for ((index, size) in listOf(4L * 1024 * 1024 * 1024, 4L * 1024 * 1024 * 1024 + 1024 * 1024).withIndex()) {
                    val started = android.os.SystemClock.elapsedRealtime()
                    val source = File(base, "source.bin")
                    val digest = MessageDigest.getInstance("SHA-256")
                    source.outputStream().use { file ->
                        DigestOutputStream(file, digest).use { output ->
                            var remaining = size
                            while (remaining > 0) {
                                val count = minOf(buffer.size.toLong(), remaining).toInt()
                                output.write(buffer, 0, count)
                                remaining -= count
                            }
                        }
                    }
                    val expected = digest.digest()
                    val path = "$folder/boundary-$index.bin"
                    var transferred = 0L
                    var logged = 0L
                    fun progress(stage: String, bytes: Long) {
                        peakHeap = maxOf(peakHeap, usedHeap())
                        transferred = bytes
                        if (bytes - logged >= 512L * 1024 * 1024) {
                            Log.i(TAG, "$stage $size: $bytes bytes")
                            logged = bytes
                        }
                    }
                    source.inputStream().use { input ->
                        assertEquals(size, source.length())
                        dav.upload(root, authorization, path, if (index == 0) size else null,
                            { input }, progress = { bytes, _ -> progress("upload", bytes) })
                    }
                    assertEquals(size, transferred)
                    assertTrue(source.delete())
                    val remote = requireNotNull(dav.stat(root, authorization, path))
                    assertEquals(size, remote.size)
                    val metadata = dav.mediaMetadata(root, authorization, path)
                    assertEquals(size, metadata.length)
                    val position = size - 65517
                    val tail = dav.mediaRange(root, authorization, path, metadata, position, 65517)
                    assertArrayEquals(ByteArray(tail.size) { buffer[((position + it) % buffer.size).toInt()] }, tail)
                    val received = MessageDigest.getInstance("SHA-256")
                    transferred = 0
                    logged = 0
                    val downloaded = File(base, "download.bin")
                    downloaded.outputStream().use { output ->
                        val bytes = dav.download(root, authorization, path, DigestOutputStream(output, received),
                            remote.etag, progress = { count, _ -> progress("download", count) })
                        assertEquals(size, bytes)
                    }
                    assertEquals(size, downloaded.length())
                    assertEquals(size, transferred)
                    assertArrayEquals(expected, received.digest())
                    assertTrue(downloaded.delete())
                    dav.mutate(root, authorization, path, "DELETE", sourceEtag = remote.etag)
                    cases.put(JSONObject().put("bytes", size).put("unknownUploadLength", index != 0)
                        .put("sha256", expected.joinToString("") { "%02x".format(it) })
                        .put("elapsedMillis", android.os.SystemClock.elapsedRealtime() - started)
                        .put("tailRangeVerified", true).put("downloadHashMatched", true))
                    evidence.put("heapGrowthBytes", peakHeap - baseline)
                    File(context.cacheDir, "webdav-large-live-evidence.json").writeText(evidence.toString(2))
                }
                assertTrue("Transfer heap growth must stay bounded", peakHeap - baseline < 128L * 1024 * 1024)
            }
        } catch (error: Throwable) { failure = error; evidence.put("failure", error.stackTraceToString()); throw error }
        finally {
            withContext(NonCancellable) {
                try {
                    if (created) withTimeout(30_000) { dav.mutate(root, authorization, folder, "DELETE", directory = true) }
                    evidence.put("ownedRemoteFilesRemoved", true)
                } catch (cleanup: Throwable) { failure?.addSuppressed(cleanup) ?: run { failure = cleanup } }
                try {
                    check(base.deleteRecursively()) { "Failed to remove owned large transfer files" }
                    evidence.put("ownedLocalFilesRemoved", true)
                } catch (cleanup: Throwable) { failure?.addSuppressed(cleanup) ?: run { failure = cleanup } }
                failure?.let { evidence.put("failure", it.stackTraceToString()) }
                File(context.cacheDir, "webdav-large-live-evidence.json").writeText(evidence.toString(2))
            }
        }
        failure?.let { throw it }
    }

    private fun usedHeap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
    private companion object { const val TAG = "WebDavLargeLive" }
}
