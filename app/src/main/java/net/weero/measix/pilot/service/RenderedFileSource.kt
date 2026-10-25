package net.weero.measix.pilot.service

import java.net.URI
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal const val MAX_RENDERED_RESOURCE_BYTES = 8L * 1024 * 1024

internal class RenderedResourceException(reference: String, cause: Exception) :
    java.io.IOException("rendered_resource_unavailable: $reference", cause)

/** A borrowed document identity; resource reads remain with its original file service. */
class RenderedFileSource internal constructor(
    internal val documentPath: String,
    changes: Flow<*>,
    private val verify: suspend () -> Unit,
    private val validatePath: (String) -> String,
    private val read: suspend (String, Long) -> ByteArray,
    private val original: RenderedFileSource? = null,
) {
    private val closed = MutableStateFlow(false)
    private val resources = RenderedResourceCache()
    internal val resourceFailures = MutableStateFlow<List<RenderedResourceException>>(emptyList())
    internal val identity = UUID.randomUUID().toString()
    internal val resourceOrigin = "https://measix-preview-$identity.invalid"
    internal val accessChanges: Flow<*> = merge(changes, closed)
    internal val documentUrlPath = URI(null, null, "/$documentPath", null).toASCIIString()

    internal fun close() { closed.value = true; resources.close() }

    /** Each visible viewer borrows the original authority and owns only its temporary reads. */
    internal fun newPreview(): RenderedFileSource {
        val root = original ?: this
        return RenderedFileSource(documentPath, root.accessChanges, root::requireAccess, validatePath, read, root)
    }

    internal suspend fun requireAccess() {
        currentCoroutineContext().ensureActive()
        check(!closed.value) { "rendered_file_closed" }
        verify()
        currentCoroutineContext().ensureActive()
        check(!closed.value) { "rendered_file_closed" }
    }

    /** URLs are decoded exactly once, then validated by the original root's path rules. */
    internal fun resourcePath(reference: String): String {
        val uri = try { URI(reference) }
        catch (error: java.net.URISyntaxException) { throw IllegalArgumentException("rendered_file_reference_invalid", error) }
        require(uri.scheme == null && uri.rawAuthority == null &&
            !Regex("%2f|%5c|%00", RegexOption.IGNORE_CASE).containsMatchIn(uri.rawPath.orEmpty())) {
            "rendered_file_reference_invalid"
        }
        val rawPath = uri.path.orEmpty()
        require(rawPath.isNotEmpty() && Charsets.UTF_8.newEncoder().canEncode(rawPath) &&
            rawPath.none { it == '\\' || it.code < 32 || it == '\uFFFD' }) {
            "rendered_file_reference_invalid"
        }
        val segments = if (rawPath.startsWith('/')) mutableListOf()
            else documentPath.substringBeforeLast('/', "").split('/').filter(String::isNotEmpty).toMutableList()
        rawPath.trimStart('/').split('/').forEach { segment ->
            when (segment) {
                ".", "" -> Unit
                ".." -> { require(segments.isNotEmpty()) { "rendered_file_outside_root" }; segments.removeAt(segments.lastIndex) }
                else -> segments += segment
            }
        }
        require(segments.isNotEmpty()) { "rendered_file_reference_invalid" }
        return validatePath(segments.joinToString("/"))
    }

    internal suspend fun readResource(reference: String, maxBytes: Long = MAX_RENDERED_RESOURCE_BYTES): Pair<String, ByteArray> {
        val attempt = resources
        try {
            val external = reference.startsWith("https://", true) || reference.startsWith("http://", true)
            val inline = reference.startsWith("data:", true)
            val path = if (external || inline) reference.substringBefore('#') else resourcePath(reference)
            if (external) require(path.length <= 8192) { "rendered_resource_url_length_limit" }
            val key = if (inline) "inline:" + java.security.MessageDigest.getInstance("SHA-256")
                .digest(path.toByteArray()).joinToString("") { "%02x".format(it) } else path
            val limit = minOf(maxBytes, MAX_RENDERED_RESOURCE_BYTES)
            // Includes queueing, DNS, redirects and owner IO; timeout is a retryable resource failure.
            val result = withTimeoutOrNull(30_000) {
                attempt.read(key, ::requireAccess, limit) {
                    when {
                        external -> readPublicRenderedResource(path, limit, resourceOrigin)
                        inline -> {
                            decodeRenderedInlineResource(path, limit)
                        }
                        else -> read(path, limit).let { bytes ->
                            val mime = renderedResourceMime(path, bytes)
                            if (mime == "text/css") {
                                check(bytes.size <= 1024 * 1024) { "rendered_stylesheet_size_limit" }
                                mime to absoluteStylesheetUrls(bytes.toString(Charsets.UTF_8), URI(null, null, "/$path", null), resourceOrigin).toByteArray()
                            } else mime to bytes
                        }
                    }.also { (mime, bytes) ->
                        check(bytes.size <= limit) { "rendered_resource_size_limit: $limit" }
                        val font = mime.startsWith("font/") || mime in setOf("application/font-woff", "application/vnd.ms-fontobject", "application/x-font-ttf")
                        require(mime == "text/css" || mime.startsWith("image/") || font) { "rendered_resource_type_denied: $mime" }
                        if (mime == "text/css") check(bytes.size <= 1024 * 1024) { "rendered_stylesheet_size_limit" }
                        if (font) check(bytes.size <= 2 * 1024 * 1024) { "rendered_font_size_limit" }
                        if (mime.startsWith("image/")) {
                            net.weero.measix.pilot.service.workspace.validateWorkspaceImage(bytes, if (mime == "image/svg+xml") "image.svg" else "image")
                        }
                    }
                }
            } ?: throw java.net.SocketTimeoutException("rendered_resource_timeout: 30000 ms")
            check(result.second.size <= limit) { "rendered_resource_size_limit: $limit" }
            return result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            requireAccess()
            val failure = RenderedResourceException(if (reference.startsWith("data:", true)) "inline image" else reference, error)
            if (resourceFailures.value.none { it.message == failure.message })
                net.weero.measix.pilot.utils.logDiagnosticFailure("RenderedFile", "Resource read failed", failure)
            resourceFailures.update { old ->
                if (old.any { it.message == failure.message }) old else (old + failure).takeLast(16)
            }
            throw failure
        }
    }

    internal fun image(reference: String): ImageSource {
        val path = reference
        return ImageSource(cacheIdentity = "rendered-file:$identity:$path", origin = ImageOrigin.LOCAL,
            displayName = path.substringBefore('?').substringAfterLast('/').takeUnless { reference.startsWith("data:", true) },
            mimeHint = if (path.substringBefore('?').endsWith(".svg", true)) "image/svg+xml" else null,
            verifyAccess = ::requireAccess,
            memoryCacheEnabled = false,
            readPayload = { readResource(reference).second })
    }
}
