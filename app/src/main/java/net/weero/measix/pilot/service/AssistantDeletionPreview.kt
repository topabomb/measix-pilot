package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import kotlin.uuid.Uuid

/** Shared definition deletion covers every space; only the originally selected space discloses a count. */
internal class AssistantDeletionPreview internal constructor(
    internal val assistantId: ConfigurationReference,
    internal val selection: RealmSelection,
    internal val queues: Map<ConversationRuntime, Map<Uuid, PendingDeletionInput>>,
) {
    val pendingInputCount: Int = queues.filterKeys { it.durable.header.scope == selection.access.scope }
        .values.sumOf { it.size }
}
