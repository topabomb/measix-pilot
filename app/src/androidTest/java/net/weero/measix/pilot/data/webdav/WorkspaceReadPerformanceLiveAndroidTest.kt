package net.weero.measix.pilot.data.webdav

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import net.weero.measix.pilot.BuildConfig
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.service.files.*
import net.weero.measix.pilot.service.remoteworkspace.*
import net.weero.measix.pilot.service.webdav.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Opt-in production read-only comparison. The input is consumed once; credentials never enter evidence. */
@RunWith(AndroidJUnit4::class)
class WorkspaceReadPerformanceLiveAndroidTest {
    @Test fun compareSameDirectoriesAndMedia() = runBlocking<Unit> {
        val argument = InstrumentationRegistry.getArguments().getString("workspaceReadPerformanceInput")
        assumeTrue(argument != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inputFile = File(requireNotNull(argument)).canonicalFile
        require(inputFile.toPath().startsWith(context.cacheDir.canonicalFile.toPath()) && inputFile.isFile)
        val input = try { JSONObject(inputFile.readText()) } finally { check(inputFile.delete()) }
        val report = JSONObject().put("readOnly", true)
        val samples = JSONArray().also { report.put("samples", it) }
        val reportFile = File(context.cacheDir, "workspace-read-performance.json")
        var davBrowser: FileBrowserSession? = null
        var filesBrowser: FileBrowserSession? = null
        var rawHandle: WebDavHandle? = null
        var dav: WebDavApplicationService? = null
        suspend fun <T> measure(name: String, path: String = "", action: suspend () -> T): T {
            val start = SystemClock.elapsedRealtime()
            val sample = JSONObject().put("operation", name).put("path", path)
            try { return withTimeout(60_000) { action() } }
            catch (error: Exception) { sample.put("failureType", error.javaClass.simpleName); throw error }
            finally {
                sample.put("elapsedMs", SystemClock.elapsedRealtime() - start)
                samples.put(sample)
                reportFile.writeText(report.toString(2))
            }
        }
        try {
            withTimeout(600_000) {
                val koin = GlobalContext.get()
                koin.get<ApplicationRecoveryCoordinator>()
                koin.get<ApplicationRecoveryGate>().awaitReady()
                val sessions = koin.get<EnterpriseSessionController>()
                val platform = koin.get<PlatformEnterpriseService>()
                val expectedOrigin = EnrollmentMaterialParser.normalizeOrigin(input.getString("platformUrl"))
                val access = if (input.optBoolean("resumeEnrolled")) {
                    val selected = requireNotNull(sessions.readPresentation().selection)
                    val original = selected.access as RealmAccess.Enterprise
                    check(sessions.platformContext(original.sessionId).platform.connection.origin == expectedOrigin)
                    check(original.scope.authority.deploymentId == input.getString("deploymentId"))
                    original
                } else {
                    val initial = (sessions.state.value as EnterpriseState.Available).manifest
                    check(initial.session == null && initial.pendingEnrollment == null && initial.lastIdentity == null)
                    val material = EnrollmentMaterialParser().parse(input.getString("enrollment"))
                    input.remove("enrollment")
                    measure("enroll") { platform.enroll(material, android.os.Build.MODEL, BuildConfig.VERSION_NAME) }
                }
                measure("synchronize") { koin.get<EnterpriseApplicationService>().synchronize(access) }
                val previous = requireNotNull(sessions.readPresentation().selection)
                if (previous.access != access) sessions.switchRealm(RealmSwitchRequest(previous, access)) {}
                val selection = requireNotNull(sessions.readPresentation().selection)
                val remote = koin.get<RemoteWorkspaceService>()
                dav = koin.get<WebDavApplicationService>()
                val davService = requireNotNull(dav)
                report.put("platformOrigin", expectedOrigin).put("deploymentId", access.scope.authority.deploymentId)
                val files = measure("files.open") { RemoteWorkspaceBrowserSource(remote, selection).open() }.also { filesBrowser = it }
                val webdav = measure("dav.open") { koin.get<WebDavBrowserService>().source(selection).open() }.also { davBrowser = it }
                val handle = davService.open(selection).also { rawHandle = it }
                val target = handle.target as WebDavTarget.Enterprise
                val token = platform.accessToken(access.sessionId, target.connection)
                val transport = WebDavClient()
                val auth = "Bearer ${token.value}"
                report.put("resourceId", target.id).put("agentSpaceId", target.space)
                val path = input.optString("directory", "")
                repeat(3) {
                    measure("files.refresh", path) { files.refresh() }
                    measure("files.list", path) { files.list(path) }
                    measure("dav.refresh", path) { webdav.refresh() }
                    measure("dav.list", path) { webdav.list(path) }
                    measure("dav.transport.list", path) { transport.list(target.root, auth, path) }
                }
                measure("files.browse.cold", path) { files.browse(path) }
                measure("dav.browse.cold", path) { webdav.browse(path) }
                repeat(3) {
                    measure("files.browse.cached", path) { files.browse(path) }
                    measure("dav.browse.cached", path) { webdav.browse(path) }
                }
                val videoPath = input.optString("video", "")
                if (videoPath.isNotBlank()) {
                    val file = requireNotNull(files.stat(videoPath))
                    val davFile = requireNotNull(webdav.stat(videoPath))
                    for ((name, browser, entry) in listOf(Triple("files", files, file), Triple("dav", webdav, davFile))) {
                        val source = measure("$name.media.open", videoPath) { browser.mediaSource(entry) }
                        try {
                            for (count in listOf(256 * 1024, 1024 * 1024, 8 * 1024 * 1024)) {
                                if (source.length > count + 1024 * 1024L) {
                                    measure("$name.media.range.$count", videoPath) { source.readRange(1024 * 1024L, count) }
                                }
                            }
                        } finally { source.close() }
                    }
                } else {
                    // Discover candidates without recursively traversing production storage.
                    val directories = ArrayDeque<String>().apply { add(path) }
                    val candidates = JSONArray()
                    repeat(8) {
                        if (directories.isEmpty() || candidates.length() >= 8) return@repeat
                        val directory = directories.removeFirst()
                        val listing = measure("files.discover", directory) { files.browse(directory) }
                        listing.files.filter { it.directory }.take(8).forEach { directories.addLast(it.path) }
                        listing.files.filter { !it.directory && it.name.substringAfterLast('.').lowercase() in setOf("mp4", "webm", "mkv") }
                            .take(8).forEach { candidates.put(JSONObject().put("path", it.path).put("bytes", it.size)) }
                    }
                    report.put("videoCandidates", candidates)
                }
                report.put("completed", true)
            }
        } finally {
            withContext(NonCancellable) {
                filesBrowser?.close(); davBrowser?.close()
                rawHandle?.let { dav?.close(it) }
                reportFile.writeText(report.toString(2))
            }
        }
    }
}
