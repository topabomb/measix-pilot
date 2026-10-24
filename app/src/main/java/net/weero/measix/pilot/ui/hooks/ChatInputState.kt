package net.weero.measix.pilot.ui.hooks

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

sealed interface ChatInputEditTarget {
    data class History(val messageId: Uuid) : ChatInputEditTarget
    data class Queued(val itemId: Uuid, val version: Long) : ChatInputEditTarget
}

class ChatInputState {
    val textContent = TextFieldState()
    private data class InputAttachment(val part: UIMessagePart, val identity: Any = Any())
    private var attachments by mutableStateOf(emptyList<InputAttachment>())
    var messageContent: List<UIMessagePart>
        get() = attachments.map { it.part }
        set(value) {
            val retained = attachments.toMutableList()
            attachments = value.map { part ->
                val index = retained.indexOfFirst { it.part === part }
                if (index >= 0) retained.removeAt(index) else InputAttachment(part)
            }
        }
    var editingTarget by mutableStateOf<ChatInputEditTarget?>(null)
    var editingMessage: Uuid?
        get() = (editingTarget as? ChatInputEditTarget.History)?.messageId
        set(value) { editingTarget = value?.let { ChatInputEditTarget.History(it) } }
    private var editingParts: List<UIMessagePart>? = null
    private var editingAttachmentPositions: Map<Any, Int> = emptyMap()
    private var editingAttachmentUrls: Set<String> = emptySet()
    private var inputIdentity = Any()
    private var imageEditEpoch by mutableStateOf(Any())

    internal class ImageEditTarget internal constructor(
        internal val inputIdentity: Any,
        internal val epoch: Any,
        internal val attachmentIdentity: Any,
        internal val editingTarget: ChatInputEditTarget?,
        val image: UIMessagePart.Image,
    )

    internal fun imageEditTargets(): List<ImageEditTarget> = attachments.mapNotNull { attachment ->
        (attachment.part as? UIMessagePart.Image)?.let { image ->
            ImageEditTarget(inputIdentity, imageEditEpoch, attachment.identity, editingTarget, image)
        }
    }

    internal fun isImageEditCurrent(target: ImageEditTarget): Boolean =
        target.inputIdentity === inputIdentity && target.epoch === imageEditEpoch &&
            target.editingTarget == editingTarget && attachments.any { it.identity === target.attachmentIdentity }

    internal fun invalidateImageEdits() { imageEditEpoch = Any() }

    internal fun removeImage(target: ImageEditTarget): Boolean {
        if (!isImageEditCurrent(target)) return false
        attachments = attachments.filterNot { it.identity === target.attachmentIdentity }
        return true
    }

    /** The caller retains resource ownership until this identity-based replacement succeeds. */
    internal fun replaceImage(target: ImageEditTarget, image: UIMessagePart.Image): Boolean {
        if (!isImageEditCurrent(target)) return false
        val replacement = InputAttachment(image)
        // Preserve the causal position when editing a message containing interleaved text and media.
        editingAttachmentPositions[target.attachmentIdentity]?.let { position ->
            editingAttachmentPositions = editingAttachmentPositions - target.attachmentIdentity + (replacement.identity to position)
        }
        attachments = attachments.map { if (it.identity === target.attachmentIdentity) replacement else it }
        return true
    }

    internal class Submission internal constructor(
        internal val identity: Any,
        internal val text: String,
        internal val attachmentIdentities: Set<Any>,
        internal val editingTarget: ChatInputEditTarget?,
        val contents: List<UIMessagePart>,
    ) {
        internal val editingMessage: Uuid? get() = (editingTarget as? ChatInputEditTarget.History)?.messageId
    }

    internal fun captureSubmission(): Submission {
        // Sending invalidates pending image edits even if the request is subsequently rejected.
        invalidateImageEdits()
        return Submission(
            inputIdentity, textContent.text.toString(), attachments.mapTo(mutableSetOf()) { it.identity }, editingTarget, getContents(),
        )
    }

