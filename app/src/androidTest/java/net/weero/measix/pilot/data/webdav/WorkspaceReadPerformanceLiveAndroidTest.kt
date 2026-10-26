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
import okio.buffer

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
        val evidenceLock = Any()
        val probeOperation = java.util.concurrent.atomic.AtomicReference("")
        val httpEvents = JSONArray().also { report.put("rawTransportEvents", it) }
        val bodyReads = JSONArray().also { report.put("rawBodyReads", it) }
        fun saveReport() = synchronized(evidenceLock) { reportFile.writeText(report.toString(2)) }
        var davBrowser: FileBrowserSession? = null
        var filesBrowser: FileBrowserSession? = null
        var rawHandle: WebDavHandle? = null
        var dav: WebDavApplicationService? = null
        var probeHttp: okhttp3.OkHttpClient? = null
        suspend fun <T> measure(name: String, path: String = "", action: suspend () -> T): T {
            probeOperation.set(name)
            val start = SystemClock.elapsedRealtime()
            val sample = JSONObject().put("operation", name).put("path", path)
            try { return withTimeout(60_000) { action() } }
            catch (error: Exception) { sample.put("failureType", error.javaClass.simpleName); throw error }
            finally {
                sample.put("elapsedMs", SystemClock.elapsedRealtime() - start)
                samples.put(sample)
                saveReport()
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
                val browserService = koin.get<WebDavBrowserService>()
                val webdav = measure("dav.open") { browserService.source(selection).open() }.also { davBrowser = it }
                val handle = davService.open(selection).also { rawHandle = it }
                val target = handle.target as WebDavTarget.Enterprise
                val token = platform.accessToken(access.sessionId, target.connection)
                val probeClient = okhttp3.OkHttpClient.Builder()
                    .followRedirects(false).followSslRedirects(false)
                    .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
                    .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .addNetworkInterceptor { chain ->
                        val operation = probeOperation.get()
                        val response = chain.proceed(chain.request())
                        val originalBody = response.body
                        val started = SystemClock.elapsedRealtime()
                        val lastData = java.util.concurrent.atomic.AtomicLong(-1)
                        val eof = java.util.concurrent.atomic.AtomicLong(-1)
                        val readCalls = java.util.concurrent.atomic.AtomicInteger()
                        val completed = java.util.concurrent.atomic.AtomicBoolean()
                        val source = object : okio.ForwardingSource(originalBody.source()) {
                            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                                val count = super.read(sink, byteCount)
                                if (count > 0) { lastData.set(SystemClock.elapsedRealtime() - started); readCalls.incrementAndGet() }
                                if (count == -1L) eof.set(SystemClock.elapsedRealtime() - started)
                                return count
                            }
                            override fun close() {
                                try { super.close() } finally {
                                    if (completed.compareAndSet(false, true)) synchronized(evidenceLock) {
                                        bodyReads.put(JSONObject().put("operation", operation)
                                            .put("lastPositiveReadMs", lastData.get()).put("eofReadMs", eof.get())
                                            .put("positiveReads", readCalls.get()).put("closeMs", SystemClock.elapsedRealtime() - started))
                                    }
                                }
                            }
                        }.buffer()
                        response.newBuilder().body(object : okhttp3.ResponseBody() {
                            override fun contentType() = originalBody.contentType()
                            override fun contentLength() = originalBody.contentLength()
                            override fun source() = source
                        }).build()
                    }
                    .eventListenerFactory { call ->
                        val started = SystemClock.elapsedRealtime()
                        val event = JSONObject().put("operation", probeOperation.get())
                            .put("epochMs", System.currentTimeMillis()).put("method", call.request().method)
                            .put("path", call.request().url.encodedPath)
                        object : okhttp3.EventListener() {
                            override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) {
                                event.put("headersMs", SystemClock.elapsedRealtime() - started)
                                    .put("status", response.code).put("protocol", response.protocol.toString())
                                    .put("contentLength", response.header("Content-Length") ?: "unknown")
                                    .put("encoding", response.header("Content-Encoding") ?: "identity")
                                    .put("requestId", response.header("X-Request-Id") ?: JSONObject.NULL)
                                    .put("coreRequestId", response.header("X-Measix-Request-Id") ?: JSONObject.NULL)
                            }
                            override fun responseBodyStart(call: okhttp3.Call) {
                                event.put("bodyReadStartMs", SystemClock.elapsedRealtime() - started)
                            }
                            override fun responseBodyEnd(call: okhttp3.Call, byteCount: Long) {
                                event.put("bodyEndMs", SystemClock.elapsedRealtime() - started).put("bytes", byteCount)
                            }
                            override fun callEnd(call: okhttp3.Call) {
                                event.put("callMs", SystemClock.elapsedRealtime() - started)
                                synchronized(evidenceLock) { httpEvents.put(event) }
                            }
                            override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) {
                                event.put("callMs", SystemClock.elapsedRealtime() - started).put("failureType", ioe.javaClass.simpleName)
                                synchronized(evidenceLock) { httpEvents.put(event) }
                            }
                        }
                    }.build().also { probeHttp = it }
                val transport = WebDavClient(probeClient)
                val filesTransport = PlatformWorkspaceClient(probeClient)
                val auth = "Bearer ${token.value}"
                report.put("resourceId", target.id).put("agentSpaceId", target.space)
                val path = input.optString("directory", "")
                val consistency = JSONArray().also { report.put("directoryComparisons", it) }
                suspend fun openWithStatusChecks() = measure("dav.open.withStatusChecks", path) {
                    davService.refresh(selection)
                    browserService.source(selection).open().close()
                }
                repeat(input.optInt("rounds", 5).coerceIn(3, 8)) { round ->
                    if (round % 2 == 0) openWithStatusChecks()
                    measure("dav.open.singleCheck", path) { browserService.source(selection).open().close() }
                    if (round % 2 != 0) openWithStatusChecks()
                }
                // Reconstruct the former network-check sequence in the same process and transport.
                // Alternate its order against the optimized read to reduce deployment/warmup bias.
                suspend fun readWithStatusChecks() = measure("dav.list.withStatusChecks", path) {
                    davService.validate(handle)
                    webdav.list(path).also { davService.validate(handle) }
                }
                repeat(input.optInt("rounds", 5).coerceIn(3, 8)) { round ->
                    measure("workspace.status", path) { remote.readDavProjection(access) }
                    measure("files.refresh", path) { files.refresh() }
                    val fileList = measure("files.list", path) { files.list(path) }
                    measure("dav.refresh", path) { webdav.refresh() }
                    val checkedBefore = if (round % 2 == 0) readWithStatusChecks() else null
                    val davList = measure("dav.list", path) { webdav.list(path) }
                    val checkedDavList = checkedBefore ?: readWithStatusChecks()
                    val rawFiles = measure("files.transport.list", path) {
                        filesTransport.list(target.connection, token.value, requireNotNull(target.space), path)
                    }
                    val rawDav = measure("dav.transport.list", path) { transport.list(target.root, auth, path) }
                    measure("workspace.transport.status", path) { filesTransport.state(target.connection, token.value) }
                    val paths = fileList.files.map { entry -> entry.path }.toSet()
                    val davPaths = davList.files.map { entry -> entry.path }.toSet()
                    check(checkedDavList.files.map { it.path }.toSet() == davPaths) { "dav_status_check_comparison_changed" }
                    val rawFilePaths = rawFiles.entries.map { entry -> entry.path }.toSet()
                    val rawDavPaths = rawDav.files.map { entry -> entry.path }.toSet()
                    fun visible(values: Set<String>) = values.filterNot { path.isEmpty() && it == ".agent-space" }.toSet()
                    consistency.put(JSONObject().put("entries", paths.size).put("samePaths",
                        paths == davPaths && paths == rawFilePaths && paths == rawDavPaths)
                        .put("sameVisiblePaths", paths == visible(davPaths) && paths == rawFilePaths && paths == visible(rawDavPaths))
                        .put("davEntries", davPaths.size).put("rawDavEntries", rawDavPaths.size)
                        .put("filesOnly", JSONArray((paths - davPaths).sorted()))
                        .put("davOnly", JSONArray((davPaths - paths).sorted())))
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
                probeHttp?.connectionPool?.evictAll()
                probeHttp?.dispatcher?.executorService?.shutdown()
                saveReport()
            }
        }
    }
}
