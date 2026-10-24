package net.weero.measix.pilot.service

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.core.MessageRole
import net.weero.measix.pilot.data.ai.tools.PendingToolInteraction
import me.rerere.ai.ui.ToolInteractionState
import net.weero.measix.pilot.service.turn.TurnOutcome
import net.weero.measix.pilot.service.turn.TurnPause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class GenerationSideEffectsTest {
    private fun summaryResult(message: UIMessage, reason: String? = "stop") = me.rerere.ai.ui.MessageChunk(
        id = "summary", model = "model", choices = listOf(me.rerere.ai.ui.UIMessageChoice(
            index = 0, delta = null, message = message, finishReason = reason)))

    @Test fun `summary discards leading reasoning but preserves literal tags in code`() {
        assertEquals("## User Goals\nContinue", historySummaryText(summaryResult(
            UIMessage.assistant("<think>private reasoning</think>\n## User Goals\nContinue"))))
        val literal = "Example: `<think>literal</think>`"
        assertEquals(literal, historySummaryText(summaryResult(UIMessage.assistant(literal))))
        val native = UIMessage.assistant("Summary").copy(parts = listOf(
            UIMessagePart.Reasoning("private"), UIMessagePart.Text("Summary")))
        assertEquals("Summary", historySummaryText(summaryResult(native)))
    }

    @Test fun `summary strips reasoning across text blocks and rejects an unclosed section`() {
        fun blocks(vararg text: String) = summaryResult(UIMessage.assistant("").copy(
            parts = text.map { UIMessagePart.Text(it) }))
        assertEquals("Summary\nExample: `<think>literal</think>`", historySummaryText(blocks(
            "  ", "<think>first reasoning", "remaining reasoning</think>\nSummary",
            "Example: `<think>literal</think>`")))
        val failure = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            historySummaryText(blocks("<think>first reasoning", "remaining reasoning"))
        }
        assertEquals("empty_history_summary_chunk", failure.message)
        assertEquals("Summary\nLiteral <think>example</think>", historySummaryText(blocks(
            "Summary", "Literal <think>example</think>")))
    }

    @Test fun `summary rejects tools even when explanatory text is present`() {
        val message = UIMessage.assistant("I will inspect the file").copy(parts = listOf(
            UIMessagePart.Text("I will inspect the file"),
            UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call",
                toolName = "read_file", input = "{}")))
        val failure = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            historySummaryText(summaryResult(message, "tool_calls"))
        }
        assertEquals("history_summary_tool_call_response", failure.message)
    }

    @Test fun `summary rejects incomplete terminal states and reasoning-only output`() {
        listOf("length", "max_tokens", "MAX_TOKENS", "SAFETY", "content_filter", "incomplete", "pause_turn").forEach { reason ->
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                historySummaryText(summaryResult(UIMessage.assistant("partial summary"), reason))
            }
        }
        listOf("", " ", "<think>unfinished", "<think>reasoning</think>").forEach { text ->
            val failure = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                historySummaryText(summaryResult(UIMessage.assistant(text)))
            }
            assertEquals("empty_history_summary_chunk", failure.message)
        }
    }

    @Test
    fun `approval sound follows committed pending state and is not replayed`() {
        var steps = 0
        var approvals = 0
        val finishedAt = LocalDateTime(2026, 1, 1, 12, 0)
        val initial = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), finishedAt = finishedAt)
        val pending = initial.copy(parts = listOf("first", "second").map { call ->
            UIMessagePart.Tool(
                localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = call,
                toolName = "calendar_create", input = "{}",
                interactionState = ToolInteractionState.AwaitingApproval,
            )
        })
        val tracker = GenerationSoundTracker(initial, Json, { steps++ }, { approvals++ })

        tracker.onStreaming(pending, enabled = true)
        assertEquals(0, approvals)
        assertEquals(0, steps)
        tracker.onCheckpoint(pending, enabled = true)
        tracker.onCheckpoint(pending, enabled = true)
        assertEquals(1, approvals)
        assertEquals(0, steps)

        val resumed = GenerationSoundTracker(pending, Json, { steps++ }, { approvals++ })
        resumed.onStreaming(pending, enabled = true)
        resumed.onCheckpoint(pending, enabled = true)
        assertEquals(1, approvals)
        assertEquals(0, steps)
    }

    @Test
    fun `attention observed while sound is disabled is not played later`() {
        var approvals = 0
        val initial = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        val pending = initial.copy(parts = listOf(UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call",
            toolName = "calendar_create", input = "{}",
            interactionState = ToolInteractionState.AwaitingApproval,
        )))
        val tracker = GenerationSoundTracker(initial, Json, {}, { approvals++ })

        tracker.onCheckpoint(pending, enabled = false)
        tracker.onCheckpoint(pending, enabled = true)
        assertEquals(0, approvals)
    }

    @Test
    fun `background generation params preserve model request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(modelId = "custom-chat-model", customHeaders = headers, customBodies = bodies)

        val conversationId = Uuid.random()
        val params = backgroundTextGenerationParams(model, ReasoningLevel.LOW, conversationId)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.LOW, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
        assertEquals(conversationId.toString(), params.openCodeSessionId)
        assertEquals(null, params.providerSessionId)
    }

    @Test
    fun `only completed turns launch completion side effects`() {
        assertTrue(shouldLaunchCompletionSideEffects(TurnOutcome.Completed(assistantMessage = UIMessage.assistant("done"))))
        assertFalse(shouldLaunchCompletionSideEffects(TurnPause(
            listOf(PendingToolInteraction(me.rerere.ai.core.ToolCallLocator(kotlin.uuid.Uuid.random(), kotlin.uuid.Uuid.random(), kotlin.uuid.Uuid.random()), ToolInteractionState.AwaitingApproval)),
        )))
        assertFalse(shouldLaunchCompletionSideEffects(TurnOutcome.Incomplete("step_limit")))
    }
}
