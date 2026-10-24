package net.weero.measix.pilot.service.runtime

import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.ArtifactSubmission
import kotlin.uuid.Uuid

internal enum class QueuedInputPhase { WAITING, EDITING, SUBMITTING }
internal enum class InputQueueRequestOutcome { COMPLETED, AWAITING_USER, FAILED, CANCELLED }
internal enum class InputQueuePauseReason { USER_STOP, REALM_CHANGED, CONTEXT_CHANGED, REQUEST_FAILED }

/** Process-local input ownership. Only ConversationRuntime mutates the containing queue. */
internal data class QueuedConversationInput(
    val id: Uuid,
    val version: Long,
    val userMessageId: Uuid,
    val parts: List<UIMessagePart>,
    val answer: Boolean,
    val selection: RealmSelection,
    val assistantId: ConfigurationReference,
    val contextRevision: Long,
    val phase: QueuedInputPhase,
    val submission: ArtifactSubmission?,
    val editToken: Uuid? = null,
)

/** A dispatch observation is not permission to start; installation rechecks it atomically. */
internal data class QueuedInputDispatch(
    val itemId: Uuid,
    val version: Long,
    val epoch: Long,
)

internal data class ConversationInputQueueSnapshot(
    val items: List<QueuedConversationInput> = emptyList(),
    val pauseReason: InputQueuePauseReason? = null,
    val epoch: Long = 0,
)
