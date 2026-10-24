package net.weero.measix.pilot.ui.components.toast

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ToasterStateTest {
    @Test
    fun `replacement survives the old expiry and uses its own lifetime`() = runTest {
        val state = ToasterState(backgroundScope)
        state.show("first", duration = 2.seconds, id = "save")
        runCurrent()
        advanceTimeBy(1_000)
        state.show("saved", duration = 4.seconds, id = "save")
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("saved"), state.items.map { it.message })
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(state.items.isEmpty())
        state.close()
    }

    @Test
    fun `infinite progress is explicitly dismissible and repeated close is harmless`() = runTest {
        val state = ToasterState(backgroundScope)
        state.show("saving", duration = Duration.INFINITE, id = "save")
        runCurrent()
        advanceTimeBy(100_000)
        runCurrent()
        assertEquals(1, state.items.size)
        state.dismiss("save")
        state.dismiss("save")
        runCurrent()
        assertTrue(state.items.isEmpty())
        state.close()
        state.close()
    }

    @Test
    fun `disposed owner rejects queued and late updates`() = runTest {
        val state = ToasterState(backgroundScope)
        state.show("queued")
        state.close()
        runCurrent()
        state.show("late", duration = Duration.INFINITE)
        runCurrent()
        assertTrue(state.items.isEmpty())
    }

    @Test
    fun `visible bound evicts old items and their timers without affecting new handles`() = runTest {
        val state = ToasterState(backgroundScope)
        repeat(5) { state.show("$it", duration = 4.seconds, id = it) }
        runCurrent()
        assertEquals(listOf("2", "3", "4"), state.items.map { it.message })
        state.show("updated", duration = Duration.INFINITE, id = 4)
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(listOf("updated"), state.items.map { it.message })
        state.close()
    }
}
