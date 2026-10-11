package net.weero.measix.pilot.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatExportTest {
    @Test
    fun `reasoning option preserves tools and attachments without mutating history`() {
        val tool = UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call-1",
            toolName = "search", input = "{}",
            output = listOf(UIMessagePart.Text("result"), UIMessagePart.Reasoning("tool reasoning")),
        )
        val parts = listOf(
            UIMessagePart.Reasoning("first line\nsecond line"), UIMessagePart.Text("answer"), tool,
            UIMessagePart.Document("https://example.com/report.txt", "report.txt"),
            UIMessagePart.Image("https://example.com/image.png"),
        )
        val messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = parts))
        val withReasoning = renderMarkdownExport("Title", messages, { null })
        assertTrue(withReasoning.contains("> first line\n> second line\n"))
        val without = renderMarkdownExport("Title", messages, { null }, ChatExportOptions(includeReasoning = false))
        assertFalse(without.contains("first line"))
        assertFalse(without.contains("tool reasoning"))
        for (retained in listOf("answer", "search", "call-1", "result", "report.txt", "image.png")) {
            assertTrue(retained, without.contains(retained))
        }
        val projected = exportParts(parts, ChatExportOptions(includeReasoning = false))
        assertEquals(4, projected.size)
        assertEquals(listOf(UIMessagePart.Text("result")), (projected[1] as UIMessagePart.Tool).output)
        assertEquals(parts, messages.single().parts)
        assertEquals(2, tool.output.size)
    }
}
