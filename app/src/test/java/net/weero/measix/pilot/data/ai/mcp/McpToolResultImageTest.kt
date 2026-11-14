package net.weero.measix.pilot.data.ai.mcp

import android.app.Application
import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.RequestImageSupport
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageEncodingMetadata
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.OwnedArtifact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class McpToolResultImageTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun `text image order and original image payload survive projection and Responses request`() = runBlocking {
        val bytes = ByteArrayOutputStream().use { output ->
            ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", output)
            output.toByteArray()
        }
        val file = temporaryFolder.newFile("result.png").apply { writeBytes(bytes) }
        McpProviderRequestRecorder().use { recorder ->
            for (unique in listOf(false, true)) {
                val store = mockk<ArtifactStore>()
                val owned = mockk<OwnedArtifact> { every { uri } returns Uri.fromFile(file) }
                coEvery { store.createFromBytes(ConfigurationScope.Personal, any(), "mcp_image.png", "image/png",
                    origin = ArtifactOrigin.SYSTEM) } returns owned
                val created = mutableListOf<OwnedArtifact>()
                val text = """{"applied":true}"""
                val structured = Json.parseToJsonElement(if (unique) """{"applied":true,"record":42}""" else text)
                val tool = executeMcpResult(CallToolResult(
                    content = listOf(
                        TextContent(text), ImageContent(Base64.encode(bytes), "image/png"), TextContent("after"),
                    ),
                    structuredContent = structured,
                ), store, created::add)
                val image = tool.output[1] as UIMessagePart.Image
                assertEquals(ToolResultStatus.COMPLETED, tool.resultStatus)
                assertEquals(Uri.fromFile(file).toString(), image.url)
                assertEquals(true, image.metadataAs<ImageEncodingMetadata>()?.preserveOriginal)
                assertNotNull(AttachmentRefs.getStableRef(image))
                val expected = buildList {
                    addAll(listOf(UIMessagePart.Text(text), image, UIMessagePart.Text("after")))
                    if (unique) add(UIMessagePart.Text(buildJsonObject {
                        put("structured_content", structured)
                    }.toString()))
                }
                assertEquals(expected, tool.output)
                assertEquals(listOf(owned), created)
                coVerify(exactly = 1) {
                    store.createFromBytes(ConfigurationScope.Personal, match { it.contentEquals(bytes) },
                        "mcp_image.png", "image/png", origin = ArtifactOrigin.SYSTEM)
                }
                coVerify(exactly = 0) { store.discardUnpublished(any()) }

                val params = TextGenerationParams(Model(modelId = "test"), mediaCapabilities = RequestMediaCapabilities(
                    toolOutputImages = RequestImageSupport.STRUCTURED,
                ))
                val body = recorder.capture(tool, responses = true, params = params)
                val parts = recorder.toolOutput(body, responses = true).jsonArray.map { it.jsonObject }
                assertEquals(
                    if (unique) listOf("input_text", "input_image", "input_text", "input_text")
                    else listOf("input_text", "input_image", "input_text"),
                    parts.map { it.getValue("type").jsonPrimitive.content },
                )
                assertEquals(text, parts[0].getValue("text").jsonPrimitive.content)
                assertEquals("data:image/png;base64,${Base64.encode(bytes)}",
                    parts[1].getValue("image_url").jsonPrimitive.content)
                assertEquals("after", parts[2].getValue("text").jsonPrimitive.content)
                if (unique) assertEquals((expected.last() as UIMessagePart.Text).text,
                    parts.last().getValue("text").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `remote errors retain diagnostics and unique structured fields while omitting error images`() = runBlocking {
        val store = mockk<ArtifactStore>()
        val text = """{"code":"DENIED","error":"original remote detail"}"""
        McpProviderRequestRecorder().use { recorder ->
            for (unique in listOf(false, true)) {
                val structured = Json.parseToJsonElement(if (unique) {
                    """{"code":"DENIED","error":"original remote detail","hint":"refresh permissions"}"""
                } else text)
                val tool = executeMcpResult(CallToolResult(
                    content = listOf(
                        TextContent(text), ImageContent("invalid", "image/png"), TextContent("remote diagnostic tail"),
                    ),
                    structuredContent = structured, isError = true,
                ), store, { error("Error images must not create an Artifact") })
                assertEquals(ToolResultStatus.FAILED, tool.resultStatus)
                val envelope = Json.parseToJsonElement((tool.output.first() as UIMessagePart.Text).text).jsonObject
                assertEquals("failed", envelope.getValue("status").jsonPrimitive.content)
                assertEquals("remote_error", envelope.getValue("reason").jsonPrimitive.content)
                if (unique) assertEquals(structured, envelope["structured_content"])
                else assertFalse(envelope.containsKey("structured_content"))
                assertEquals(listOf(UIMessagePart.Text(text), UIMessagePart.Text("[Remote MCP error image omitted]"),
                    UIMessagePart.Text("remote diagnostic tail")), tool.output.drop(1))
                val body = recorder.capture(tool, responses = false)
                assertEquals(tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text },
                    recorder.toolOutput(body, responses = false).jsonPrimitive.content)
            }
        }
        coVerify(exactly = 0) { store.createFromBytes(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { store.discardUnpublished(any()) }
    }
}
