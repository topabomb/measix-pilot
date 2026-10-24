package net.weero.measix.pilot.service

import java.net.URI
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.merge

internal const val MAX_RENDERED_RESOURCE_BYTES = 24L * 1024 * 1024

/** A borrowed document identity; resource reads remain with its original file service. */
class RenderedFileSource internal constructor(
    internal val documentPath: String,
    changes: Flow<*>,
    private val verify: suspend () -> Unit,
    private val validatePath: (String) -> String,
    private val read: suspend (String, Long) -> ByteArray,
) {
    private val closed = MutableStateFlow(false)
    internal val identity = UUID.randomUUID().toString()
    internal val accessChanges: Flow<*> = merge(changes, closed)
    internal val documentUrlPath = URI(null, null, "/$documentPath", null).toASCIIString()

    internal fun close() { closed.value = true }

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
        val path = resourcePath(reference)
        requireAccess()
        val bytes = read(path, maxBytes)
        requireAccess()
        check(bytes.size.toLong() <= maxBytes) { "rendered_resource_size_limit" }
        val mime = when (path.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html"
            "svg" -> "image/svg+xml"
            "css" -> "text/css"
            "js", "mjs" -> "text/javascript"
            "json" -> "application/json"
            "txt", "md" -> "text/plain"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            else -> net.weero.measix.pilot.data.ai.attachments.ImageMime.sniff(bytes) ?: "application/octet-stream"
        }
        return mime to bytes
    }

    internal fun image(reference: String): ImageSource {
        val path = resourcePath(reference)
        return ImageSource(cacheIdentity = "rendered-file:$identity:$path", origin = ImageOrigin.LOCAL,
            displayName = path.substringAfterLast('/'),
            mimeHint = if (path.endsWith(".svg", true)) "image/svg+xml" else null,
            verifyAccess = ::requireAccess,
            readPayload = { readResource(reference, 4L * 1024 * 1024).second.also {
                net.weero.measix.pilot.service.workspace.validateWorkspaceImage(it, path)
            } })
    }
}
