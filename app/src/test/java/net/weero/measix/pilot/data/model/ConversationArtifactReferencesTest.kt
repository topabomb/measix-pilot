package net.weero.measix.pilot.data.model

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.ui.ToolOutputArchive
import me.rerere.ai.ui.ToolOutputArchiveRef
import me.rerere.ai.ui.ToolRuntimeState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationArtifactReferencesTest {
    @Test
    fun `archived tool output becomes a durable TOOL_OUTPUT reference`() {
        val reference = listOf(messageWithArchive(7, "tool_outputs/a.txt")).collectArtifactReferences().single()
        assertEquals("tool_outputs/a.txt", reference.token)
        assertEquals(ArtifactReferenceType.TOOL_OUTPUT, reference.type)
        assertEquals(7L, reference.expectedArtifactId)
    }

    @Test
    fun `a tool without an archive contributes no durable reference`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call",
                    toolName = "tool",
                    input = "{}",
                ),
            ),
        )
        assertTrue(listOf(message).collectArtifactReferences().isEmpty())
    }

    @Test
    fun `nested output media is retained but remote URLs are not local artifacts`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                tool(output = listOf(UIMessagePart.Image(url = "file:///tmp/nested.png"))),
                UIMessagePart.Document(url = "https://example.com/remote.pdf", fileName = "remote.pdf", mime = "application/pdf"),
            ),
        )
        assertEquals(setOf("file:///tmp/nested.png"), listOf(message).collectFileReferenceTokens())
    }

    @Test
    fun `literal file mentions do not retain artifacts`() {
        val literal = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("see file:///tmp/keep.txt")))
        val attached = UIMessage(role = MessageRole.USER, parts = listOf(
            UIMessagePart.Document(url = "file:///tmp/keep.txt", fileName = "keep.txt", mime = "text/plain"),
        ))
        assertTrue(listOf(literal).collectArtifactReferences().isEmpty())
        assertEquals(setOf("file:///tmp/keep.txt"), listOf(attached).collectFileReferenceTokens())
    }

    @Test
    fun `direct and subassistant artifact metadata contribute attachment references`() {
        fun artifact(path: String) = buildJsonObject {
            put("version", 1)
            put("relativePath", path)
            put("mimeType", "image/png")
        }
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            tool().copy(metadata = buildJsonObject {
                put("artifact", artifact("upload/generated.png"))
                put("sub_assistant_call", buildJsonObject {
                    put("artifacts", buildJsonArray {
                        add(buildJsonObject {
                            put("ref", "attachment:11111111-1111-1111-1111-111111111111")
                            put("type", "image")
                            put("mime", "image/png")
                            put("artifact", artifact("upload/delegated.png"))
                        })
                    })
                })
            }),
        ))
        assertEquals(
            setOf(
                MessageArtifactReference("upload/generated.png", ArtifactReferenceType.ATTACHMENT),
                MessageArtifactReference("upload/delegated.png", ArtifactReferenceType.ATTACHMENT),
            ),
            listOf(message).collectArtifactReferences().toSet(),
        )
    }

    private fun tool(output: List<UIMessagePart> = emptyList()) = UIMessagePart.Tool(
        localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call",
        toolName = "tool", input = "{}", output = output,
    )

    private fun messageWithArchive(ref: Long, relativePath: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call",
                toolName = "tool",
                input = "{}",
                runtimeState = ToolRuntimeState(
                    outputPolicy = ToolOutputPolicy.ARCHIVABLE_TEXT,
                    archive = ToolOutputArchive(
                        ref = ref,
                        artifact = ToolOutputArchiveRef(relativePath, "text/plain"),
                        characters = 12,
                        lines = 2,
                    ),
                ),
            ),
        ),
    )
}
