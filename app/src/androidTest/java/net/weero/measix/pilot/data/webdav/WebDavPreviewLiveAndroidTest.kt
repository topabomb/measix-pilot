package net.weero.measix.pilot.data.webdav

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.FileFolders
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.RouteActivity
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.configuration.WebDavConnection
import net.weero.measix.pilot.data.configuration.WebDavConnectionChange
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.service.webdav.WebDavApplicationService
import net.weero.measix.pilot.service.webdav.WebDavBrowserService
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.context.*
import net.weero.measix.pilot.ui.pages.webdav.WebDavFilePage
import net.weero.measix.pilot.ui.pages.webdav.WebDavFileVM
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Reads a dedicated standard-DAV video fixture; temporary personal configuration is restored. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class WebDavPreviewLiveAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<RouteActivity>()

    @Test fun largeVideoSeeksThroughManagerAndCancelledDownloadDoesNotPublishAttachment() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("webdavPreviewLive") == "true")
        withTimeout(240_000) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val koin = GlobalContext.get()
            koin.get<ApplicationRecoveryGate>().awaitReady()
            val queries = koin.get<ConfigurationQueryService>()
            val commands = koin.get<ConfigurationApplicationService>()
            val before = queries.observeWebDavConfiguration().filterIsInstance<WebDavConfigurationReadState.Available>().first()
            check(before.selection.access == RealmAccess.Personal)
            check(before.currentId == null || before.current != null) { "Original current reference must be restorable before changing test configuration" }
            val connection = WebDavConnection(name = "DAV Preview Live", rootUrl = requireNotNull(arguments.getString("webdavLiveRoot")),
                username = requireNotNull(arguments.getString("webdavLiveUser")), password = requireNotNull(arguments.getString("webdavLivePassword")))
            val path = requireNotNull(arguments.getString("webdavPreviewPath"))
            check('/' !in path && path.endsWith(".mp4")) { "Fixture must be a video directly below its dedicated DAV root" }
            val store = ViewModelStore()
            val evidence = JSONObject().put("server", arguments.getString("webdavServerVersion"))
            var added = false
            var failure: Throwable? = null
            try {
                commands.changeWebDavConnection(before.selection, WebDavConnectionChange.Save(connection, null))
                added = true
                commands.changeWebDavConnection(before.selection, WebDavConnectionChange.Select(connection.id))
                val dav = koin.get<WebDavApplicationService>()
                val handle = dav.open(before.selection, connection.id.toString())
                try {
                    val file = requireNotNull(dav.stat(handle, path))
                    check(!file.directory && requireNotNull(file.size) >= 4L * 1024 * 1024 * 1024)
                    evidence.put("bytes", file.size)
                    run {
                        val artifacts = koin.get<ArtifactStore>()
                        val artifactDao = koin.get<AppDatabase>().artifactDao()
                        val beforeIds = artifactDao.listAllStatesByFolder(FileFolders.UPLOAD).first().map { it.id }.toSet()
                        var accepted: OwnedArtifact? = null
                        var terminal: Throwable? = null
                        var read = 0L
                        val cancelled = launch(Dispatchers.IO) {
                            val owner = currentCoroutineContext().job
                            artifacts.createFromStream(before.selection.access.scope, "cancelled-${UUID.randomUUID()}.bin", write = { output ->
                                dav.download(handle, path, output, file.etag, progress = { bytes, _ ->
                                    read = bytes
                                    if (bytes >= 1024 * 1024) owner.cancel()
                                })
                                "application/octet-stream"
                            }, receive = { accepted = it })
                            fail("Cancelled download must not reach publication")
                        }
                        cancelled.invokeOnCompletion { terminal = it }
                        withTimeout(30_000) { cancelled.join() }
                        assertTrue(cancelled.isCancelled)
                        assertTrue(read >= 1024 * 1024)
                        assertNull(accepted)
                        assertTrue(generateSequence(terminal) { it.cause }.all { it.suppressed.isEmpty() })
                        assertEquals(beforeIds, artifactDao.listAllStatesByFolder(FileFolders.UPLOAD).first().map { it.id }.toSet())
                        evidence.put("cancelledDownloadDoesNotPublishArtifact", true)
                    }
                } finally { dav.close(handle) }

                lateinit var vm: WebDavFileVM
                compose.runOnUiThread {
                    vm = WebDavFileVM(before.selection, queries, koin.get<WebDavBrowserService>(), context)
                    store.put("dav-preview", vm)
                    compose.activity.setContent {
                        MaterialTheme {
                            CompositionLocalProvider(
                                LocalNavController provides remember { Navigator(mutableStateListOf(Screen.Setting)) },
                                LocalSettings provides Settings(),
                                LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo(),
                            ) { WebDavFilePage(before.selection, vm) }
                        }
                    }
                }
                compose.waitUntil(30_000) { vm.state.value.directory != null }
                compose.onNodeWithTag("remote-file-list").performScrollToNode(hasText(path))
                compose.onNodeWithText(path).performClick()
                var view: PlayerView? = null
                compose.waitUntil(30_000) { compose.runOnIdle {
                    view = findPlayer(compose.activity.window.decorView)
                    view?.player?.playbackState == Player.STATE_READY
                } }
                val playerView = requireNotNull(view)
                val player = compose.runOnIdle { requireNotNull(playerView.player) as ExoPlayer }
                val frameUs = AtomicLong(-1)
                compose.runOnIdle {
                    player.setVideoFrameMetadataListener { time, _, _, _ -> frameUs.set(time) }
                    player.play()
                }
                compose.waitUntil(20_000) { compose.runOnIdle { player.currentPosition > 500 && player.videoSize.width > 0 } }
                val seekMs = compose.runOnIdle {
                    player.pause()
                    check(player.duration > 2_000)
                    (player.duration - 1_000).also {
                        assertTrue("Initial playback must not already have reached the seek target", player.currentPosition + 500 < it)
                        frameUs.set(-1)
                        player.seekTo(it)
                    }
                }
                compose.waitUntil(30_000) { compose.runOnIdle {
                    player.playbackState == Player.STATE_READY && frameUs.get() >= (seekMs - 500) * 1_000
                } }
                evidence.put("nearEndDecodedFrameUs", frameUs.get()).put("seekMs", seekMs)
                val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    File(context.getExternalFilesDir(null), "webdav-large-video-seek.png").outputStream().use {
                        check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { bitmap.recycle() }
                pressBack()
                compose.waitUntil(10_000) { compose.runOnIdle { playerView.player == null } }
                evidence.put("playerReleasedOnClose", true)
            } catch (error: Throwable) { failure = error; evidence.put("failure", error.stackTraceToString()); throw error }
            finally {
                compose.runOnUiThread { store.clear() }
                withContext(NonCancellable) {
                    try {
                        if (added) {
                            var restoreFailure: Throwable? = null
                            try { commands.changeWebDavConnection(before.selection, WebDavConnectionChange.Select(before.personalCurrentId)) }
                            catch (error: Throwable) { restoreFailure = error }
                            try { commands.changeWebDavConnection(before.selection, WebDavConnectionChange.Delete(connection.id)) }
                            catch (error: Throwable) { restoreFailure?.addSuppressed(error) ?: run { restoreFailure = error } }
                            restoreFailure?.let { throw it }
                        }
                        val restored = withTimeout(30_000) {
                            queries.observeWebDavConfiguration().filterIsInstance<WebDavConfigurationReadState.Available>()
                                .first { it.currentId == before.currentId && it.personalConnections.none { item -> item.id == connection.id } }
                        }
                        assertEquals(before.backup, restored.backup)
                        evidence.put("configurationRestored", true)
                    } catch (cleanup: Throwable) { failure?.addSuppressed(cleanup) ?: throw cleanup }
                    finally { File(context.cacheDir, "webdav-preview-live-evidence.json").writeText(evidence.toString(2)) }
                }
            }
        }
    }

    private fun findPlayer(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) repeat(view.childCount) { findPlayer(view.getChildAt(it))?.let { found -> return found } }
        return null
    }
}
