package net.weero.measix.pilot.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.QueuedConversationInput
import kotlin.uuid.Uuid

/** One editor borrows the original queued input and owns only newly imported resources. */
internal class QueueInputEditLease internal constructor(
    internal val target: ConversationAssistantTarget,
    internal val runtime: ConversationRuntime,
    internal val original: QueuedConversationInput,
    createDraft: (() -> Unit) -> ArtifactDraftScope,
    private val onClose: (QueueInputEditLease, Throwable?) -> Unit,
) : AutoCloseable {
    val itemId: Uuid = original.id
    val version: Long = original.version
    val parts: List<UIMessagePart> = original.parts.toList()
    internal val editToken: Uuid = requireNotNull(original.editToken)
    internal val initialQueueEpoch: Long = runtime.inputQueue.value.epoch
    private val state = Any()
    private val closed = MutableStateFlow(false)
    val isClosed = closed.asStateFlow()
    private var saving = false
    private var observer: Job? = null
    val draft: ArtifactDraftScope = createDraft(::requireReadable)

    /** UI import and editing actions must stop once save has captured the input. */
    fun requireOpen() = synchronized(state) {
        check(!closed.value) { "queue_edit_closed" }
        check(!saving) { "queue_edit_saving" }
    }

    internal fun requireReadable() = synchronized(state) {
        check(!closed.value) { "queue_edit_closed" }
    }

    internal fun beginSave() = synchronized(state) {
        requireOpen()
        saving = true
    }

    internal fun finishSave() = synchronized(state) { saving = false }

    /** The caller already holds the original Session and conversation operation lock. */
    internal fun acceptReplacement(action: () -> Unit) = synchronized(state) {
        check(!closed.value && saving) { "queue_edit_closed" }
        action()
    }

    internal fun observeWith(job: Job) {
        val cancel = synchronized(state) {
            if (closed.value) true else { observer = job; false }
        }
        if (cancel) job.cancel()
    }

    override fun close() {
        val job = synchronized(state) {
            if (closed.value) return
            closed.value = true
            observer.also { observer = null }
        }
        var failure: Throwable? = null
        try { draft.close() } catch (error: Throwable) { failure = error }
        job?.cancel()
        onClose(this, failure)
    }
}
