package net.weero.measix.pilot.service

import net.weero.measix.pilot.data.enterprise.EnterpriseExitRequest
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import kotlin.uuid.Uuid

/** An approval remains bound to the original Session and unsent input versions. */
internal class EnterpriseExitPreview internal constructor(
    internal val request: EnterpriseExitRequest,
    internal val queues: Map<ConversationRuntime, Map<Uuid, PendingDeletionInput>>,
) {
    val access get() = request.access
    val selection get() = request.selection
    // Personal space may request logout, but does not gain enterprise queue disclosure.
    val pendingInputCount: Int? = if (selection.access == access) queues.values.sumOf { it.size } else null
}
