package net.weero.measix.pilot.service.turn

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ClaudeContentAccumulatorTest {
    @Test fun `citations from multiple responses survive later deltas and duplicates`() {
        val accumulator = StepOutputAccumulator()
        var message = UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Step(Uuid.random(), 0, Instant.fromEpochMilliseconds(1))))
        val first = UIMessageAnnotation.UrlCitation("first", "https://example.com/first")
        val second = UIMessageAnnotation.UrlCitation("second", "https://example.com/second")
        fun append(response: String, citations: List<UIMessageAnnotation>) {
            message = accumulator.accumulate(message, MessageChunk("", "", listOf(UIMessageChoice(
                0, UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("")), annotations = citations), null, null,
                contentBlockSlots = listOf(ProviderContentBlockSlot(response, 0)),
            ))), null)
        }
        append("first", listOf(first))
        append("second", listOf(second))
        append("second", listOf(second))
        append("second", emptyList())
        assertEquals(listOf(first, second), message.annotations)
    }

    @Test fun `block deltas preserve interleaved order signatures and new response index identity`() {
        val accumulator = StepOutputAccumulator()
        var message = UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Step(Uuid.random(), 0, Instant.fromEpochMilliseconds(1))))
        fun append(index: Int, part: UIMessagePart, response: String = "first") {
            val delta = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(part))
            message = accumulator.accumulate(message, MessageChunk("", "", listOf(UIMessageChoice(0, delta, null, null,
                toolCallSlots = if (part is UIMessagePart.Tool) listOf(ProviderToolCallSlot.Index(index)) else emptyList(),
                contentBlockSlots = listOf(ProviderContentBlockSlot(response, index))))), null)
        }
        fun reasoning(text: String, signature: String? = null) = UIMessagePart.Reasoning(text,
            createdAt = Instant.fromEpochMilliseconds(1), metadata = ClaudeReasoningMetadata(signature = signature).toMetadata())
        append(0, reasoning("first"))
        append(1, UIMessagePart.Tool(localCallId = Uuid.NIL, stepId = Uuid.NIL, providerCallId = "tool", toolName = "tool", input = "{}"))
        append(2, reasoning("second"))
        append(3, UIMessagePart.Text("answer"))
        append(0, reasoning("", "sig-"))
        append(2, reasoning("", "other"))
        append(0, reasoning("", "complete"))
        assertEquals(listOf("first", "second"), message.parts.filterIsInstance<UIMessagePart.Reasoning>().map { it.reasoning })
        assertEquals(listOf("sig-complete", "other"), message.parts.filterIsInstance<UIMessagePart.Reasoning>().map { it.metadataAs<ClaudeReasoningMetadata>()?.signature })
        assertTrue(message.parts[2] is UIMessagePart.Tool)
        assertTrue(message.parts[4] is UIMessagePart.Text)
        append(0, reasoning("new response"), "second")
        assertEquals(3, message.parts.filterIsInstance<UIMessagePart.Reasoning>().size)
        assertEquals("new response", (message.parts.last() as UIMessagePart.Reasoning).reasoning)
    }

    @Test fun `retired block evidence merges across completed steps`() {
        val first = ClaudeResponseMetadata(ClaudeReplayState(retiredDigests = setOf("a"))).toMetadata()
        val second = ClaudeResponseMetadata(ClaudeReplayState(retiredDigests = setOf("b"))).toMetadata()
        val message = UIMessage.assistant("").copy(providerMetadata = mergeMessageMetadata(first, second))
        assertEquals(setOf("a", "b"), message.metadataAs<ClaudeResponseMetadata>()?.claudeReplayState?.retiredDigests)
    }
}
