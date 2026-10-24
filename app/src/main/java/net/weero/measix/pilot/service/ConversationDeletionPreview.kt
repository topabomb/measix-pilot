package net.weero.measix.pilot.service

import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import kotlin.uuid.Uuid

/** Confirmation covers these original targets and input versions, never a later UI selection. */
class ConversationDeletionPreview internal constructor(
    internal val selection: RealmSelection?,
    internal val targets: List<ConversationDeletionTargetSnapshot>,
) {
    val pendingInputCount: Int = targets.sumOf { it.pendingInputs.size }
}

internal data class ConversationDeletionTargetSnapshot(
    val target: ConversationCommandTarget,
    val runtime: ConversationRuntime?,
    val pendingInputs: Map<Uuid, PendingDeletionInput>,
)

internal data class PendingDeletionInput(val version: Long, val userMessageId: Uuid)
