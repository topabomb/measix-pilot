package net.weero.measix.pilot.data.ai.tools.local

import kotlinx.serialization.SerializationException
import net.weero.measix.pilot.data.configuration.AssistantUsagePreferences
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test

class LocalToolOptionsCompatibilityTest {
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
