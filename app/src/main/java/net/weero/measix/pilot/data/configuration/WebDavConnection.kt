package net.weero.measix.pilot.data.configuration

import java.net.URI
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** A personal file-manager connection; backup destinations have an independent lifecycle. */
@Serializable
data class WebDavConnection(
    val id: Uuid = Uuid.random(),
    val name: String,
    val rootUrl: String,
    val username: String = "",
    val password: String = "",
    /** Settings assigns a new identity on creation or a change to access fields. */
    val accessRevision: Uuid = Uuid.NIL,
) {
    fun validated(): WebDavConnection {
        require(name.isNotBlank()) { "webdav_name_required" }
        val url = try { URI(rootUrl.trim()) } catch (_: Exception) {
            throw IllegalArgumentException("webdav_root_url_invalid")
        }
        require(url.scheme in setOf("http", "https") && !url.host.isNullOrBlank() &&
            url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null &&
            url.port in -1..65535 && url.port != 0) { "webdav_root_url_invalid" }
        require(!username.contains(':') && username.none { it == '\r' || it == '\n' }) {
            "webdav_username_invalid"
        }
        require(url.path.orEmpty().split('/').none { it == "." || it == ".." } &&
            !Regex("%2f|%5c", RegexOption.IGNORE_CASE).containsMatchIn(url.rawPath.orEmpty()) &&
            url.path.orEmpty().none { it == '\\' || it.code < 32 || it.code == 127 }) {
            "webdav_root_url_invalid"
        }
        return copy(name = name.trim(), rootUrl = url.toASCIIString().trimEnd('/') + "/")
    }

    // Credentials must never enter generated data-class diagnostics.
    override fun toString(): String = "WebDavConnection(id=$id)"
}

internal sealed interface WebDavConnectionChange {
    data class Save(val connection: WebDavConnection, val expected: WebDavConnection?) : WebDavConnectionChange
    data class Delete(val id: Uuid) : WebDavConnectionChange
    data class Select(val id: Uuid?) : WebDavConnectionChange
}
