package net.weero.measix.pilot.data.ai.tools.local

import kotlinx.serialization.SerializationException
import net.weero.measix.pilot.data.configuration.AssistantUsagePreferences
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class LocalToolOptionsCompatibilityTest {
    @Test fun `retired chart is removed from definitions and scoped preferences and cannot be assembled`() {
        val assistant = JsonInstant.decodeFromString<Assistant>(
            """{"localTools":[{"type":"chart_display"},{"type":"time_info"}]}""")
        val usage = JsonInstant.decodeFromString<AssistantUsagePreferences>(
            """{"assistantId":"00000000-0000-0000-0000-000000000001","localTools":{"value":[{"type":"chart_display"},{"type":"time_info"}]}}""")
        assertEquals(listOf(LocalToolOption.TimeInfo), assistant.localTools)
        assertEquals(listOf(LocalToolOption.TimeInfo), usage.localTools!!.value)
        assertFalse(JsonInstant.encodeToString(assistant).contains("chart_display"))
        assertFalse(JsonInstant.encodeToString(usage).contains("chart_display"))
        val tools = LocalTools(io.mockk.mockk(), io.mockk.mockk(), io.mockk.mockk(), io.mockk.mockk())
        for (options in listOf(assistant.localTools, usage.localTools.value)) {
            assertTrue(tools.getTools(options).none { it.name == "chart_display" })
            assertEquals(1, tools.getTools(options).size)
        }
        val empty = JsonInstant.decodeFromString<AssistantUsagePreferences>(
            """{"assistantId":"00000000-0000-0000-0000-000000000001","localTools":{"value":[{"type":"chart_display"}]}}""")
        assertNotNull(empty.localTools)
        assertTrue(empty.localTools!!.value.isEmpty())
        val restored = JsonInstant.decodeFromString<Assistant>(JsonInstant.encodeToString(assistant))
        assertEquals(assistant.localTools, restored.localTools)
    }

    @Test fun `retired javascript choice is removed from assistants without changing other choices`() {
        val assistant = JsonInstant.decodeFromString<Assistant>(
            """{"localTools":[{"type":"time_info"},{"type":"javascript_engine"},{"type":"ask_user"}]}""")
        assertEquals(listOf(LocalToolOption.TimeInfo, LocalToolOption.AskUser), assistant.localTools)
        assertFalse(JsonInstant.encodeToString(assistant).contains("javascript_engine"))
        val empty = JsonInstant.decodeFromString<Assistant>("""{"localTools":[{"type":"javascript_engine"}]}""")
        assertTrue(empty.localTools.isEmpty())
    }

    @Test fun `enterprise usage list drops only the retired option and retains explicit override`() {
        val usage = JsonInstant.decodeFromString<AssistantUsagePreferences>(
            """{"assistantId":"00000000-0000-0000-0000-000000000001","localTools":{"value":[{"type":"javascript_engine"},{"type":"clipboard"}]}}""")
        assertEquals(listOf(LocalToolOption.Clipboard), usage.localTools!!.value)
        assertFalse(JsonInstant.encodeToString(usage).contains("javascript_engine"))
        assertThrows(SerializationException::class.java) {
            JsonInstant.decodeFromString<Assistant>("""{"localTools":[{"type":"unrecognized_active_tool"}]}""")
        }
    }
}
