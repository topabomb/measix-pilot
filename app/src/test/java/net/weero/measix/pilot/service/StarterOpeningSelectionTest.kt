package net.weero.measix.pilot.service

import net.weero.measix.pilot.data.configuration.ConfigurationResolver
import net.weero.measix.pilot.data.configuration.appliedConfiguration
import net.weero.measix.pilot.data.datastore.UserSettingsDocument
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.model.ConversationOpeningCodec
import org.junit.Assert.*
import org.junit.Test

class StarterOpeningSelectionTest {
    private val packet = exampleEnterprisePackage()
    private val candidate = packet.toCandidate()
    private val starter = packet.configuration.starters.first { it.enabled }
    private val opening = candidate.opening(starter)
    private fun resolved(value: EnterprisePackage = packet) = ConfigurationResolver.resolve(
        UserSettingsDocument.empty(), value.identity.scope, appliedConfiguration(value))

    @Test fun `unrelated publication preserves captured opening and first send remains valid`() {
        val next = packet.copy(configuration = packet.configuration.copy(generation = packet.configuration.generation + 1))
        val restored = ConversationOpeningCodec.decode(ConversationOpeningCodec.encode(opening))
        assertEquals(opening, restored)
        requireCurrentStarterOpening(restored, restored.assistant, resolved(next))
        assertEquals(candidate.configuration.generation, opening.generation)
        assertEquals((candidate.execution as EnterpriseExecution.Platform).releaseId, opening.releaseId)
        assertEquals(starter, opening.definition)
    }

    @Test fun `all selected definition changes invalidate first send without mutating source`() {
        val variants = listOf(starter.copy(prompt = starter.prompt + "edited"),
            starter.copy(openingSnapshot = starter.openingSnapshot!!.copy(systemPrompt = "changed")),
            starter.copy(openingSnapshot = starter.openingSnapshot!!.copy(initialContexts = starter.openingSnapshot.initialContexts.reversed())))
        variants.forEach { changed ->
            val changedPacket = packet.copy(configuration = packet.configuration.copy(starters = packet.configuration.starters.map {
                if (it.id == starter.id) changed else it
            }))
            assertEquals(StarterOpeningIssue.UPDATED, assertThrows(StarterOpeningException::class.java) {
                requireCurrentStarterOpening(opening, opening.assistant, resolved(changedPacket))
            }.issue)
            assertEquals(starter, opening.definition)
        }
    }

    @Test fun `disabled missing and foreign targets stay distinct from publication changes`() {
        val disabled = packet.copy(configuration = packet.configuration.copy(starters = packet.configuration.starters.map { it.copy(enabled = false) }))
        assertEquals(StarterOpeningIssue.UNAVAILABLE, assertThrows(StarterOpeningException::class.java) {
            requireCurrentStarterOpening(opening, opening.assistant, resolved(disabled))
        }.issue)
        assertEquals(StarterOpeningIssue.ASSISTANT_CHANGED, assertThrows(StarterOpeningException::class.java) {
            requireCurrentStarterOpening(opening, opening.assistant.copy(id = "asd_other"), resolved())
        }.issue)
        assertEquals(StarterOpeningIssue.NOT_SUPPLIED, assertThrows(StarterOpeningException::class.java) {
            candidate.opening(starter.copy(openingSnapshot = null))
        }.issue)
        requireCurrentStarterOpening(null, opening.assistant, resolved(disabled))
    }
}
