package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.common.configuration.ConfigurationReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageModelInfoTest {
    @Test
    fun `historical identity survives serialization while legacy references stay unknown`() {
        val info = MessageModelInfo(ConfigurationReference.random(), "wire-id", "Original name")
        val original = UIMessage.assistant("answer").copy(
            modelId = info.id, modelInfo = info,
            createdAt = kotlinx.datetime.LocalDateTime(2026, 10, 10, 12, 0),
        )
        val encoded = Json.encodeToString(original)
        assertEquals(original, Json.decodeFromString<UIMessage>(encoded))
        val legacy = JsonObject(Json.parseToJsonElement(encoded).jsonObject - "modelInfo")
        val decoded = Json.decodeFromString<UIMessage>(legacy.toString())
        assertEquals(info.id, decoded.modelId)
        assertNull(decoded.modelInfo)
        assertEquals("wire-id", info.copy(displayName = "").displayLabel)
    }
}
