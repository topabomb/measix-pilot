package net.weero.measix.pilot.service

import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.ToolCallLocator
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.service.runtime.*
import org.junit.Assert.*
import org.junit.Test

class ToolDurationQueryProjectionTest {
    @Test fun `streaming reuses committed durations and each new commit reloads them`() = runTest {
        val projection = ToolDurationQueryProjection()
        val snapshot = Conversation.ofId(Uuid.random()).toSnapshot()
        val locator = ToolCallLocator(Uuid.random(), Uuid.random(), Uuid.random())
        var loads = 0
        suspend fun load(): Map<ToolCallLocator, Long> { loads++; return mapOf(locator to loads.toLong()) }
        assertEquals(mapOf(locator to 1L), projection.read(snapshot, ::load))
        assertEquals(mapOf(locator to 1L), projection.read(snapshot, ::load))
        assertEquals(1, loads)
        assertEquals(mapOf(locator to 2L), projection.read(snapshot.copy(), ::load))
    }

    @Test fun `failed query cannot advance the cache and draft does not query persistence`() = runTest {
        val projection = ToolDurationQueryProjection()
        val snapshot = Conversation.ofId(Uuid.random()).toSnapshot()
        val failure = runCatching { projection.read(snapshot) { error("read failed") } }.exceptionOrNull()
        assertNotNull(failure)
        var retried = false
        projection.read(snapshot) { retried = true; emptyMap() }
        assertTrue(retried)
        val draft = snapshot.copy(header = snapshot.header.copy(newConversation = true))
        assertTrue(projection.read(draft) { error("draft must not read persisted tools") }.isEmpty())
    }
}
