package net.weero.measix.pilot.service

import net.weero.measix.pilot.data.enterprise.reference

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.enterprise.EnterpriseStarter
import net.weero.measix.pilot.data.enterprise.EnterpriseCandidate
import net.weero.measix.pilot.data.enterprise.EnterpriseExecution
import net.weero.measix.pilot.data.model.ConversationOpening
import kotlin.uuid.Uuid

internal data class ConversationOpeningSummary(val reference: ConfigurationReference.Enterprise, val title: String, val selectionToken: Uuid?)
internal data class StarterContextUiModel(val content: String)
internal enum class StarterOpeningIssue { NOT_SUPPLIED, UPDATED, UNAVAILABLE, ASSISTANT_CHANGED }
internal class StarterOpeningException(val issue: StarterOpeningIssue) : IllegalStateException("enterprise_starter_${issue.name.lowercase()}")
internal data class StarterOpeningDetailUiModel(
    val title: String,
    val prompt: String,
    val systemPrompt: String?,
    val contexts: List<StarterContextUiModel>,
    val isDraft: Boolean,
    val systemApplicable: Boolean,
    val issue: StarterOpeningIssue? = null,
    val canRefresh: Boolean = false,
)

internal fun EnterpriseCandidate.opening(starter: EnterpriseStarter): ConversationOpening {
    if (starter.openingSnapshot == null) throw StarterOpeningException(StarterOpeningIssue.NOT_SUPPLIED)
    val source = execution as EnterpriseExecution.Platform
    return ConversationOpening(assistant = identity.reference(starter.assistantId), releaseId = source.releaseId,
        generation = configuration.generation, snapshotHash = source.snapshotHash, definition = starter)
}

/** Called at the first AppendUserMessage boundary; publication changes alone do not invalidate a selection. */
internal fun requireCurrentStarterOpening(opening: ConversationOpening?, assistantId: ConfigurationReference, configuration: ResolvedConfiguration) {
    if (opening == null) return
    if (opening.assistant != assistantId) throw StarterOpeningException(StarterOpeningIssue.ASSISTANT_CHANGED)
    val current = configuration.availableStarters(opening.assistant).singleOrNull { it.id == opening.definition.id }
        ?: throw StarterOpeningException(StarterOpeningIssue.UNAVAILABLE)
    if (current.openingSnapshot == null) throw StarterOpeningException(StarterOpeningIssue.NOT_SUPPLIED)
    if (current != opening.definition) throw StarterOpeningException(StarterOpeningIssue.UPDATED)
}

internal fun EnterpriseStarter.details(isDraft: Boolean, applicable: Boolean = true, issue: StarterOpeningIssue? = null, canRefresh: Boolean = false) =
    StarterOpeningDetailUiModel(title, prompt, openingSnapshot?.systemPrompt,
        openingSnapshot?.initialContexts.orEmpty().map { StarterContextUiModel(it.content) },
        isDraft, applicable, issue ?: if (openingSnapshot == null) StarterOpeningIssue.NOT_SUPPLIED else null, canRefresh)
