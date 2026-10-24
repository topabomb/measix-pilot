package net.weero.measix.pilot.service.turn

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ProviderUsageSnapshot
import me.rerere.ai.provider.*
import me.rerere.ai.ui.*
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.runtime.ModelResponseCheckpoint
import net.weero.measix.pilot.service.runtime.TurnCheckpoint
import net.weero.measix.pilot.test.TurnRunCapture
import net.weero.measix.pilot.test.turnRunInputsFixture
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ProviderContinuationTest {
    private class Fixture(val stream: Boolean = false) {
        val model = Model(modelId = "claude-sonnet-4-5")
        val setting = ProviderSetting.Claude(models = listOf(model))
        val provider = mockk<Provider<ProviderSetting.Claude>>()
        val manager = mockk<ProviderManager>()
        val assistant = Assistant(enableMemory = false, streamOutput = stream)
        val capture = TurnRunCapture()
        val requests = mutableListOf<List<me.rerere.ai.core.ModelRequestMessage>>()
        var checkpoints = 0
        val runner: TurnRunner
        init {
            every { manager.getProviderByType(setting) } returns provider
            runner = TurnRunner(
                context = mockk<Context>(relaxed = true), providerManager = manager, json = Json,
                attachmentResolver = mockk(relaxed = true), toolOutputStore = mockk(relaxed = true),
                artifactStore = mockk(relaxed = true),
            )
        }
        fun response(continueTurn: Boolean): MessageChunk {
            val part = UIMessagePart.ServerTool(
                ServerToolSource(ServerToolProtocol.CLAUDE_MESSAGES, "https://api.anthropic.com/v1/messages"),
                buildJsonObject { put("type", "server_tool_use"); put("id", "srv_${requests.size}"); put("name", "web_search") },
                complete = true,
            )
            return MessageChunk("response", model.modelId, listOf(UIMessageChoice(
                index = 0, delta = null,
                message = UIMessage(role = MessageRole.ASSISTANT, parts = if (continueTurn) listOf(part) else listOf(UIMessagePart.Text("done"))),
                finishReason = if (continueTurn) "pause_turn" else "end_turn",
                contentBlockSlots = listOf(ProviderContentBlockSlot("response_${requests.size}", 0)),
                continuation = if (continueTurn) ProviderContinuation.CONTINUE_ASSISTANT else null,
            )), ProviderUsageSnapshot(inputTokens = 10, outputTokens = 2, totalTokens = 12))
        }
        fun inputs(maxSteps: Int = 4, tools: List<me.rerere.ai.core.Tool> = emptyList(), checkpoint: suspend (TurnCheckpoint) -> Unit = {}) = turnRunInputsFixture(
            conversationId = Uuid.random(), settings = Settings(providers = listOf(setting), assistants = listOf(assistant)),
            model = model, assistant = assistant, messages = listOf(UIMessage.user("weather?")),
            mediaCapabilities = RequestMediaCapabilities.NONE, maxSteps = maxSteps, capture = capture, tools = tools,
            onCheckpoint = { if (it is ModelResponseCheckpoint) checkpoints++; checkpoint(it) },
        )
    }

    @Test fun `server only continuations commit before next request and retain same assistant and usage`() = runTest {
        val f = Fixture()
        coEvery { f.provider.generateText(f.setting, any(), any()) } answers {
            assertEquals(f.requests.size, f.checkpoints)
            f.requests += secondArg<List<me.rerere.ai.core.ModelRequestMessage>>()
            f.response(f.requests.size < 3)
        }
        val result = f.runner.run(f.inputs()) as TurnOutcome.Completed
        assertEquals(3, f.requests.size)
        assertEquals(2, f.checkpoints)
        assertEquals(3, f.capture.admissions.size)
        assertEquals(listOf(0, 1, 2), result.assistantMessage.parts.filterIsInstance<UIMessagePart.Step>().map { it.ordinal })
        assertEquals(2, f.requests.last().last().parts.filterIsInstance<UIMessagePart.ServerTool>().size)
        assertTrue(result.assistantMessage.getTools().isEmpty())
        assertEquals(1, f.capture.observations.map { it.id }.distinct().size)
        assertEquals(36L, result.assistantMessage.usage?.totalTokens)
    }

    @Test fun `checkpoint failure prevents continuation request`() = runTest {
        val f = Fixture()
        coEvery { f.provider.generateText(f.setting, any(), any()) } answers {
            f.requests += secondArg<List<me.rerere.ai.core.ModelRequestMessage>>()
            f.response(true)
        }
        val result = f.runner.run(f.inputs { if (it is ModelResponseCheckpoint) error("checkpoint unavailable") })
        assertTrue(result is TurnOutcome.Failed)
        assertEquals(1, f.requests.size)
    }

    @Test fun `continuations respect existing maximum step limit`() = runTest {
        val f = Fixture()
        coEvery { f.provider.generateText(f.setting, any(), any()) } answers {
            f.requests += secondArg<List<me.rerere.ai.core.ModelRequestMessage>>()
            f.response(true)
        }
        val result = f.runner.run(f.inputs(maxSteps = 2))
        assertTrue(result is TurnOutcome.Incomplete)
        assertEquals(2, f.requests.size)
        assertEquals(2, f.checkpoints)
    }

    @Test fun `mixed client tool still pauses for approval before continuation`() = runTest {
        val f = Fixture()
        var executed = false
        val tool = me.rerere.ai.core.Tool(
            name = "write", description = "write",
            interactionRequirement = { me.rerere.ai.core.ToolInteractionRequirement.Approval },
            execute = { executed = true; emptyList() },
        )
        coEvery { f.provider.generateText(f.setting, any(), any()) } answers {
            f.requests += secondArg<List<me.rerere.ai.core.ModelRequestMessage>>()
            val chunk = f.response(true)
            val choice = chunk.choices.single()
            chunk.copy(choices = listOf(choice.copy(
                message = choice.message!!.copy(parts = choice.message!!.parts + UIMessagePart.Tool(Uuid.NIL, Uuid.NIL, "local", "write", "{}")),
                toolCallSlots = listOf(ProviderToolCallSlot.Index(1)),
                contentBlockSlots = choice.contentBlockSlots + ProviderContentBlockSlot("response_1", 1),
            )))
        }
        val result = f.runner.run(f.inputs(tools = listOf(tool)))
        assertTrue(result is TurnPause)
        assertEquals(1, f.requests.size)
        assertFalse(executed)
        val step = f.capture.observations.last().parts.filterIsInstance<UIMessagePart.Step>().single()
        assertNull(step.outcome)
        assertEquals(ProviderContinuation.CONTINUE_ASSISTANT, step.modelResult?.continuation)
    }

    @Test fun `remote result never runs or approves same named local tool`() = runTest {
        val f = Fixture()
        var inspected = false
        var executed = false
        val tool = me.rerere.ai.core.Tool(name = "lookup", description = "lookup",
            interactionRequirement = { inspected = true; me.rerere.ai.core.ToolInteractionRequirement.Approval },
            execute = { executed = true; emptyList() })
        coEvery { f.provider.generateText(f.setting, any(), any()) } answers {
            MessageChunk("response", f.model.modelId, listOf(UIMessageChoice(0, null,
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
                    Uuid.NIL, Uuid.NIL, "remote", "lookup", "{}", output = listOf(UIMessagePart.Text("")),
                    resultStatus = ToolResultStatus.COMPLETED,
                    metadata = ResponseFunctionResultMetadata(ResponseFunctionResult("remote", kotlinx.serialization.json.JsonPrimitive(""),
                        "https://gateway.example/responses", OpenAIResponseSourceProfile.OPENAI_COMPATIBLE)).toMetadata(),
                ))), "completed")))
        }
        val result = f.runner.run(f.inputs(tools = listOf(tool))) as TurnOutcome.Completed
        assertFalse(inspected)
        assertFalse(executed)
        assertEquals(0, f.checkpoints)
        assertTrue(result.assistantMessage.getTools().single().hasReplayResult)
    }

    @Test fun `revoked access at next request boundary prevents provider IO`() = runTest {
        val f = Fixture()
        coEvery { f.provider.generateText(f.setting, any(), any()) } answers {
            f.requests += secondArg<List<me.rerere.ai.core.ModelRequestMessage>>()
            f.response(true)
        }
        val inputs = f.inputs()
        val original = inputs.requestContext
        val revoked = object : TurnRequestContextAccess by original {
            override suspend fun read(): TurnRequestHistory {
                if (f.checkpoints > 0) throw SecurityException("conversation_view_revoked")
                return original.read()
            }
        }
        assertTrue(f.runner.run(inputs.copy(requestContext = revoked)) is TurnOutcome.Failed)
        assertEquals(1, f.requests.size)
        assertEquals(1, f.checkpoints)
    }

    @Test fun `failed or cancelled stream after continuation signal cannot schedule another request`() = runTest {
        for (cancel in listOf(false, true)) {
            val f = Fixture(stream = true)
            coEvery { f.provider.streamText(f.setting, any(), any()) } returns flow {
                f.requests += emptyList<me.rerere.ai.core.ModelRequestMessage>()
                emit(f.response(true))
                if (cancel) throw CancellationException("cancelled") else error("connection failed")
            }
            val result = runCatching { f.runner.run(f.inputs()) }
            if (cancel) assertTrue(result.exceptionOrNull() is CancellationException)
            else assertTrue(result.getOrThrow() is TurnOutcome.Failed)
            assertEquals(1, f.requests.size)
            assertEquals(0, f.checkpoints)
            val step = f.capture.observations.last().parts.filterIsInstance<UIMessagePart.Step>().last()
            assertNull(step.modelResult?.continuation)
        }
    }
}
