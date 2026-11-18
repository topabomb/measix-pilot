package net.weero.measix.pilot.data.files

import java.io.IOException
import net.weero.measix.pilot.data.enterprise.PlatformWireCodec
import net.weero.measix.pilot.data.enterprise.PlatformWorkspaceThumbnailCapabilities
import okhttp3.HttpUrl
import okhttp3.Response
import okio.ByteString.Companion.toByteString

/** Wire rules shared by the authenticated Hub and DAV transports. */
internal object MediaThumbnailProtocol {
    const val SIZE = 256
    private const val WEBP = "image/webp"
    private const val MAX_BYTES = 1024 * 1024
    private const val MAX_CAPABILITY_BYTES = 16 * 1024L
    private val spaceId = Regex("spc_[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    private val extensions = setOf("jpg", "jpeg", "png", "apng", "gif", "webp", "bmp", "mp4", "m4v", "mov", "webm", "ogv")

    fun supports(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in extensions

    fun request(endpoint: HttpUrl, path: String, sourceVersion: String): HttpUrl = endpoint.newBuilder()
        .addQueryParameter("path", path)
        .addQueryParameter("size", SIZE.toString())
        .addQueryParameter("expectedEtag", sourceVersion)
        .build()

    /** Null means the authenticated endpoint expressly declined this optional protocol. */
    fun capability(response: Response, expectedSpace: String?, allowUnsupported: Boolean): String? {
        checkProtocol(response.code == 200 && mediaType(response) == "application/json") { "capability response" }
        val space = response.headers.values("Agent-Space-Id").singleOrNull()
        checkProtocol(space != null && spaceId.matches(space) && (expectedSpace == null || expectedSpace == space)) {
            "capability space"
        }
        val raw = boundedCapabilityText(response)
        val capability = try {
            PlatformWireCodec.decode<PlatformWorkspaceThumbnailCapabilities>(raw)
        } catch (failure: IllegalArgumentException) {
            throw IOException("preview_protocol_error: invalid capability fields", failure)
        } catch (failure: IllegalStateException) {
            throw IOException("preview_protocol_error: invalid capability fields", failure)
        }
        if (!capability.supported) {
            checkProtocol(allowUnsupported && capability.sizes.isEmpty() && capability.mediaKinds.isEmpty()) {
                "unsupported capability fields"
            }
            return null
        }
        checkProtocol(capability.sizes.toSet() == setOf(128L, 256L, 512L) && capability.mediaKinds.size == 2) {
            "supported capability fields"
        }
        return space
    }

    fun boundedCapabilityText(response: Response): String {
        val source = response.body.source()
        checkProtocol(!source.request(MAX_CAPABILITY_BYTES + 1)) { "capability body exceeds 16 KiB" }
        return source.readUtf8()
    }

    fun bytes(response: Response, space: String, sourceVersion: String): ByteArray {
        val length = response.headers.values("Content-Length").singleOrNull()?.toLongOrNull()
        checkProtocol(response.code == 200 && mediaType(response) == WEBP &&
            response.headers.values("Content-Encoding").isEmpty() &&
            response.headers.values("Agent-Space-Id") == listOf(space) &&
            response.headers.values("Preview-Source-ETag") == listOf(sourceVersion) &&
            length != null && length in 1L..MAX_BYTES.toLong()) { "representation headers" }
        val source = response.body.source()
        val bytes = source.readByteArray(requireNotNull(length))
        checkProtocol(source.exhausted()) { "representation length" }
        checkProtocol(response.headers.values("ETag") == listOf("\"" + bytes.toByteString().sha256().hex() + "\"")) {
            "representation digest"
        }
        checkProtocol(validStaticWebP(bytes)) { "invalid static WebP or dimensions exceed $SIZE" }
        return bytes
    }

    fun itemFailure(response: Response, cause: IOException): IOException =
        if (response.code in setOf(404, 412, 413, 415, 422, 429)) MediaThumbnailReadException(
            response.code, (response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 60) ?: 1) * 1000, cause,
        ) else cause

    private fun mediaType(response: Response) =
        response.headers.values("Content-Type").singleOrNull()?.substringBefore(';')?.trim()?.lowercase()

    private inline fun checkProtocol(valid: Boolean, detail: () -> String) {
        if (!valid) throw IOException("preview_protocol_error: ${detail()}")
    }

    // Validate the declared canvas and actual frame before handing bytes to a pixel decoder.
    private fun validStaticWebP(bytes: ByteArray): Boolean {
        fun value(at: Int) = bytes[at].toInt() and 0xff
        fun little(at: Int, length: Int) = (0 until length).sumOf { value(at + it).toLong() shl (8 * it) }
        fun id(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        fun dimensions(width: Long, height: Long) = width in 1L..SIZE.toLong() && height in 1L..SIZE.toLong()

        if (bytes.size < 20 || id(0) != "RIFF" || id(8) != "WEBP" || little(4, 4) + 8 != bytes.size.toLong()) return false
        var offset = 12
        var canvas: Pair<Long, Long>? = null
        var frame: Pair<Long, Long>? = null
        while (offset <= bytes.size - 8) {
            val length = little(offset + 4, 4)
            val data = offset + 8
            val next = data + length + length % 2
            if (next > bytes.size) return false
            val chunk = id(offset)
            val actual = when (chunk) {
                "VP8X" -> {
                    if (offset != 12 || length != 10L || value(data) and 0x02 != 0) return false
                    canvas = (little(data + 4, 3) + 1) to (little(data + 7, 3) + 1)
                    if (!dimensions(canvas.first, canvas.second)) return false
                    null
                }
                "ANIM", "ANMF" -> return false
                "VP8 " -> {
                    if (length < 10 || value(data) and 1 != 0 || little(data + 3, 3) != 0x2a019dL) return false
                    (little(data + 6, 2) and 0x3fffL) to (little(data + 8, 2) and 0x3fffL)
                }
                "VP8L" -> {
                    if (length < 5 || value(data) != 0x2f) return false
                    val bits = little(data + 1, 4)
                    ((bits and 0x3fff) + 1) to ((bits shr 14 and 0x3fff) + 1)
                }
                else -> null
            }
            if (actual != null) {
                if (frame != null || !dimensions(actual.first, actual.second)) return false
                frame = actual
            }
            offset = next.toInt()
        }
        return offset == bytes.size && frame != null && (canvas == null || canvas == frame)
    }
}

internal class MediaThumbnailReadException(val status: Int, val retryAfterMillis: Long, cause: IOException) :
    IOException(cause.message, cause)

internal class MediaThumbnailUnavailableException(cause: IOException) : IOException(cause.message, cause)
