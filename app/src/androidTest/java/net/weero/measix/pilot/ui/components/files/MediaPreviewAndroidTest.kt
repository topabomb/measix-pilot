package net.weero.measix.pilot.ui.components.files

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import net.weero.measix.pilot.RouteActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeRight
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import net.weero.measix.pilot.service.files.MediaPreviewSource
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import org.hamcrest.Matchers.anyOf
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Fixture generated locally with ffmpeg lavfi testsrc2 (320x180, 12 fps, 12 s), H.264 baseline,
 * 120 kbit/s, GOP 12, yuv420p, faststart, no audio. No downloaded media or external service. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class MediaPreviewAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<RouteActivity>()
    private var visible by mutableStateOf(true)
    private val authorized = MutableStateFlow(true)
    private val reads = AtomicInteger()
    private val closed = java.util.concurrent.atomic.AtomicBoolean()

    private var immersiveConfirmation: String? = null
    private var immersiveConfirmationCaptured = false

    @Before fun suppressOnlyTheSystemImmersiveTutorial() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val resolver = instrumentation.targetContext.contentResolver
        automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            immersiveConfirmation = android.provider.Settings.Secure.getString(resolver, "immersive_mode_confirmations")
            immersiveConfirmationCaptured = true
            check(android.provider.Settings.Secure.putString(resolver, "immersive_mode_confirmations", "confirmed"))
        } finally { automation.dropShellPermissionIdentity() }
    }

    @After fun restoreSystemImmersiveTutorialSetting() {
        if (!immersiveConfirmationCaptured) return
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val resolver = instrumentation.targetContext.contentResolver
        automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            check(android.provider.Settings.Secure.putString(resolver, "immersive_mode_confirmations", immersiveConfirmation))
            assertEquals(immersiveConfirmation,
                android.provider.Settings.Secure.getString(resolver, "immersive_mode_confirmations"))
        } finally { automation.dropShellPermissionIdentity() }
    }

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun open(speech: net.weero.measix.pilot.service.SpeechPlayback? = null): PlayerView {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("media/preview-test.mp4").use { it.readBytes() }
        val source = MediaPreviewSource("preview-test.mp4", bytes.size.toLong(), authorized,
            verifyAccess = { check(authorized.value) { "media_test_access_revoked" } },
            close = { closed.set(true) },
            readRange = { offset, count ->
                check(authorized.value)
                reads.incrementAndGet()
                bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
            })
        return openSource(source, speech)
    }

    private fun openSource(source: MediaPreviewSource, speech: net.weero.measix.pilot.service.SpeechPlayback? = null): PlayerView {
        compose.runOnUiThread { compose.activity.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo()) {
                    Box(Modifier.fillMaxSize()) {
                        if (visible) {
                            DisposableEffect(source) { onDispose { source.close() } }
                            MediaPreview(source, onClose = { visible = false }, speechPlayback = speech)
                        }
                    }
                }
            }
        }
        }
        var view: PlayerView? = null
        compose.waitUntil(10_000) {
            compose.runOnIdle {
                view = findPlayer(compose.activity.window.decorView)
                view?.player?.playbackState == Player.STATE_READY
            }
        }
        return requireNotNull(view)
    }

    @Test fun videoAndSpeechPauseEachOtherWithoutReactingToSpeechPositionUpdates() {
        val speechState = MutableStateFlow(me.rerere.tts.model.PlaybackState(status = me.rerere.tts.model.PlaybackStatus.Paused))
        val speech = io.mockk.mockk<net.weero.measix.pilot.service.SpeechPlayback>(relaxed = true)
        val pauses = AtomicInteger()
        io.mockk.every { speech.playbackState } returns speechState
        io.mockk.every { speech.pause() } answers { pauses.incrementAndGet(); Unit }
        val view = open(speech)
        val player = compose.runOnIdle { requireNotNull(view.player) }
        compose.runOnIdle { player.play() }
        compose.waitUntil(10_000) { compose.runOnIdle { player.currentPosition > 200 && pauses.get() == 1 } }
        speechState.value = speechState.value.copy(status = me.rerere.tts.model.PlaybackStatus.Playing)
        compose.waitUntil(5_000) { compose.runOnIdle { !player.playWhenReady } }
        compose.runOnIdle { player.play() }
        compose.waitUntil(5_000) { pauses.get() == 2 }
        val position = compose.runOnIdle { player.currentPosition }
        // The pause command is asynchronous in the application owner. Position-only projections
        // from the still-playing speech must not repeatedly pause the newly requested video.
        repeat(5) { speechState.value = speechState.value.copy(positionMs = (it + 1) * 100L) }
        compose.waitUntil(10_000) { compose.runOnIdle { player.currentPosition > position + 250 } }
        compose.runOnIdle { assertTrue(player.playWhenReady) }
        assertEquals(2, pauses.get())
        io.mockk.verify(exactly = 2) { speech.pause() }
        io.mockk.verify(exactly = 0) { speech.stop() }
    }

    @Test fun decodedVideoSupportsPlaybackScrubbingFullscreenAndClose() {
        val view = open()
        val player = compose.runOnIdle { requireNotNull(view.player) }
        val renderedPresentationUs = java.util.concurrent.atomic.AtomicLong(-1)
        compose.runOnIdle {
            (player as androidx.media3.exoplayer.ExoPlayer).setVideoFrameMetadataListener { presentationUs, _, _, _ ->
                renderedPresentationUs.set(presentationUs)
            }
            assertEquals(12_000L, player.duration)
            assertFalse(player.playWhenReady)
            view.showController()
        }
        onView(withId(androidx.media3.ui.R.id.exo_play_pause)).perform(click())
        compose.waitUntil(10_000) { compose.runOnIdle { player.currentPosition > 400 && player.videoSize.width == 320 } }
        captureEvidence("media-playing")
        compose.runOnIdle { view.showController() }
        onView(withId(androidx.media3.ui.R.id.exo_play_pause)).perform(click())
        compose.runOnIdle { assertFalse(player.playWhenReady) }
        onView(withId(androidx.media3.ui.R.id.exo_progress)).perform(swipeRight())
        compose.waitUntil(10_000) {
            compose.runOnIdle {
                player.playbackState == Player.STATE_READY && !player.playWhenReady &&
                    player.currentPosition > player.duration / 2 && renderedPresentationUs.get() > 6_000_000
            }
        }
        captureEvidence("media-paused-after-seek")
        onView(allOf(anyOf(withId(androidx.media3.ui.R.id.exo_fullscreen),
            withId(androidx.media3.ui.R.id.exo_minimal_fullscreen)), isDisplayed())).perform(click())
        compose.onNodeWithText("preview-test.mp4").assertDoesNotExist()
        captureEvidence("media-fullscreen")
        pressBack()
        compose.onNodeWithText("preview-test.mp4").assertIsDisplayed()
        assertTrue(visible)
        pressBack()
        compose.waitUntil(5_000) { !visible }
        compose.runOnIdle { assertNull("Detached PlayerView must not retain the player", view.player) }
        assertTrue("Preview must release the borrowed source", closed.get())
    }

    @Test fun backgroundStopsPlaybackAndRevocationClearsBufferedMedia() {
        val view = open()
        val player = compose.runOnIdle { requireNotNull(view.player).also { it.play() } }
        compose.waitUntil(10_000) { compose.runOnIdle { player.currentPosition > 400 } }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) { compose.runOnIdle { player.playbackState == Player.STATE_READY } }
        compose.runOnIdle { assertFalse("Returning from background requires explicit playback", player.playWhenReady) }
        authorized.value = false
        compose.waitUntil(5_000) { compose.runOnIdle { player.mediaItemCount == 0 } }
        compose.runOnIdle { assertFalse(player.isPlaying) }
    }

    /** Optional controlled DAV benchmark. Timing is evidence, never a CI pass threshold. */
    @Test fun throttledDavReportsRangeLatencyPlaybackAndSeek() = kotlinx.coroutines.runBlocking<Unit> {
        val root = InstrumentationRegistry.getArguments().getString("mediaDavBenchmarkRoot")
        org.junit.Assume.assumeTrue(root != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val evidence = org.json.JSONObject()
        val requests = java.util.concurrent.CopyOnWriteArrayList<Pair<Long, Int>>()
        val client = net.weero.measix.pilot.data.webdav.WebDavClient()
        val authorization = okhttp3.Credentials.basic("media", "test")
        val metadata = client.mediaMetadata(requireNotNull(root), authorization, "video.mp4")
        val source = MediaPreviewSource("video.mp4", metadata.length, authorized,
            verifyAccess = { check(authorized.value) }, close = { closed.set(true) },
            readRange = { position, count ->
                requests += position to count
                client.mediaRange(root, authorization, "video.mp4", metadata, position, count)
            })
        val data = MediaPreviewDataSource(source)
        try {
            val comparisonStart = android.os.SystemClock.elapsedRealtime()
            client.mediaRange(root, authorization, "video.mp4", metadata, 1024 * 1024L, 8 * 1024 * 1024)
            evidence.put("fixedEightMiBMs", android.os.SystemClock.elapsedRealtime() - comparisonStart)
            data.open(androidx.media3.datasource.DataSpec.Builder().setUri(android.net.Uri.parse("workspace-media:/benchmark"))
                .setPosition(1024 * 1024L).build())
            val smallStart = android.os.SystemClock.elapsedRealtime()
            assertEquals(1, data.read(ByteArray(1), 0, 1))
            evidence.put("initialSeekWindowMs", android.os.SystemClock.elapsedRealtime() - smallStart)
            assertEquals(256 * 1024, requests.single().second)
            data.close()
            val prepareStart = android.os.SystemClock.elapsedRealtime()
            val view = openSource(source)
            val player = compose.runOnIdle { requireNotNull(view.player) as androidx.media3.exoplayer.ExoPlayer }
            evidence.put("prepareMs", android.os.SystemClock.elapsedRealtime() - prepareStart)
            val frameUs = java.util.concurrent.atomic.AtomicLong(-1)
            val rebufferings = AtomicInteger()
            val failure = java.util.concurrent.atomic.AtomicReference<String?>()
            compose.runOnIdle {
                player.setVideoFrameMetadataListener { time, _, _, _ -> frameUs.set(time) }
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_BUFFERING && player.playWhenReady) rebufferings.incrementAndGet()
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { failure.set(error.toString()) }
                })
                player.play()
            }
            compose.waitUntil(55_000) { compose.runOnIdle { player.currentPosition >= 30_000 || failure.get() != null } }
            assertNull(failure.get())
            evidence.put("continuousPlaybackMs", compose.runOnIdle { player.currentPosition })
                .put("continuousRebufferings", rebufferings.get())
            captureEvidence("media-dav-throttled-playing")
            val seekStart = android.os.SystemClock.elapsedRealtime()
            compose.runOnIdle { player.pause(); frameUs.set(-1); player.seekTo(2_000) }
            compose.waitUntil(20_000) { compose.runOnIdle {
                player.playbackState == Player.STATE_READY && frameUs.get() in 1_500_000..4_000_000
            } }
            evidence.put("pausedSeekMs", android.os.SystemClock.elapsedRealtime() - seekStart)
                .put("pausedSeekFrameUs", frameUs.get())
            captureEvidence("media-dav-throttled-seek")
            compose.runOnIdle { visible = false }
            compose.waitUntil(5_000) { closed.get() && compose.runOnIdle { view.player == null } }
            evidence.put("sourceClosed", true).put("playerViewDetached", true)
        } finally {
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            data.close(); source.close()
            evidence.put("fileBytes", metadata.length).put("ranges", org.json.JSONArray().apply {
                requests.forEach { put(org.json.JSONObject().put("position", it.first).put("count", it.second)) }
            })
            java.io.File(context.getExternalFilesDir(null), "media-dav-performance.json").writeText(evidence.toString(2))
        }
    }

    @Test fun rotateActionChangesActualDeviceOrientationWithoutRecreatingPlayer() {
        val activity = compose.activity
        val originalOrientation = activity.requestedOrientation
        compose.runOnUiThread { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        try {
            compose.waitUntil(10_000) { activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            val view = open()
            val player = compose.runOnIdle { requireNotNull(view.player).also { it.play() } }
            compose.waitUntil(10_000) { compose.runOnIdle { player.currentPosition > 400 } }
            val beforeRotation = compose.runOnIdle { player.currentPosition }
            val rotate = activity.getString(net.weero.measix.pilot.R.string.file_media_rotate)
            compose.onNodeWithText(rotate).performClick()
            compose.waitUntil(10_000) {
                compose.runOnIdle {
                    activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && view.width > view.height
                }
            }
            compose.runOnIdle {
                assertSame("Production configChanges must retain the Activity", activity, compose.activity)
                assertSame(player, view.player)
                assertEquals(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT, view.resizeMode)
                assertTrue(player.currentPosition >= beforeRotation)
            }
            captureEvidence("media-landscape")
            compose.onNodeWithText(rotate).performClick()
            compose.waitUntil(10_000) {
                compose.runOnIdle {
                    activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT && view.height > view.width
                }
            }
            compose.runOnIdle {
                assertSame(activity, compose.activity)
                assertSame(player, view.player)
                assertFalse("Rotation must not release the source", closed.get())
            }
            captureEvidence("media-portrait-restored")
        } finally {
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            compose.runOnUiThread { activity.requestedOrientation = originalOrientation }
        }
    }

    @Test fun sparseFourGiBVideoDecodesAndSeeksBeyondTwoGiB() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = java.io.File.createTempFile("media-four-gib-", ".mp4", context.cacheDir)
        try {
            val original = InstrumentationRegistry.getInstrumentation().context.assets
                .open("media/preview-test.mp4").use { it.readBytes() }
            makeSparseMp4(original, fixture)
            val maximumReadOffset = java.util.concurrent.atomic.AtomicLong()
            val totalRead = java.util.concurrent.atomic.AtomicLong()
            val source = MediaPreviewSource(fixture.name, fixture.length(), authorized,
                verifyAccess = { check(fixture.exists() && authorized.value) },
                readRange = { offset, count ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        maximumReadOffset.updateAndGet { maxOf(it, offset) }
                        totalRead.addAndGet(count.toLong())
                        java.io.RandomAccessFile(fixture, "r").use { file ->
                            file.seek(offset)
                            ByteArray(count).also { file.readFully(it) }
                        }
                    }
                })
            val view = openSource(source)
            val player = compose.runOnIdle { requireNotNull(view.player).also { it.play() } }
            compose.waitUntil(15_000) { compose.runOnIdle { player.currentPosition > 400 && player.videoSize.width == 320 } }
            compose.runOnIdle { player.seekTo(9_000) }
            compose.waitUntil(10_000) { compose.runOnIdle { player.playbackState == Player.STATE_READY && player.currentPosition >= 9_000 } }
            assertEquals(4L * 1024 * 1024 * 1024, fixture.length())
            assertTrue("Extractor must read media bytes beyond signed Int offsets", maximumReadOffset.get() > Int.MAX_VALUE)
            assertTrue("Random access must not download the sparse 4 GiB body", totalRead.get() < 32L * 1024 * 1024)
            captureEvidence("media-four-gib")
        } finally {
            compose.runOnIdle { visible = false }
            compose.waitForIdle()
            assertTrue("Large sparse fixture must be removed", !fixture.exists() || fixture.delete())
        }
    }

    /** Move mdat to 3 GiB, patch unsigned stco offsets, and fill the remaining extent with free boxes. */
    private fun makeSparseMp4(original: ByteArray, destination: java.io.File) {
        data class Box(val offset: Int, val size: Int, val type: String)
        fun boxAt(bytes: ByteArray, offset: Int): Box {
            val size = java.nio.ByteBuffer.wrap(bytes, offset, 4).int.toLong() and 0xffff_ffffL
            check(size in 8..Int.MAX_VALUE.toLong() && offset.toLong() + size <= bytes.size)
            return Box(offset, size.toInt(), String(bytes, offset + 4, 4, Charsets.US_ASCII))
        }
        val boxes = mutableListOf<Box>()
        var cursor = 0
        while (cursor < original.size) boxAt(original, cursor).let { boxes += it; cursor += it.size }
        val ftyp = boxes.single { it.type == "ftyp" }
        val moov = boxes.single { it.type == "moov" }
        val mdat = boxes.single { it.type == "mdat" }
        val metadata = original.copyOfRange(moov.offset, moov.offset + moov.size)
        val mediaStart = 3L * 1024 * 1024 * 1024
        val shift = mediaStart - mdat.offset
        var patched = 0
        fun patch(start: Int, end: Int) {
            var offset = start
            while (offset < end) {
                val box = boxAt(metadata, offset)
                check(offset + box.size <= end)
                if (box.type == "stco") {
                    val buffer = java.nio.ByteBuffer.wrap(metadata)
                    val count = buffer.getInt(offset + 12)
                    check(count >= 0 && offset.toLong() + 16L + count * 4L <= offset + box.size)
                    repeat(count) { index ->
                        val position = offset + 16 + index * 4
                        val previous = buffer.getInt(position).toLong() and 0xffff_ffffL
                        val updated = previous + shift
                        check(updated in mediaStart..0xffff_ffffL)
                        buffer.putInt(position, updated.toInt())
                        patched++
                    }
                } else if (box.type in setOf("moov", "trak", "mdia", "minf", "stbl")) {
                    patch(offset + 8, offset + box.size)
                }
                offset += box.size
            }
        }
        patch(0, metadata.size)
        check(patched > 0)
        java.io.RandomAccessFile(destination, "rw").use { file ->
            val finalLength = 4L * 1024 * 1024 * 1024
            file.setLength(finalLength)
            file.write(original, ftyp.offset, ftyp.size)
            file.write(metadata)
            val gap = mediaStart - file.filePointer
            check(gap in 8..0xffff_ffffL)
            file.writeInt(gap.toInt()); file.writeBytes("free")
            file.seek(mediaStart)
            file.write(original, mdat.offset, mdat.size)
            val tail = finalLength - file.filePointer
            check(tail in 8..0xffff_ffffL)
            file.writeInt(tail.toInt()); file.writeBytes("free")
        }
    }

    private fun captureEvidence(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "preview-evidence")
        check(directory.isDirectory || directory.mkdirs())
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            java.io.File(directory, "$name.png").outputStream().use {
                check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { screenshot.recycle() }
        // The runner uninstalls its app after the suite; retain UI evidence outside app data.
        check(shell("mkdir -p /data/local/tmp/preview-ui-evidence").isBlank())
        check(shell("cp ${directory.absolutePath}/$name.png /data/local/tmp/preview-ui-evidence/$name.png").isBlank())
    }

    private fun findPlayer(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) repeat(view.childCount) { index ->
            findPlayer(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
