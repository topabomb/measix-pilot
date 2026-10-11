package net.weero.measix.pilot.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import java.security.MessageDigest
import kotlin.uuid.Uuid

@Serializable
internal data class HistorySummaryCoveredMessage(val message: ContextMessageLocator, val fingerprint: String) {
    init { require(fingerprint.matches(Regex("[0-9a-f]{64}"))) { "invalid_history_summary_fingerprint" } }
}

/** Immutable evidence of the original prefix. Missing historical locators invalidate, rather than corrupt, a checkpoint. */
@Serializable
internal data class HistorySummaryCheckpoint(
    val version: Int = 1,
    val covered: List<HistorySummaryCoveredMessage>,
    val previousEntryId: Uuid? = null,
    val previousSummaryFingerprint: String? = null,
) {
    init {
        require(version == 1) { "unsupported_history_summary_checkpoint" }
        require(covered.isNotEmpty() && covered.map { it.message }.distinct().size == covered.size) { "invalid_history_summary_coverage" }
        require((previousEntryId == null) == (previousSummaryFingerprint == null)) { "invalid_history_summary_dependency" }
        require(previousSummaryFingerprint == null || previousSummaryFingerprint.matches(Regex("[0-9a-f]{64}"))) { "invalid_history_summary_dependency_fingerprint" }
    }
}

internal data class SelectedHistorySummary(val entry: ConversationModelContextEntry, val message: UIMessage,
    val checkpoint: HistorySummaryCheckpoint)

internal fun ConversationModelContextEntry.summaryCheckpoint(): HistorySummaryCheckpoint? =
    (payload.source as? ConversationContextSource.HistorySummary)?.checkpoint

/** Summary containers are excluded even when their variant or coverage is no longer applicable. */
internal fun checkpointContainerIds(entries: List<ConversationModelContextEntry>): Set<Uuid> =
    entries.filter { it.summaryCheckpoint() != null }.mapTo(hashSetOf()) { it.ownerNodeId }

internal fun selectHistorySummary(
    branch: List<UIMessage>,
    locators: Map<Uuid, ContextMessageLocator>,
    entries: List<ConversationModelContextEntry>,
    admittedEntryIds: Set<Uuid>? = null,
): SelectedHistorySummary? {
    val containers = checkpointContainerIds(entries)
    val ordinary = branch.filter { locators[it.id]?.nodeId !in containers }
    val semanticLocators = ordinary.mapNotNull { locators[it.id] }
    val positions = branch.withIndex().associate { it.value.id to it.index }
    val byId = entries.associateBy { it.id }
    val memo = mutableMapOf<Uuid, Boolean>()
    val visiting = mutableSetOf<Uuid>()
    fun valid(entry: ConversationModelContextEntry): Boolean {
        memo[entry.id]?.let { return it }
        val checkpoint = entry.summaryCheckpoint() ?: return false
        if (!visiting.add(entry.id)) return false
        val ownerIndex = positions[entry.ownerMessageId]
        val prefix = ordinary.take(checkpoint.covered.size)
        val accepted = ownerIndex != null && locators[entry.ownerMessageId] == ContextMessageLocator(entry.ownerNodeId, entry.ownerMessageId) &&
            ordinary.count { positions.getValue(it.id) < ownerIndex } == checkpoint.covered.size &&
            prefix.size == checkpoint.covered.size && prefix.zip(checkpoint.covered).all { (message, covered) ->
                locators[message.id] == covered.message && positions.getValue(message.id) < ownerIndex &&
                    historySummaryFingerprint(message, entries, semanticLocators) == covered.fingerprint
            } && checkpoint.previousEntryId?.let { previousId ->
                val previous = byId[previousId]
                previous != null && (positions[previous.ownerMessageId] ?: Int.MAX_VALUE) < ownerIndex &&
                    previous.summaryCheckpoint()?.covered?.size?.let { it <= checkpoint.covered.size } == true && valid(previous) &&
                    historySummaryFingerprint(branch[positions.getValue(previous.ownerMessageId)], entries, semanticLocators) == checkpoint.previousSummaryFingerprint
            } != false
        visiting.remove(entry.id)
        memo[entry.id] = accepted
        return accepted
    }
    return entries.asSequence().filter { it.summaryCheckpoint() != null && (admittedEntryIds == null || it.id in admittedEntryIds) }
        .sortedByDescending { positions[it.ownerMessageId] ?: -1 }.firstOrNull(::valid)?.let { entry ->
            SelectedHistorySummary(entry, branch[positions.getValue(entry.ownerMessageId)], entry.summaryCheckpoint()!!)
        }
}

