package net.weero.measix.pilot.data.webdav

import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.weero.measix.pilot.BuildConfig
import net.weero.measix.pilot.RouteActivity
import net.weero.measix.pilot.Screen
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.service.remoteworkspace.RemoteWorkspaceHandle
import net.weero.measix.pilot.service.remoteworkspace.RemoteWorkspaceService
import net.weero.measix.pilot.service.webdav.WebDavApplicationService
import net.weero.measix.pilot.service.webdav.WebDavBrowserService
import net.weero.measix.pilot.service.webdav.WebDavHandle
import net.weero.measix.pilot.service.webdav.WebDavTarget
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.components.files.FileBrowserVM
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.ui.context.Navigator
import net.weero.measix.pilot.ui.pages.remoteworkspace.RemoteWorkspacePage
import net.weero.measix.pilot.ui.pages.remoteworkspace.RemoteWorkspaceVM
import net.weero.measix.pilot.ui.pages.webdav.WebDavFilePage
import net.weero.measix.pilot.ui.pages.webdav.WebDavFileVM
import net.weero.measix.pilot.utils.redactDiagnosticSecrets
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Real Core opt-in on a dedicated device. Resuming requires the exact selected deployment and origin. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class EnterpriseMediaPreviewLiveAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<RouteActivity>()

    @Test fun enrolledCoreDavAndFilesPlayAndSeekTheSameVideo() = runBlocking<Unit> {
        val inputPath = InstrumentationRegistry.getArguments().getString("enterpriseMediaInput")
        assumeTrue(inputPath != null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val cache = context.cacheDir.canonicalFile
        val inputFile = File(requireNotNull(inputPath)).canonicalFile
        require(inputFile.toPath().startsWith(cache.toPath()) && inputFile.isFile) {
            "enterprise_media_input_must_be_a_cache_file"
        }
        val folder = "android-enterprise-media-${UUID.randomUUID()}"
        val path = "$folder/video.mp4"
        val evidence = JSONObject().put("folder", folder).put("path", path)
        val rootDirectoryFailures = JSONObject()
        evidence.put("rootDirectoryFailures", rootDirectoryFailures)
        val cleanup = JSONObject()
        val store = ViewModelStore()
        val viewModels = mutableListOf<FileBrowserVM>()
        var video: File? = null
        var enrollment: String? = null
        var enrollmentCode: String? = null
        var dav: WebDavApplicationService? = null
        var remote: RemoteWorkspaceService? = null
        var davHandle: WebDavHandle? = null
        var remoteHandle: RemoteWorkspaceHandle? = null
        var directoryCreated = false
        var failure: Throwable? = null
        fun diagnosis(error: Throwable): String = listOfNotNull(enrollment, enrollmentCode).filter { it.isNotEmpty() }
            .fold(redactDiagnosticSecrets(error.stackTraceToString())) { text, secret -> text.replace(secret, "[REDACTED_ENROLLMENT]") }
        suspend fun clean(name: String, action: suspend () -> Unit) {
            try {
                withTimeout(45_000) { action() }
                cleanup.put(name, "completed")
            } catch (error: Throwable) {
                cleanup.put(name, diagnosis(error))
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        try {
            withTimeout(600_000) {
                evidence.put("stage", "input")
                val input = try {
                    try { JSONObject(inputFile.readText()) }
                    catch (_: org.json.JSONException) { error("enterprise_media_input_invalid_json") }
                } finally { check(inputFile.delete()) { "enterprise_media_input_cleanup_failed" } }
                val resumeEnrolled = input.optBoolean("resumeEnrolled", false)
                enrollment = if (resumeEnrolled) null else input.getString("enrollment")
                input.remove("enrollment")
                evidence.put("server", input.getString("server")).put("resumeEnrolled", resumeEnrolled)
                val fixture = File(input.getString("videoFile")).canonicalFile
                require(fixture != inputFile && fixture.toPath().startsWith(cache.toPath()) && fixture.isFile) {
                    "enterprise_media_video_must_be_a_distinct_cache_file"
                }
                video = fixture
                val fileBytes = fixture.length()
                require(fileBytes > 1024 * 1024) { "enterprise_media_video_too_short_for_nonzero_range" }
                val localInfo = withContext(Dispatchers.IO) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(fixture.absolutePath)
                        JSONObject().put("durationMs", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong())
                            .put("width", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt())
                            .put("height", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt())
                    } finally { retriever.release() }
                }
                require(localInfo.getLong("durationMs") >= 30_000) { "enterprise_media_video_requires_at_least_thirty_seconds" }
                val original = withContext(Dispatchers.IO) { DigestSink().also { sink -> fixture.inputStream().use { it.copyTo(sink) } } }
                evidence.put("media", localInfo.put("bytes", fileBytes).put("sha256", original.hex()))
                val koin = GlobalContext.get()
                koin.get<ApplicationRecoveryCoordinator>()
                koin.get<ApplicationRecoveryGate>().awaitReady()
                val sessions = koin.get<EnterpriseSessionController>()
                val initial = (sessions.state.value as EnterpriseState.Available).manifest
                var resumeSelection: RealmSelection? = null
                evidence.put("stage", if (resumeEnrolled) "resume_identity_validation" else "enrollment_and_synchronization")
                val access = if (resumeEnrolled) {
                    val selected = requireNotNull(sessions.readPresentation().selection)
                    val original = selected.access as? RealmAccess.Enterprise
                        ?: error("enterprise_media_resume_requires_selected_enterprise")
                    val existing = requireNotNull(initial.session) { "enterprise_media_resume_requires_existing_session" }
                    check(initial.pendingEnrollment == null && existing.id == original.sessionId && existing.identity.scope == original.scope) {
                        "enterprise_media_resume_session_identity_mismatch"
                    }
                    sessions.withSelectedRealmSelection(selected) {}
                    val connection = sessions.platformContext(original.sessionId).platform.connection
                    val expectedOrigin = EnrollmentMaterialParser.normalizeOrigin(input.getString("platformUrl"))
                    val expectedDeployment = input.getString("deploymentId")
                    check(connection.origin == expectedOrigin) { "enterprise_media_resume_platform_origin_mismatch" }
                    check(original.scope.authority.deploymentId == expectedDeployment &&
                        connection.discovery.deploymentId == expectedDeployment) { "enterprise_media_resume_deployment_mismatch" }
                    resumeSelection = selected
                    evidence.put("platformOrigin", connection.origin).put("deploymentId", expectedDeployment)
                    original
                } else {
                    check(initial.session == null && initial.pendingEnrollment == null && initial.lastIdentity == null) {
                        "enterprise_media_requires_unbound_test_device"
                    }
                    val material = EnrollmentMaterialParser().parse(requireNotNull(enrollment))
                    enrollmentCode = material.code
                    koin.get<PlatformEnterpriseService>().enroll(material, android.os.Build.MODEL, BuildConfig.VERSION_NAME)
                }
                evidence.put("stage", "synchronization")
                assertEquals(EnterpriseSynchronizationCommandResult.COMPLETED,
                    koin.get<EnterpriseApplicationService>().synchronize(access))
                resumeSelection?.let { sessions.withSelectedRealmSelection(it) {} }
                val before = requireNotNull(sessions.readPresentation().selection)
                if (before.access != access) sessions.switchRealm(RealmSwitchRequest(before, access)) {}
                val selection = requireNotNull(sessions.readPresentation().selection)
                assertEquals(access, selection.access)
                dav = koin.get<WebDavApplicationService>()
                remote = koin.get<RemoteWorkspaceService>()
                val davService = requireNotNull(dav)
                val remoteService = requireNotNull(remote)
                evidence.put("stage", "open_current_enterprise_resources")
                val openedDav = davService.open(selection).also { davHandle = it }
                val target = openedDav.target as? WebDavTarget.Enterprise
                    ?: error("enterprise_media_current_dav_is_not_enterprise")
                check(openedDav.writable) { "enterprise_media_current_dav_is_read_only" }
                val openedFiles = remoteService.open(selection).also { remoteHandle = it }
                assertEquals(target.space, openedFiles.space)
                evidence.put("resourceId", target.id).put("agentSpaceId", openedFiles.space)
                    .put("davBindingRevision", target.bindingRevision)
                if (input.has("cleanupPreviousDirectory")) {
                    check(resumeEnrolled && openedFiles.space == input.getString("cleanupPreviousAgentSpaceId")) {
                        "enterprise_media_previous_cleanup_requires_original_space"
                    }
                    val previous = input.getString("cleanupPreviousDirectory")
                    require(previous.matches(Regex("android-enterprise-media-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) {
                        "enterprise_media_previous_cleanup_requires_recorded_test_directory"
                    }
                    // The host supplies only an acknowledged folder from a previous invocation's evidence.
                    // This is a new authorized cleanup operation in the same space, never rebinding the old operation.
                    if (davService.stat(openedDav, previous) != null) {
                        davService.mutate(openedDav, previous, "DELETE", directory = true)
                    }
                    assertNull(davService.stat(openedDav, previous))
                    evidence.put("previousTestDirectoryCleanup", previous)
                }
                evidence.put("stage", "upload")
                davService.mkdir(openedDav, folder)
                directoryCreated = true
                val uploadStart = SystemClock.elapsedRealtime()
                davService.upload(openedDav, path, fileBytes, { fixture.inputStream() })
                evidence.put("uploadMs", SystemClock.elapsedRealtime() - uploadStart)
                val stat = requireNotNull(davService.stat(openedDav, path))
                val metadata = davService.mediaMetadata(openedDav, path)
                assertEquals(fileBytes, metadata.length)
                assertEquals(fileBytes, stat.size)
                assertEquals(stat.etag, metadata.etag)
                WebDavClient.requireStrongEtag(metadata.etag)
                val filesFile = remoteService.list(openedFiles, folder).files.single { it.path == path }
                assertEquals(fileBytes, filesFile.size)
                val offset = 1024 * 1024L
                val rangeCount = 64 * 1024
                val expectedRange = withContext(Dispatchers.IO) {
                    RandomAccessFile(fixture, "r").use { file ->
                        file.seek(offset)
                        ByteArray(rangeCount).also(file::readFully)
                    }
                }
                evidence.put("stage", "head_range_and_streaming_digests")
                assertArrayEquals(expectedRange, davService.mediaRange(openedDav, path, metadata, offset, rangeCount))
                val filesSource = remoteService.mediaSource(openedFiles, filesFile)
                try {
                    assertEquals(fileBytes, filesSource.length)
                    assertArrayEquals(expectedRange, filesSource.readRange(offset, rangeCount))
                    if (input.optBoolean("rangeBenchmark")) {
                        val timings = org.json.JSONArray().also { evidence.put("rangeTimings", it) }
                        for (count in listOf(256 * 1024, 1024 * 1024, 8 * 1024 * 1024)) {
                            if (offset + count > fileBytes) continue
                            var start = SystemClock.elapsedRealtime()
                            assertEquals(count, filesSource.readRange(offset, count).size)
                            timings.put(JSONObject().put("source", "files").put("bytes", count)
                                .put("elapsedMs", SystemClock.elapsedRealtime() - start))
                            start = SystemClock.elapsedRealtime()
                            assertEquals(count, davService.mediaRange(openedDav, path, metadata, offset, count).size)
                            timings.put(JSONObject().put("source", "dav").put("bytes", count)
                                .put("elapsedMs", SystemClock.elapsedRealtime() - start))
                        }
                    }
                } finally { filesSource.close() }
                val davDigest = DigestSink()
                assertEquals(fileBytes, davService.download(openedDav, path, davDigest, metadata.etag))
                assertEquals(fileBytes, davDigest.bytes)
                assertEquals(original.hex(), davDigest.hex())
                val filesDigest = DigestSink()
                val filesContent = remoteService.download(openedFiles, filesFile, { filesDigest })
                assertEquals(fileBytes, filesDigest.bytes)
                assertEquals(original.hex(), filesDigest.hex())
                evidence.put("dav", JSONObject().put("headBytes", metadata.length).put("etag", metadata.etag)
                    .put("sha256", davDigest.hex()).put("nonzeroRangeValidatedByOwner", true))
                    .put("files", JSONObject().put("headBytes", filesSource.length).put("contentEtag", filesContent.etag)
                        .put("sha256", filesDigest.hex()).put("nonzeroRangeValidatedByOwner", true))
                    .put("range", JSONObject().put("position", offset).put("count", rangeCount)
                        .put("sha256", MessageDigest.getInstance("SHA-256").digest(expectedRange).toHex())
                        .put("validated", "206, pinned strong ETag, Content-Range, identity encoding, exact bytes"))
                val browser = koin.get<WebDavBrowserService>()
                val queries = koin.get<ConfigurationQueryService>()
                lateinit var filesVm: RemoteWorkspaceVM
                compose.runOnUiThread {
                    filesVm = RemoteWorkspaceVM(selection, remoteService, context)
                    viewModels += filesVm
                    store.put("enterprise-media-files", filesVm)
                    render { RemoteWorkspacePage(selection, filesVm) }
                }
                evidence.put("stage", "files_page_playback")
                evidence.put("filesPlayback", playThroughPage(filesVm, folder, "files", localInfo, rootDirectoryFailures))
                withContext(Dispatchers.Main) { filesVm.leave() }
                lateinit var davVm: WebDavFileVM
                compose.runOnUiThread {
                    davVm = WebDavFileVM(selection, queries, browser, context)
                    viewModels += davVm
                    store.put("enterprise-media-dav", davVm)
                    render { WebDavFilePage(selection, davVm) }
                }
                evidence.put("stage", "dav_page_playback")
                val davPlayback = playThroughPage(davVm, folder, "dav", localInfo, rootDirectoryFailures)
                assertEquals(target.id, davVm.targetId.value)
                evidence.put("davPlayback", davPlayback)
                withContext(Dispatchers.Main) { davVm.leave() }
                if (rootDirectoryFailures.length() != 0) {
                    evidence.put("stage", "playback_verified_root_directory_failed")
                    error("enterprise_media_root_directory_failed: $rootDirectoryFailures")
                }
                evidence.put("stage", "completed")
            }
        } catch (error: Throwable) { failure = error }
        finally {
            withContext(NonCancellable) {
                clean("playerComposition") {
                    compose.runOnUiThread { compose.activity.setContent {} }
                    compose.runOnIdle { assertNull(findPlayer(compose.activity.window.decorView)) }
                }
                viewModels.forEachIndexed { index, vm -> clean("pageSession$index") { withContext(Dispatchers.Main) { vm.leave() } } }
                clean("viewModelStore") { compose.runOnUiThread { store.clear() } }
                remoteHandle?.let { handle -> clean("filesHandle") { requireNotNull(remote).close(handle) } }
                if (directoryCreated) clean("testDirectory") {
                    val service = requireNotNull(dav)
                    val handle = requireNotNull(davHandle)
                    if (service.stat(handle, folder) != null) service.mutate(handle, folder, "DELETE", directory = true)
                    assertNull(service.stat(handle, folder))
                }
                davHandle?.let { handle -> clean("davHandle") { requireNotNull(dav).close(handle) } }
                video?.let { fixture -> clean("videoFixture") { check(!fixture.exists() || fixture.delete()) } }
                clean("inputFixture") { check(!inputFile.exists() || inputFile.delete()) }
                evidence.put("cleanup", cleanup).put("outcome", if (failure == null) "passed" else "failed")
                failure?.let { evidence.put("failure", diagnosis(it)) }
                File(context.cacheDir, "enterprise-media-preview-live-evidence.json").writeText(evidence.toString(2))
            }
        }
        failure?.let { throw AssertionError(diagnosis(it)) }
    }

    private fun render(content: @Composable () -> Unit) {
        compose.activity.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalNavController provides remember { Navigator(mutableStateListOf(Screen.Enterprise)) },
                    LocalSettings provides Settings(),
                    LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo(),
                ) { content() }
            }
        }
    }

    private fun playThroughPage(vm: FileBrowserVM, folder: String, source: String, media: JSONObject,
        rootDirectoryFailures: JSONObject,
    ): JSONObject {
        compose.waitUntil(60_000) {
            !vm.state.value.loading && (vm.state.value.directory != null || vm.state.value.error != null || vm.state.value.revoked)
        }
        val root = vm.state.value
        check(!root.revoked && root.handle != null) { "$source page access revoked: ${root.error}" }
        val navigation = if (root.error == null) {
            fileNode(folder).performClick()
            "root_directory"
        } else {
            rootDirectoryFailures.put(source, root.error)
            // Keep the complete scenario failed, while measuring playback inside this acknowledged test directory.
            // browse retains the original handle and uses the same owner authorization and directory query as the UI.
            compose.runOnUiThread { vm.browse(folder) }
            "confirmed_test_directory_after_root_failure"
        }
        compose.waitUntil(60_000) {
            !vm.state.value.loading && (vm.state.value.directory?.path == folder || vm.state.value.error != null || vm.state.value.revoked)
        }
        check(vm.state.value.error == null && !vm.state.value.revoked && vm.state.value.directory?.path == folder) {
            "$source directory: ${vm.state.value.error}"
        }
        val started = SystemClock.elapsedRealtime()
        fileNode("video.mp4").performClick()
        var view: PlayerView? = null
        compose.waitUntil(60_000) { compose.runOnIdle {
            view = findPlayer(compose.activity.window.decorView)
            view?.player?.playbackState == Player.STATE_READY || view?.player?.playerError != null || vm.state.value.error != null
        } }
        check(vm.state.value.error == null) { "$source preview: ${vm.state.value.error}" }
        val playerView = requireNotNull(view) { "$source player missing" }
        val player = compose.runOnIdle { requireNotNull(playerView.player) as ExoPlayer }
        compose.runOnIdle { check(player.playerError == null) { "$source prepare: ${player.playerError}" } }
        val result = JSONObject().put("navigation", navigation).put("prepareMs", SystemClock.elapsedRealtime() - started)
        val frameUs = AtomicLong(-1)
        val frames = AtomicInteger()
        val bufferings = AtomicInteger()
        val error = AtomicReference<String?>()
        compose.runOnIdle {
            player.setVideoFrameMetadataListener { time, _, _, _ -> frameUs.set(time); frames.incrementAndGet() }
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_BUFFERING && player.playWhenReady) bufferings.incrementAndGet()
                }
                override fun onPlayerError(failure: androidx.media3.common.PlaybackException) { error.set(failure.stackTraceToString()) }
            })
            playerView.showController()
        }
        val playbackStart = SystemClock.elapsedRealtime()
        onView(withId(androidx.media3.ui.R.id.exo_play_pause)).perform(click())
        compose.waitUntil(60_000) { compose.runOnIdle { player.currentPosition >= 15_000 && frames.get() > 0 || error.get() != null } }
        check(error.get() == null) { "$source playback: ${error.get()}" }
        compose.runOnIdle {
            assertEquals(media.getInt("width"), player.videoSize.width)
            assertEquals(media.getInt("height"), player.videoSize.height)
            result.put("continuousPositionMs", player.currentPosition).put("decodedFrames", frames.get())
            playerView.showController()
        }
        result.put("continuousWallMs", SystemClock.elapsedRealtime() - playbackStart).put("bufferingTransitions", bufferings.get())
        capture("enterprise-media-$source-playing")
        onView(withId(androidx.media3.ui.R.id.exo_play_pause)).perform(click())
        compose.waitUntil(5_000) { compose.runOnIdle { !player.playWhenReady } }
        val seekStart = SystemClock.elapsedRealtime()
        val seekMs = compose.runOnIdle {
            val target = player.duration - 3_000
            check(target > player.currentPosition + 1_000) { "$source fixture has no distinct seek target" }
            frameUs.set(-1)
            player.seekTo(target)
            target
        }
        compose.waitUntil(60_000) { compose.runOnIdle {
            player.playbackState == Player.STATE_READY && !player.playWhenReady && frameUs.get() >= (seekMs - 1_000) * 1_000 || error.get() != null
        } }
        check(error.get() == null) { "$source seek: ${error.get()}" }
        result.put("pausedSeekMs", SystemClock.elapsedRealtime() - seekStart).put("seekTargetMs", seekMs)
            .put("pausedSeekFrameUs", frameUs.get())
        capture("enterprise-media-$source-paused-seek")
        pressBack()
        compose.waitUntil(15_000) { compose.runOnIdle { playerView.player == null && vm.state.value.preview == null } }
        return result.put("playerDetachedOnClose", true)
    }

    private fun fileNode(name: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.onNodeWithTag("remote-file-list").performScrollToNode(hasText(name))
        return compose.onNodeWithText(name)
    }

    private fun findPlayer(view: View): PlayerView? {
        if (view is PlayerView) return view
        if (view is ViewGroup) repeat(view.childCount) { findPlayer(view.getChildAt(it))?.let { player -> return player } }
        return null
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private class DigestSink : OutputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var result: String? = null
        var bytes = 0L
            private set
        override fun write(value: Int) { digest.update(value.toByte()); bytes++ }
        override fun write(buffer: ByteArray, offset: Int, length: Int) { digest.update(buffer, offset, length); bytes += length }
        fun hex(): String = result ?: digest.digest().toHex().also { result = it }
    }

    private companion object {
        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
