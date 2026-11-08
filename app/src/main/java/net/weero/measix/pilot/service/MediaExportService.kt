package net.weero.measix.pilot.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.common.android.appTempFolder
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.export.ModeInjectionSerializer
import net.weero.measix.pilot.data.model.PromptInjection
import net.weero.measix.pilot.utils.ImageExportResult
import net.weero.measix.pilot.utils.exportImageBytes
import net.weero.measix.pilot.utils.getActivity
import net.weero.measix.pilot.utils.logDiagnosticFailure
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal const val IMAGE_SAVE_PERMISSION_REQUIRED = "permission_required"

sealed interface PreparedExport {
    val fileName: String
    val mimeType: String
}

/** Only wrap a URI returned by this operation's CreateDocument launcher. */
@JvmInline
internal value class CreatedExportDocument(val uri: Uri)

/** UI-facing export port for copying media outside app storage. */
class MediaExportService(
    private val files: FileManagementApplicationService,
    private val cleanupScope: CoroutineScope,
) {
    private sealed interface Claim {
        data object Released : Claim
        data object Output : Claim
        data class Document(val uri: Uri) : Claim
    }

    private class Request(
        val owner: MediaExportService,
        override val fileName: String,
        override val mimeType: String,
        val source: RenderedContentSource.Conversation? = null,
        val messages: List<ConversationMessageResourceSource> = emptyList(),
        val text: String? = null,
        val file: File? = null,
    ) : PreparedExport {
        // Either the output coroutine or release() takes the single right to clean this payload.
        val claim = AtomicReference<Claim?>()
        fun deleteCandidate() {
            file?.let { check(!it.exists() || it.delete()) { "Unable to remove unpublished export: ${it.name}" } }
        }

        suspend fun writeTo(output: OutputStream) {
            val operation = currentCoroutineContext()
            operation.ensureActive()
            if (file == null) {
                output.write(requireNotNull(text).toByteArray(Charsets.UTF_8))
                return
            }
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    operation.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    suspend fun prepareModeInjectionExport(snapshot: PromptInjection.ModeInjection): PreparedExport =
        withContext(Dispatchers.Default) {
            Request(
                owner = this@MediaExportService,
                fileName = safeExportName(ModeInjectionSerializer.getExportFileName(snapshot), "export.json"),
                mimeType = "application/json",
                text = ModeInjectionSerializer.exportToJson(snapshot),
            )
        }

    internal suspend fun prepareConversationMarkdown(
        source: ConversationViewLease,
        text: String,
        fileName: String,
    ): PreparedExport {
        val origin = RenderedContentSource.Conversation(source)
        files.requireContentAccess(origin)
        return Request(this, safeExportName(fileName, "chat.md"), "text/markdown", origin, text = text)
    }

    internal suspend fun prepareConversationImage(
        context: Context,
        source: ConversationViewLease,
        messages: List<ConversationMessageResourceSource>,
        bitmap: Bitmap,
        fileName: String,
    ): PreparedExport {
        val origin = RenderedContentSource.Conversation(source)
        require(messages.all { it.view === source }) { "message_resource_view_mismatch" }
        var candidate: File? = null
        try {
            val request = withContext(Dispatchers.IO) {
                val file = File(context.appTempFolder, "${UUID.randomUUID()}.png")
                candidate = file
                val pending = Request(this@MediaExportService, safeExportName(fileName, "chat.png"), "image/png",
                    origin, messages.toList(), file = file)
                requireAccess(pending)
                file.outputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 90, output)) { "Unable to encode image export" }
                }
                requireAccess(pending)
                pending
            }
            candidate = null
            return request
        } catch (error: Throwable) {
            compensate(error) { candidate?.let { check(!it.exists() || it.delete()) { "Unable to remove unpublished export" } } }
            throw error
        }
    }

    fun release(export: PreparedExport) {
        val request = export as Request
        check(request.owner === this)
        if (!request.claim.compareAndSet(null, Claim.Released) || request.file == null) return
        cleanupScope.launch(Dispatchers.IO) {
            try {
                request.deleteCandidate()
            } catch (error: Exception) {
                logDiagnosticFailure("MediaExport", "Unable to release export", error)
            }
        }
    }

    internal suspend fun savePreparedDocument(context: Context, target: CreatedExportDocument, export: PreparedExport?) {
        val request = export as Request?
        val claim = Claim.Document(target.uri)
        val acquired = request != null && request.owner === this && request.claim.compareAndSet(null, claim)
        // A duplicate callback does not own the destination that the first invocation acquired.
        check(acquired || request?.claim?.get() != claim) { "document_result_already_claimed" }
        writeDocument(context, target.uri) {
            check(acquired) { "document_request_expired" }
            val owned = requireNotNull(request)
            finishClaim(owned) {
                withContext(Dispatchers.IO) {
                    requireAccess(owned)
                    context.writeDocumentStream(target.uri) { writeAuthorized(owned, it) }
                    requireAccess(owned)
                }
            }
        }
    }

    suspend fun sharePrepared(context: Context, export: PreparedExport) = consume(export) { request ->
        publishFile(context, request.fileName, writePayload = { output ->
            writeAuthorized(request, output)
            request.mimeType
        }) { uri, mime ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            requireAccess(request)
            val source = request.source
            val title = source?.let { context.getString(R.string.chat_page_export_share_via) }
            val chooser = Intent.createChooser(intent, title)
            if (source == null) context.startActivity(chooser)
            else files.withContentAccess(source) { context.startActivity(chooser) }
        }
    }

    suspend fun savePreparedImage(context: Context, export: PreparedExport) = consume(export) { request ->
        require(request.mimeType == "image/png")
        withContext(Dispatchers.IO) {
            requireAccess(request)
            val activity = requireNotNull(context.getActivity()) { "Activity not found" }
            val bytes = requireNotNull(request.file).readBytes()
            requireExportSuccess(context.exportImageBytes(activity, bytes, request.mimeType, request.fileName) {
                requireAccess(request)
            })
        }
    }

    private suspend fun requireAccess(request: Request) {
        currentCoroutineContext().ensureActive()
        request.source?.let { files.requireContentAccess(it) }
        request.messages.forEach { it.requireAccess() }
    }

    private suspend fun writeAuthorized(request: Request, output: OutputStream) {
        requireAccess(request)
        // Destination I/O can block. Recheck the original lease after it returns before publishing.
        request.writeTo(output)
        requireAccess(request)
    }

    private suspend fun <T> consume(export: PreparedExport, action: suspend (Request) -> T): T {
        val request = export as Request
        check(request.owner === this && request.claim.compareAndSet(null, Claim.Output)) { "document_request_expired" }
        return finishClaim(request) { action(request) }
    }

    private suspend fun <T> finishClaim(request: Request, action: suspend () -> T): T {
        var failure: Throwable? = null
        try {
            currentCoroutineContext().ensureActive()
            return action()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (request.file != null) compensate(failure, request::deleteCandidate)
        }
    }

    private suspend fun compensate(failure: Throwable?, cleanup: () -> Unit) {
        try {
            withContext(NonCancellable + Dispatchers.IO) { cleanup() }
        } catch (error: Throwable) {
            if (failure == null) throw error
            if (error !== failure) failure.addSuppressed(error)
            if (failure is CancellationException) {
                logDiagnosticFailure("MediaExport", "Unable to clean cancelled export", failure)
            }
        }
    }

    suspend fun saveImage(context: Context, image: ImageSource): String = withContext(Dispatchers.IO) {
        val activity = requireNotNull(context.getActivity()) { "Activity not found" }
        val bytes = image.readBytes()
        val mime = requireNotNull(net.weero.measix.pilot.data.ai.attachments.ImageMime.sniff(bytes)) { "Invalid image format" }
        val extension = when (mime) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heic", "image/heif" -> "heic"
            else -> "png"
        }
        val fileName = "MeasixPilot_${java.util.UUID.randomUUID()}.$extension"
        requireExportSuccess(context.exportImageBytes(activity, bytes, mime, fileName, image::requireAccess))
        fileName
    }

    suspend fun openContentLink(context: Context, source: RenderedContentSource, url: String) {
        val uri = android.net.Uri.parse(url)
        if (uri.host.equals("measix.local", ignoreCase = true)) {
            require(uri.scheme == "https" && uri.port == -1) { "content_link_unavailable" }
            val local = requireNotNull(renderedLocalFileUrl(uri)) { "content_link_unavailable" }
            openAttachment(context, requireNotNull(files.resolveContentAttachment(source, local)) { "attachment_unavailable" })
            return
        }
        if (uri.scheme?.lowercase() in setOf("http", "https", "mailto", "tel")) {
            withContext(Dispatchers.Main.immediate) {
                files.withContentAccess(source) { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
            }
        } else {
            openAttachment(context, requireNotNull(files.resolveContentAttachment(source, url)) { "attachment_unavailable" })
        }
    }

    suspend fun saveTextDocument(context: Context, uri: Uri, request: TextDocumentExport?) {
        writeDocument(context, uri) {
            requireNotNull(request) { "document_request_unavailable" }
            withContext(Dispatchers.IO) {
                files.requireContentAccess(request.source)
                context.writeDocumentStream(uri) { it.write(request.text.toByteArray(Charsets.UTF_8)) }
                files.requireContentAccess(request.source)
            }
        }
    }

    private inline fun Context.writeDocumentStream(uri: Uri, write: (OutputStream) -> Unit) {
        val output = requireNotNull(contentResolver.openOutputStream(uri, "wt")) { "document_unavailable" }
        output.use {
            write(it)
            it.flush()
        }
    }

    private suspend fun writeDocument(context: Context, uri: Uri, write: suspend () -> Unit) {
        try {
            write()
        } catch (error: Throwable) {
            compensate(error) {
                check(DocumentsContract.deleteDocument(context.contentResolver, uri)) { "document_cleanup_failed" }
            }
            throw error
        }
    }

    suspend fun openAttachment(context: Context, preview: AttachmentPreview) {
        val target = requireNotNull(preview.fileTarget) { "attachment_unavailable" }
        publishFile(context, target.displayName ?: "attachment", writePayload = { output ->
            files.copyAttachmentTo(preview, output)
        }) { uri, mime ->
            files.withAttachmentAccess(preview) {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                // Launch directly so missing handlers fail before ownership is handed off.
                context.startActivity(intent)
            }
        }
    }

    private suspend fun publishFile(
        context: Context,
        fileName: String,
        writePayload: suspend (java.io.OutputStream) -> String,
        deliver: suspend (android.net.Uri, String) -> Unit,
    ) {
        var candidate: java.io.File? = null
        var shared = false
        var failure: Throwable? = null
        try {
            val displayName = safeExportName(fileName, "attachment")
            val extension = displayName.substringAfterLast('.', "").takeIf {
                it.length in 1..16 && it.all(Char::isLetterOrDigit)
            }?.let { ".$it" }.orEmpty()
            val (file, mimeType) = withContext(Dispatchers.IO) {
                val file = File(context.appTempFolder, "${UUID.randomUUID()}$extension")
                candidate = file
                file to file.outputStream().use { output -> writePayload(output) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file, displayName)
            withContext(Dispatchers.Main.immediate) {
                deliver(uri, mimeType)
                shared = true
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (!shared) candidate?.let { file ->
                compensate(failure) { check(!file.exists() || file.delete()) { "Unable to remove unpublished export" } }
            }
        }
    }

    private fun safeExportName(name: String, fallback: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .filterNot { it.isISOControl() || it in ":*?\"<>|" }
            .takeUnless { it.isBlank() || it == "." || it == ".." } ?: fallback

    private fun requireExportSuccess(result: ImageExportResult) {
        when (result) {
            ImageExportResult.Success -> Unit
            ImageExportResult.PermissionRequired -> error(IMAGE_SAVE_PERMISSION_REQUIRED)
            ImageExportResult.Failed -> error("Failed to save image")
        }
    }

}