/** Wire-visible content and its original application sources, excluding display clocks and usage. */
internal fun historySummaryFingerprint(message: UIMessage, entries: List<ConversationModelContextEntry>,
    semanticLocators: List<ContextMessageLocator>): String {
    val stepOrdinals = message.parts.filterIsInstance<UIMessagePart.Step>().associate { it.stepId to it.ordinal }
    fun parts(input: List<UIMessagePart>): JsonArray = JsonArray(input.mapNotNull { part ->
        when (part) {
            is UIMessagePart.Step -> buildJsonObject { put("type", "step"); put("ordinal", part.ordinal) }
            is UIMessagePart.Reasoning -> buildJsonObject {
                put("type", "reasoning"); put("reasoning", part.reasoning); put("metadata", part.metadata ?: JsonNull)
            }
            is UIMessagePart.Tool -> buildJsonObject {
                put("type", "tool"); put("name", part.toolName); put("input", part.input)
                put("step", stepOrdinals[part.stepId])
                put("output", parts(part.output)); put("status", part.resultStatus?.name)
                // Fork allocates new lineage identities without changing the saved result.
                // Only the known typed lineage fields are normalized; opaque Provider metadata stays intact.
                val call = part.getSubAssistantCallMetadata(Json)
                val metadata = if (call?.schemaVersion == 2) part.mergeSubAssistantCallMetadata(Json, call.copy(
                    runId = "", previousRunId = null, childConversationId = null, childTaskNodeId = null,
                )).metadata else part.metadata
                put("metadata", metadata ?: JsonNull)
            }
            else -> Json.encodeToJsonElement<UIMessagePart>(part)
        }
    })
    val emptyLocator = ContextMessageLocator(Uuid.NIL, Uuid.NIL)
    fun normalize(locator: ContextMessageLocator): ContextMessageLocator {
        if (locator.messageId == message.id) return emptyLocator
        val index = semanticLocators.indexOf(locator)
        return if (index < 0) locator else ContextMessageLocator(Uuid.fromLongs(0, index.toLong() + 1), Uuid.NIL)
    }
    val value = buildJsonObject {
        put("role", message.role.name)
        put("parts", parts(message.parts))
        put("providerMetadata", message.providerMetadata ?: JsonNull)
        put("sources", JsonArray(entries.filter { it.ownerMessageId == message.id }.sortedBy { it.occurrence }.map { entry ->
            val source = when (val original = entry.payload.source) {
                is ConversationContextSource.MessageTime -> original.copy(message = normalize(original.message), previous = original.previous?.let(::normalize))
                is ConversationContextSource.Attachment -> original.copy(message = normalize(original.message))
                is ConversationContextSource.HistorySummary -> original.copy(checkpoint = original.checkpoint?.let { checkpoint ->
                    checkpoint.copy(covered = checkpoint.covered.map { it.copy(message = normalize(it.message)) },
                        previousEntryId = checkpoint.previousEntryId?.let { Uuid.NIL })
                })
                else -> original
            }
            val body = if (entry.payload.body is ConversationContextBody.MessageReference) ConversationContextBody.MessageReference(emptyLocator)
                else entry.payload.body
            Json.encodeToJsonElement(entry.payload.copy(source = source, body = body))
        }))
    }
    fun canonical(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(element.map(::canonical))
        else -> element
    }
    return MessageDigest.getInstance("SHA-256").digest(canonical(value).toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
