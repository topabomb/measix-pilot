package net.weero.measix.pilot.service

import net.weero.measix.pilot.data.enterprise.EnterpriseDataResetRequest
import net.weero.measix.pilot.data.enterprise.EnterpriseLocalDataResetAdmission
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import kotlin.uuid.Uuid

/** The original reset choice and queue versions stay together until durable admission. */
internal class EnterpriseResetPreview internal constructor(
    val request: EnterpriseDataResetRequest,
    internal val admission: EnterpriseLocalDataResetAdmission,
    internal val queues: Map<ConversationRuntime, Map<Uuid, PendingDeletionInput>>,
) {
    val pendingInputCount: Int? = (admission.selection?.access as? RealmAccess.Enterprise)?.let { access ->
        queues.filterKeys { it.durable.header.scope == access.scope }.values.sumOf { it.size }
    }
}
