package net.weero.measix.pilot.service.files

import java.io.OutputStream
import net.weero.measix.pilot.data.enterprise.WorkspaceFileRules
import net.weero.measix.pilot.data.enterprise.WorkspaceText

internal data class BrowserFile(
    val path: String, val directory: Boolean, val size: Long?, val modifiedAt: String?, val etag: String?,
    val versioned: Boolean = WorkspaceFileRules.strongEtag(etag),
) {
    val name: String get() = path.substringAfterLast('/')
}
internal data class BrowserDirectory(val path: String, val files: List<BrowserFile>, val usedBytes: Long?, val availableBytes: Long?)
internal enum class BrowserOutcome { SUCCEEDED, FAILED, PARTIAL, UNKNOWN, NOT_STARTED, CANCELLED }
internal data class BrowserOperationResult(val path: String, val outcome: BrowserOutcome, val diagnostic: String? = null)
internal enum class BrowserFileAction { DELETE, MOVE, COPY }

internal data class BrowserTextDocument(val file: BrowserFile, val content: WorkspaceText)
internal interface BrowserExportDestination {
    val description: String
    fun open(): OutputStream
    fun delete(): Boolean
}
