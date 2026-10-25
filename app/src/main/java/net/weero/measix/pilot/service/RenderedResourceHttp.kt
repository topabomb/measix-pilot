package net.weero.measix.pilot.service

import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import net.weero.measix.pilot.data.ai.attachments.ImageMime
import net.weero.measix.pilot.data.ai.attachments.SafeRemoteMediaFetcher

/** Public static resources use no workspace credentials, cookies or browser network state. */
internal suspend fun readPublicRenderedResource(
    reference: String,
    maxBytes: Long,
    inlineOrigin: String,
    lookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    transport: net.weero.measix.pilot.data.ai.attachments.RemoteHttpTransport =
        net.weero.measix.pilot.data.ai.attachments.RemoteHttpTransport { url, addresses ->
            SafeRemoteMediaFetcher.defaultTransport(url, addresses, 15_000, 15_000, maxBytes.toInt())
        },
): Pair<String, ByteArray> {
    val policy = SafeRemoteMediaFetcher()
    var url = requireNotNull(SafeRemoteMediaFetcher.parseHttpUrl(reference)) { "rendered_resource_url_invalid" }
    repeat(6) { hop ->
        require(url.userInfo == null && policy.inspectHost(url.host) == null) { "rendered_resource_url_denied" }
        val addresses = runInterruptible(Dispatchers.IO) { lookup(url.host) }
        require(addresses.isNotEmpty() && addresses.none { policy.isBlockedAddress(it) }) { "rendered_resource_address_denied" }
        val response = transport.execute(url, addresses)
        fun header(name: String) = response.headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
        if (response.code in 300..399) {
            check(hop < 5) { "rendered_resource_redirect_limit" }
            val next = requireNotNull(SafeRemoteMediaFetcher.resolveRedirect(url, header("Location").orEmpty())) { "rendered_resource_redirect_invalid" }
            require(url.protocol != "https" || next.protocol == "https") { "rendered_resource_redirect_downgrade" }
            url = next
        } else {
            check(response.code == 200) { "rendered_resource_http_error: GET $reference (HTTP ${response.code})" }
            val mime = header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                ?: renderedResourceMime(url.path, response.body)
            require(mime == "text/css" || mime.startsWith("image/") || mime.startsWith("font/") ||
                mime in setOf("application/font-woff", "application/vnd.ms-fontobject", "application/x-font-ttf")) {
                "rendered_resource_type_denied: $mime"
            }
            val bytes = if (mime == "text/css") {
                require(response.body.size <= 1024 * 1024) { "rendered_stylesheet_size_limit" }
                // Absolutize against the final stylesheet URL, including after redirects.
                absoluteStylesheetUrls(response.body.toString(Charsets.UTF_8), url.toURI(), inlineOrigin).toByteArray()
            } else response.body
            return mime to bytes
        }
    }
    error("rendered_resource_redirect_limit")
}

internal fun renderedResourceMime(path: String, bytes: ByteArray): String = when (path.substringBefore('?').substringAfterLast('.', "").lowercase()) {
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
    "ico" -> "image/x-icon"
    else -> ImageMime.sniff(bytes) ?: "application/octet-stream"
}

