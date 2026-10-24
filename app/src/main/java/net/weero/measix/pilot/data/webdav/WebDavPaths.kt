package net.weero.measix.pilot.data.webdav

import java.net.URI
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Logical paths are decoded names. Encoding happens exactly once, at the HTTP boundary. */
internal class WebDavPaths(rootUrl: String) {
    val root: HttpUrl = rootUrl.toHttpUrl()
    private val rootSegments = root.pathSegments.dropLastWhile { it.isEmpty() }

    init {
        require(root.username.isEmpty() && root.password.isEmpty() && root.query == null && root.fragment == null &&
            root.encodedPath.endsWith('/')) { "webdav_root_url_invalid" }
    }

    fun logical(path: String): String {
        require(!path.startsWith('/') && !path.endsWith('/') &&
            path.none { it == '\\' || it.code < 32 || it.code == 127 }) { "webdav_path_invalid" }
        if (path.isEmpty()) return path
        require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "webdav_path_invalid" }
        return path
    }

    fun url(path: String, directory: Boolean = false): HttpUrl {
        logical(path)
        if (path.isEmpty()) return root
        val builder = root.newBuilder()
        path.split('/').forEach(builder::addPathSegment)
        if (directory) builder.addPathSegment("")
        return builder.build()
    }

    fun child(parent: String, name: String): String {
        logical(parent)
        require(name.isNotEmpty() && '/' !in name) { "webdav_name_invalid" }
        return logical(if (parent.isEmpty()) name else "$parent/$name")
    }

    fun pathFromHref(href: String, relativeTo: HttpUrl = root): String {
        val parsed = try { URI(href) } catch (_: Exception) { error("webdav_href_invalid") }
        require(parsed.rawUserInfo == null && parsed.rawQuery == null && parsed.rawFragment == null &&
            !Regex("%2f|%5c", RegexOption.IGNORE_CASE).containsMatchIn(parsed.rawPath.orEmpty()) &&
            parsed.path.orEmpty().split('/').none { it == "." || it == ".." }) { "webdav_href_invalid" }
        val url = requireNotNull(relativeTo.resolve(href)) { "webdav_href_invalid" }
        require(url.scheme == root.scheme && url.host == root.host && url.port == root.port &&
            url.pathSegments.take(rootSegments.size) == rootSegments) { "webdav_href_outside_root" }
        return logical(url.pathSegments.drop(rootSegments.size).dropLastWhile { it.isEmpty() }.joinToString("/"))
    }
}
