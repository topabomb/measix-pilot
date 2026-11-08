package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.RequestImageSupport
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.OpenAIEndpointVendor
import me.rerere.ai.provider.providers.openai.resolveChatReasoningReplayPolicy
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.ImageEncodingMetadata
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.EncodedImage
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.RequestImageEncoder
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class RequestImageEncoderWireTest {
    @Test
    fun actualImageContainersReceiveTheSameRequestCapabilityWithTheirOwnPartPolicy() = runBlocking {
        val local = UIMessagePart.Image("file:///artifact/input.png")
        val original = local.copy(metadata = ImageEncodingMetadata(preserveOriginal = true).toMetadata())
        val messages = listOf(
            ModelRequestMessage(MessageRole.USER, listOf(local)),
            ModelRequestMessage(MessageRole.ASSISTANT, listOf(UIMessagePart.Tool(
                localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "capture",
                toolName = "capture_image", input = "{}", resultStatus = ToolResultStatus.COMPLETED,
                output = listOf(original),
            ))),
        )
        val capabilities = RequestMediaCapabilities(
            userImages = RequestImageSupport.STRUCTURED,
            toolOutputImages = RequestImageSupport.STRUCTURED,
        )
        val calls = mutableListOf<Pair<UIMessagePart.Image, Boolean>>()
        val encoder = RequestImageEncoder { part, prefix ->
            calls.add(part to prefix)
            val mime = if (part == original) "image/png" else "image/webp"
            val payload = if (part == original) "Ag==" else "AQ=="
            EncodedImage(if (prefix) "data:$mime;base64,$payload" else payload, mime)
        }
        val client = OkHttpClient()
        suspend fun verify(prefix: Boolean, build: suspend () -> Any) {
            val wire = build().toString()
            assertEquals(listOf(local to prefix, original to prefix), calls)
            assertTrue(wire.contains(if (prefix) "data:image/webp;base64,AQ==" else "AQ=="))
            assertTrue(wire.contains(if (prefix) "data:image/png;base64,Ag==" else "Ag=="))
            calls.clear()
        }
        verify(true) {
            ChatCompletionsAPI(client, KeyRoulette.default()).buildMessages(
                messages,
                resolveChatReasoningReplayPolicy(OpenAIEndpointVendor.COMPATIBLE, "fixture", true, true),
                capabilities, imageEncoder = encoder,
            )
        }
        verify(true) {
            ResponseAPI(client, KeyRoulette.default()).buildMessages(
                messages, mediaCapabilities = capabilities, imageEncoder = encoder,
            )
        }
        verify(false) {
            ClaudeProvider(client).buildMessages(
                messages, false, ClaudePromptCacheTtl.FIVE_MINUTES, capabilities, encoder,
            )
        }
        verify(false) {
            GoogleProvider(client).buildContents(messages, capabilities, "gemini-test", "fixture", encoder)
        }
    }
}
