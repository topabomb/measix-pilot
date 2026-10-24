package net.weero.measix.pilot.service

import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

enum class QueuedInputUiPhase { WAITING, EDITING, SUBMITTING }
enum class InputQueuePauseUiReason { USER_STOP, REALM_CHANGED, CONTEXT_CHANGED, REQUEST_FAILED }

data class QueuedInputUiModel(
    val id: Uuid,
    val version: Long,
    val parts: List<UIMessagePart>,
    val phase: QueuedInputUiPhase,
    val canEdit: Boolean,
    val canRemove: Boolean,
)

/** Read-only display values; attachment ownership and execution authority remain with Runtime. */
data class ConversationInputQueueUiModel(
    val items: List<QueuedInputUiModel>,
    val pauseReason: InputQueuePauseUiReason?,
) {
    val queueCount: Int get() = items.size
}