    /** A committed submission owns its captured input, never edits made while it was waiting. */
    internal fun completeSubmission(submission: Submission) {
        if (submission.identity !== inputIdentity || submission.editingTarget != editingTarget) return
        if (textContent.text.toString() == submission.text) textContent.setTextAndPlaceCursorAtEnd("")
        // Equal file payloads can be removed and selected again while append is pending.
        attachments = attachments.filterNot { it.identity in submission.attachmentIdentities }
        editingTarget = null
        editingParts = null
        editingAttachmentPositions = emptyMap()
        editingAttachmentUrls = emptySet()
        inputIdentity = Any()
    }

    fun clearInput() {
        inputIdentity = Any()
        textContent.setTextAndPlaceCursorAtEnd("")
        messageContent = emptyList()
        editingTarget = null
        editingParts = null
        editingAttachmentPositions = emptyMap()
        editingAttachmentUrls = emptySet()
    }

    fun isEditing() = editingTarget != null

    fun setMessageText(text: String) {
        textContent.setTextAndPlaceCursorAtEnd(text)
    }

    fun appendText(content: String) {
        textContent.setTextAndPlaceCursorAtEnd(textContent.text.toString() + content)
    }

    fun setContents(contents: List<UIMessagePart>) {
        inputIdentity = Any()
        val lastTextIndex = contents.indexOfLast { it is UIMessagePart.Text }
        val text = if (lastTextIndex >= 0) {
            (contents[lastTextIndex] as UIMessagePart.Text).text
        } else {
            ""
        }
        textContent.setTextAndPlaceCursorAtEnd(text)
        messageContent = contents.filter { it !is UIMessagePart.Text }
        editingParts = contents
        editingAttachmentPositions = attachments.map { it.identity }
            .zip(contents.indices.filter { contents[it] !is UIMessagePart.Text }).toMap()
        editingAttachmentUrls = contents.mapNotNull { it.attachmentUrlOrNull() }.toSet()
    }

    fun getContents(): List<UIMessagePart> {
        val text = textContent.text.toString()
        if (isEditing()) {
            val originalParts = editingParts
            if (originalParts != null) {
                val editedTextIndex = originalParts.indexOfLast { it is UIMessagePart.Text }
                val remainingAttachments = attachments.toMutableList()
                val merged = mutableListOf<UIMessagePart>()

                originalParts.forEachIndexed { index, part ->
                    when {
                        index == editedTextIndex -> {
                            if (text.isNotBlank()) merged.add(UIMessagePart.Text(text))
                        }

                        part is UIMessagePart.Text -> {
                            if (part.text.isNotBlank()) merged.add(part)
                        }

                        else -> {
                            val currentIndex = remainingAttachments.indexOfFirst { editingAttachmentPositions[it.identity] == index }
                            if (currentIndex >= 0) {
                                merged.add(remainingAttachments.removeAt(currentIndex).part)
                            }
                        }
                    }
                }
                if (editedTextIndex < 0 && text.isNotBlank()) {
                    merged.add(0, UIMessagePart.Text(text))
                }
                // Newly added attachments are appended in insertion order.
                merged.addAll(remainingAttachments.map { it.part })
                return merged
            }
            return if (text.isBlank()) messageContent else listOf(UIMessagePart.Text(text)) + messageContent
        }
        return if (text.isBlank()) messageContent else listOf(UIMessagePart.Text(text)) + messageContent
    }

    fun isEmpty(): Boolean {
        return getContents().isEmpty()
    }

    fun addImages(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Image(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addVideos(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Video(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addAudios(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Audio(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addFiles(uris: List<UIMessagePart.Document>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach {
            newMessage.add(it)
        }
        messageContent = newMessage
    }

    /**
     * 仅删除当前输入组件临时新增的本地文件。
     * 编辑历史消息时，原有附件不在这里删除，由会话层统一做差异清理。
     */
    fun shouldDeleteFileOnRemove(part: UIMessagePart): Boolean {
        val url = part.attachmentUrlOrNull() ?: return false
        if (!url.startsWith("file:", ignoreCase = true)) return false
        return !isEditing() || url !in editingAttachmentUrls
    }

    private fun UIMessagePart.attachmentUrlOrNull(): String? {
        return when (this) {
            is UIMessagePart.Image -> this.url
            is UIMessagePart.Video -> this.url
            is UIMessagePart.Audio -> this.url
            is UIMessagePart.Document -> this.url
            else -> null
        }
    }
}