/** CSS comments and strings are tokens too, so text resembling url() inside them is untouched. */
internal fun absoluteStylesheetUrls(css: String, base: URI, inlineOrigin: String? = null): String {
    fun absolute(raw: String): String {
        val decoded = raw.trim().replace(Regex("""\\([0-9a-fA-F]{1,6})\s?|\\(.)""")) { match ->
            if (match.groupValues[1].isNotEmpty()) String(Character.toChars(match.groupValues[1].toInt(16).coerceIn(1, 0x10ffff)))
            else match.groupValues[2]
        }
        val resolved = if (decoded.startsWith("data:", true) && inlineOrigin != null) renderedInlineUrl(inlineOrigin, decoded)
            else if (decoded.startsWith('#') || decoded.startsWith("data:", true)) decoded
            else try { base.resolve(decoded.replace(" ", "%20")).toASCIIString() }
            catch (_: IllegalArgumentException) { "about:invalid" }
        return resolved.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "")
    }
    // Scan once: recursive regex alternatives can overflow the stack on a large data URL.
    fun quotedEnd(start: Int): Int {
        var end = start + 1
        while (end < css.length) {
            if (css[end] == '\\') end += 2
            else if (css[end++] == css[start]) return end
        }
        return css.length
    }
    val result = StringBuilder(css.length)
    var at = 0
    while (at < css.length) {
        when {
            css.startsWith("/*", at) -> {
                val end = css.indexOf("*/", at + 2).let { if (it < 0) css.length else it + 2 }
                result.append(css, at, end); at = end
            }
            css[at] == '\'' || css[at] == '"' -> {
                val end = quotedEnd(at)
                result.append(css, at, end); at = end
            }
            css.regionMatches(at, "url(", 0, 4, true) &&
                (at == 0 || !(css[at - 1].isLetterOrDigit() || css[at - 1] in "_-")) -> {
                var start = at + 4
                while (start < css.length && css[start].isWhitespace()) start++
                var end = start
                val quoted = start < css.length && css[start] in "\"'"
                if (quoted) end = quotedEnd(start)
                else while (end < css.length && css[end] != ')') {
                    if (css[end] == '\\' && end + 1 < css.length) end += 2 else end++
                }
                var close = end
                while (close < css.length && css[close].isWhitespace()) close++
                if (close < css.length && css[close] == ')' && (!quoted || end > start + 1)) {
                    val raw = if (quoted) css.substring(start + 1, end - 1) else css.substring(start, end)
                    result.append("url(\"").append(absolute(raw)).append("\")")
                    at = close + 1
                } else if (close >= css.length) {
                    result.append("url(\"about:invalid\")"); at = css.length
                } else { result.append(css[at]); at++ }
            }
            css.regionMatches(at, "@import", 0, 7, true) && at + 7 < css.length &&
                (css[at + 7].isWhitespace() || css[at + 7] in "\"'/") -> {
                var start = at + 7
                while (start < css.length) {
                    if (css[start].isWhitespace()) start++
                    else if (css.startsWith("/*", start)) start = css.indexOf("*/", start + 2).let { if (it < 0) css.length else it + 2 }
                    else break
                }
                if (start < css.length && css[start] in "\"'") {
                    val end = quotedEnd(start)
                    result.append(css, at, start).append('"').append(absolute(css.substring(start + 1, (end - 1).coerceAtLeast(start + 1)))).append('"')
                    at = end
                } else { result.append(css, at, start); at = start }
            }
            else -> { result.append(css[at]); at++ }
        }
    }
    return result.toString()
}

internal const val RENDERED_INLINE_PATH = "/_preview_inline"
internal fun renderedInlineUrl(origin: String, data: String): String =
    "$origin$RENDERED_INLINE_PATH?url=${java.net.URLEncoder.encode(data, Charsets.UTF_8.name())}"

internal fun decodeRenderedInlineResource(reference: String, limit: Long): Pair<String, ByteArray> {
    require(reference.length <= limit * 3 + 256) { "rendered_inline_size_limit" }
    val comma = reference.indexOf(',')
    require(comma in 5..256) { "rendered_inline_header_invalid" }
    val header = reference.substring(5, comma)
    val mime = header.substringBefore(';').lowercase()
    require(mime.startsWith("image/") || mime.startsWith("font/")) { "rendered_inline_type_denied" }
    val payload = reference.substring(comma + 1)
    val bytes = if (header.endsWith(";base64", true)) java.util.Base64.getDecoder().decode(payload)
    else java.io.ByteArrayOutputStream().use { out ->
        var at = 0
        while (at < payload.length) {
            if (payload[at] == '%') {
                require(at + 2 < payload.length) { "rendered_inline_escape_invalid" }
                val value = payload.substring(at + 1, at + 3).toIntOrNull(16)
                require(value != null) { "rendered_inline_escape_invalid" }
                check(out.size() < limit) { "rendered_inline_size_limit" }
                out.write(value); at += 3
            } else {
                val end = payload.indexOf('%', at).let { if (it < 0) payload.length else it }
                val literal = payload.substring(at, end).toByteArray(Charsets.UTF_8)
                check(literal.size <= limit - out.size()) { "rendered_inline_size_limit" }
                out.write(literal); at = end
            }
        }
        out.toByteArray()
    }
    check(bytes.size <= limit) { "rendered_inline_size_limit" }
    return mime to bytes
}
