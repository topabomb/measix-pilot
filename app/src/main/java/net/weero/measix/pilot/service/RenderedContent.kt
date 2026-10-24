package net.weero.measix.pilot.service

/** Query-issued provenance bound to one selected message and its original page lease. */
class ConversationMessageResourceSource internal constructor(
    internal val view: ConversationViewLease,
    internal val workspace: net.weero.measix.pilot.data.model.WorkspaceContextOrigin?,
    private val verify: suspend () -> Unit,
) {
    internal val identity = kotlin.uuid.Uuid.random()
    internal val documentUrlPath: String? = workspace?.let {
        java.net.URI(null, null, it.cwd.trimEnd('/') + "/_message", null).toASCIIString()
    }
    internal suspend fun requireAccess() = verify()
}

/** The rendered document borrows its original source; it never reconstructs permission from a route or URL. */
sealed interface RenderedContentSource {
    data class WorkspaceFile(val file: RenderedFileSource) : RenderedContentSource
    data class Conversation(val view: ConversationViewLease, val message: ConversationMessageResourceSource? = null) : RenderedContentSource {
        init { require(message == null || message.view === view) { "message_resource_view_mismatch" } }
    }
    data class RealmConfiguration(val view: ConversationViewLease) : RenderedContentSource
    data object UserConfiguration : RenderedContentSource
    data object Static : RenderedContentSource
}

data class RenderedContent(val source: RenderedContentSource, val html: String)

/** Frozen at the user's click, before the system document picker can suspend the page. */
data class TextDocumentExport(
    val source: RenderedContentSource,
    val text: String,
    val fileName: String,
    val mimeType: String,
)

internal const val RENDERED_FILE_PATH = "/_managed_file"

/** Only the document's own virtual origin may interpret these paths. */
internal fun renderedLocalFileUrl(uri: android.net.Uri): String? = when {
    uri.path == RENDERED_FILE_PATH -> uri.getQueryParameter("url")?.takeIf { it.startsWith("file:", ignoreCase = true) }
    uri.path.orEmpty().startsWith("/upload/") && uri.query == null -> uri.path
    else -> null
}
