package net.weero.measix.pilot.data.webdav

import at.bitfire.dav4jvm.ktor.ResponseParser
import at.bitfire.dav4jvm.property.webdav.*
import io.ktor.http.Url
import java.io.FilterInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

internal data class DavFile(val path: String, val directory: Boolean, val size: Long?, val modified: Instant?, val etag: String?)
internal data class DavDirectory(val files: List<DavFile>, val self: DavFile?, val usedBytes: Long?, val availableBytes: Long?)
internal data class DavMediaMetadata(val length: Long, val etag: String)
internal class DavHttpException(val status: Int, val method: String, val path: String) :
    IOException("webdav_http_error: $method $path (HTTP $status)")
internal class DavMultiStatusException(val failures: List<Pair<String, Int>>) :
    IOException("webdav_partial_result: " + failures.joinToString { "${it.first}: HTTP ${it.second}" })

/** HTTP IO only. Callers own credentials, access checks, result uncertainty and file publication. */
internal class WebDavClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .cache(null).callTimeout(0, TimeUnit.SECONDS).connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build(),
    private val httpFailure: ((Response, String) -> IOException)? = null,
) {
    suspend fun list(root: String, authorization: String, path: String = "", depth: Int = 1): DavDirectory {
        return properties(root, authorization, path, depth, directory = true).also {
            check(it.self?.directory == true) { "webdav_not_directory" }
        }
    }

    /** A direct query, so a partial or capped parent listing can never imply absence. */
    suspend fun stat(root: String, authorization: String, path: String): DavFile? = try {
        properties(root, authorization, path, 0, directory = path.isEmpty()).self
    } catch (failure: DavHttpException) {
        if (failure.status == 404) null else throw failure
    } catch (failure: DavMultiStatusException) {
        if (failure.failures == listOf(path to 404)) null else throw failure
    }

    private suspend fun properties(root: String, authorization: String, path: String, depth: Int, directory: Boolean): DavDirectory {
        require(depth == 0 || depth == 1)
        val paths = WebDavPaths(root)
        val request = request(paths, path, authorization, "PROPFIND", PROPERTIES.toRequestBody(XML), directory = directory)
            .header("Depth", depth.toString()).build()
        return execute(request) { response ->
            requireStatus(response, path, 207)
            val entries = mutableListOf<DavFile>()
            val names = mutableSetOf<String>()
            var self: DavFile? = null
            var used: Long? = null
            var available: Long? = null
            parseMultiStatus(response, paths) { item ->
                val name = paths.pathFromHref(item.href.toString())
                val status = item.status?.value
                if (status != null && status !in 200..299) throw DavMultiStatusException(listOf(name to status))
                val type = item[ResourceType::class.java] ?: throw IOException("webdav_resource_type_unavailable: $name")
                val entry = DavFile(name, WebDAV.Collection in type.types,
                    item[GetContentLength::class.java]?.contentLength,
                    item[GetLastModified::class.java]?.lastModified, propertyEtag(item[GetETag::class.java]?.rawETag))
                if (name == path) {
                    require(self == null) { "webdav_duplicate_directory_member" }
                    self = entry
                    used = item[QuotaUsedBytes::class.java]?.quotaUsedBytes
                    available = item[QuotaAvailableBytes::class.java]?.quotaAvailableBytes
                } else {
                    require(depth == 1 && name.substringBeforeLast('/', "") == path) { "webdav_unexpected_directory_member" }
                    require(names.add(name)) { "webdav_duplicate_directory_member" }
                    entries += entry
                    if (entries.size > 100_000) throw IOException("webdav_directory_limit_exceeded")
                }
            }
            require(self != null) { "webdav_directory_self_missing" }
            DavDirectory(entries, self, used, available)
        }
    }

    suspend fun download(root: String, authorization: String, path: String, output: OutputStream,
        etag: String? = null, progress: (Long, Long?) -> Unit = { _, _ -> }, maxBytes: Long = Long.MAX_VALUE,
        contentType: (String?) -> Unit = {}): Long {
        val builder = request(WebDavPaths(root), path, authorization, "GET")
        etag?.let { builder.header("If-Match", it) }
        return execute(builder.build(), cancelResources = { output.close() }) { response ->
            requireStatus(response, path, 200)
            requireIdentityEncoding(response)
            if (etag != null && response.header("ETag") != etag) throw IOException("webdav_file_changed")
            val length = response.body.contentLength().takeIf { it >= 0 }
            contentType(response.header("Content-Type"))
            response.body.byteStream().use { input -> copy(input, output, length, progress, maxBytes) }
        }
    }

    suspend fun mediaMetadata(root: String, authorization: String, path: String): DavMediaMetadata =
        execute(request(WebDavPaths(root), path, authorization, "HEAD").build()) { response ->
            requireStatus(response, path, 200)
            requireIdentityEncoding(response)
            val length = response.header("Content-Length")?.toLongOrNull()
            require(length != null && length > 0) { "webdav_media_length_unavailable" }
            val etag = response.header("ETag") ?: error("webdav_strong_etag_required")
            requireStrongEtag(etag)
            DavMediaMetadata(length, etag)
        }

    suspend fun mediaRange(root: String, authorization: String, path: String, metadata: DavMediaMetadata,
        position: Long, count: Int): ByteArray {
        require(position >= 0 && position < metadata.length && count in 1..8 * 1024 * 1024)
        val length = minOf(count.toLong(), metadata.length - position)
        val end = position + length - 1
        val request = request(WebDavPaths(root), path, authorization, "GET")
            .header("Range", "bytes=$position-$end").header("If-Match", metadata.etag).build()
        return execute(request) { response ->
            requireStatus(response, path, 206)
            requireIdentityEncoding(response)
            require(response.header("ETag") == metadata.etag &&
                response.header("Content-Range") == "bytes $position-$end/${metadata.length}") { "webdav_media_changed" }
            val output = ByteArrayOutputStream(length.toInt())
            response.body.byteStream().use { copy(it, output, length, { _, _ -> }, length) }
            output.toByteArray()
        }
    }

    suspend fun upload(root: String, authorization: String, path: String, length: Long?, input: () -> InputStream,
        existingEtag: String? = null, sent: () -> Unit = {}, progress: (Long, Long?) -> Unit = { _, _ -> }) {
        require(path.isNotEmpty() && (length == null || length >= 0)) { "webdav_upload_target_invalid" }
        val context = currentCoroutineContext()
        val activeInput = AtomicReference<InputStream?>()
        val body = object : RequestBody() {
            override fun contentType(): MediaType = BINARY
            override fun contentLength(): Long = length ?: -1L
            override fun isOneShot(): Boolean = true
            override fun writeTo(sink: BufferedSink) {
                context.ensureActive()
                input().use { source ->
                    activeInput.set(source)
                    try {
                        context.ensureActive()
                        val buffer = ByteArray(64 * 1024)
                        var count = 0L
                        while (true) {
                            context.ensureActive()
                            val read = source.read(buffer)
                            if (read < 0) break
                            if (length != null && count + read > length) throw IOException("webdav_source_changed")
                            sink.write(buffer, 0, read)
                            count += read
                            progress(count, length)
                        }
                        if (length != null && count != length) throw IOException("webdav_source_changed")
                    } finally { activeInput.compareAndSet(source, null) }
                }
            }
        }
        val builder = request(WebDavPaths(root), path, authorization, "PUT", body)
        if (existingEtag == null) builder.header("If-None-Match", "*")
        else { requireStrongEtag(existingEtag); builder.header("If-Match", existingEtag) }
        execute(builder.build(), sent, cancelResources = { activeInput.get()?.close() }) { response -> requireStatus(response, path, 200, 201, 204) }
    }

    suspend fun mkdir(root: String, authorization: String, path: String, sent: () -> Unit = {}) {
        require(path.isNotEmpty()) { "webdav_root_mutation_forbidden" }
        val builder = request(WebDavPaths(root), path, authorization, "MKCOL", EMPTY, directory = true)
        execute(builder.build(), sent) { response -> requireStatus(response, path, 201) }
    }

    suspend fun mutate(root: String, authorization: String, path: String, method: String, destination: String? = null,
        sourceEtag: String? = null, destinationEtag: String? = null, sent: () -> Unit = {}, directory: Boolean = false) {
        require(method in setOf("COPY", "MOVE", "DELETE"))
        val paths = WebDavPaths(root)
        require(path.isNotEmpty()) { "webdav_root_mutation_forbidden" }
        val builder = request(paths, path, authorization, method, if (method == "DELETE") null else EMPTY, directory)
        sourceEtag?.let { requireStrongEtag(it); builder.header("If-Match", it) }
        if (method != "DELETE") {
            val target = requireNotNull(destination)
            require(target.isNotEmpty() && target != path && !target.startsWith("$path/")) { "webdav_destination_invalid" }
            val url = paths.url(target, directory)
            builder.header("Destination", url.toString()).header("Overwrite", if (destinationEtag == null) "F" else "T")
            destinationEtag?.let { requireStrongEtag(it); builder.header("If", "<$url> ([$it])") }
        }
        execute(builder.build(), sent) { response ->
            if (response.code == 207) {
                val failures = mutableListOf<Pair<String, Int>>()
                parseMultiStatus(response, paths) { item ->
                    val name = paths.pathFromHref(item.href.toString())
                    val status = item.status?.value ?: throw IOException("webdav_multistatus_status_missing")
                    if (status !in 200..299) failures += name to status
                }
                if (failures.isNotEmpty()) throw DavMultiStatusException(failures)
            } else requireStatus(response, path, 200, 201, 204)
        }
    }

    private fun request(paths: WebDavPaths, path: String, authorization: String, method: String,
        body: RequestBody? = null, directory: Boolean = false): Request.Builder = Request.Builder()
        .url(paths.url(path, directory)).header("Authorization", authorization).header("Accept-Encoding", "identity")
        .method(method, body)

    private suspend fun <T> execute(request: Request, sent: () -> Unit = {}, cancelResources: () -> Unit = {},
        block: suspend (Response) -> T): T =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val call = client.newCall(request)
                val owner = currentCoroutineContext().job
                // Cancellation closes blocked socket reads/writes; the parent waits for IO and cleanup to finish.
                val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally {
                        call.cancel()
                        if (!owner.isActive) cancelResources()
                    }
                }
                try {
                    ensureActive()
                    sent()
                    call.execute().use { response -> block(response) }
                } finally { closer.cancelAndJoin() }
            }
        }

    private suspend fun copy(input: InputStream, output: OutputStream, length: Long?, progress: (Long, Long?) -> Unit,
        maxBytes: Long): Long {
        require(maxBytes >= 0 && (length == null || length <= maxBytes)) { "webdav_preview_size_limit" }
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            if (read > maxBytes - count) throw IOException("webdav_preview_size_limit")
            if (length != null && count + read > length) throw IOException("webdav_content_length_mismatch")
            output.write(buffer, 0, read)
            count += read
            progress(count, length)
        }
        if (length != null && count != length) throw IOException("webdav_content_length_mismatch")
        return count
    }

    private fun requireStatus(response: Response, path: String, vararg codes: Int) {
        if (response.code !in codes) throw (httpFailure?.invoke(response, path)
            ?: DavHttpException(response.code, response.request.method, path))
    }

    private fun requireIdentityEncoding(response: Response) {
        require(response.header("Content-Encoding").let { it == null || it.equals("identity", true) }) {
            "webdav_unexpected_content_encoding"
        }
    }

    private suspend fun parseMultiStatus(response: Response, paths: WebDavPaths, accept: (at.bitfire.dav4jvm.ktor.Response) -> Unit) {
        response.body.byteStream().use { input ->
            val limited = object : FilterInputStream(input) {
                var remaining = 64L * 1024 * 1024
                override fun read(): Int {
                    if (remaining <= 0) throw IOException("webdav_xml_limit_exceeded")
                    return super.read().also { if (it >= 0) remaining-- }
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (remaining <= 0) throw IOException("webdav_xml_limit_exceeded")
                    return `in`.read(b, off, minOf(len.toLong(), remaining).toInt()).also { if (it > 0) remaining -= it }
                }
            }
            val delegate = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            delegate.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            delegate.setInput(limited, null)
            val parser = ValidatingDavXmlParser(delegate, paths, response.request.url)
            while (parser.next() != XmlPullParser.START_TAG) {
                if (parser.eventType == XmlPullParser.END_DOCUMENT) throw IOException("webdav_xml_empty")
            }
            require(parser.namespace == "DAV:" && parser.name == "multistatus") { "webdav_multistatus_expected" }
            val responseParser = ResponseParser(Url(response.request.url.toString()))
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                currentCoroutineContext().ensureActive()
                if (parser.eventType == XmlPullParser.START_TAG && parser.depth == 2 &&
                    parser.namespace == "DAV:" && parser.name == "response") {
                    val item = responseParser.parseResponse(parser) ?: throw IOException("webdav_href_missing")
                    paths.pathFromHref(item.response.href.toString())
                    accept(item.response)
                }
            }
        }
    }

    companion object {
        // Some DAV servers (including WsgiDAV) omit quotes in getetag XML while their HTTP
        // headers use entity-tag syntax. Quote only an unambiguous opaque value; retain weakness
        // and malformed values so they never acquire permission for conditional replacement.
        private fun propertyEtag(raw: String?): String? {
            if (raw == null) return null
            val weak = raw.startsWith("W/")
            val value = if (weak) raw.substring(2) else raw
            if (value.isEmpty() || value.any { !etagCharacter(it) }) return raw
            return (if (weak) "W/" else "") + "\"$value\""
        }

        fun requireStrongEtag(etag: String) {
            require(strongEtag(etag)) { "webdav_strong_etag_required" }
        }

        fun strongEtag(etag: String?): Boolean = etag != null && etag.length >= 2 &&
            etag.startsWith('"') && etag.endsWith('"') &&
            etag.substring(1, etag.lastIndex).all(::etagCharacter)

        // Use only opaque characters accepted by OkHttp's standard request header API.
        private fun etagCharacter(value: Char): Boolean = value.code in 33..126 && value != '"'
        private val BINARY = "application/octet-stream".toMediaType()
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody()
        private const val PROPERTIES = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/><d:quota-used-bytes/><d:quota-available-bytes/></d:prop></d:propfind>"""
    }
}
