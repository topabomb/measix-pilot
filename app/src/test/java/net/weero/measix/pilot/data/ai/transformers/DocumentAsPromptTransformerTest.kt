package net.weero.measix.pilot.data.ai.transformers

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.RequestImageSupport
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.files.ArtifactReadLease
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.turn.resolveTurnAssistantSnapshot
import net.weero.measix.pilot.test.testPromptInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class DocumentAsPromptTransformerTest {
    @Test
    fun `invalid structured documents fail before publishing extracted document context`() = runTest {
        val formats = listOf(
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "epub" to "application/epub+zip",
        )
        for ((extension, mime) in formats) {
            val file = kotlin.io.path.createTempFile("invalid-document", ".$extension").toFile()
            try {
                file.writeText("This is not a valid ZIP document")
                val ref = LocalArtifactRef(relativePath = "upload/${file.name}", mimeType = mime)
                val document = UIMessagePart.Document(url = ref.fileUri(File("managed")), fileName = file.name, mime = mime)
                val reads = mockk<ArtifactReadLease> {
                    every { resolveUri(document.url) } returns ref
                    every { file(ref) } returns file
                }
                var failure: Throwable? = null
                try {
                    DocumentAsPromptTransformer().transform(contextFor(reads),
                        listOf(UIMessage(role = MessageRole.USER, parts = listOf(document))))
                } catch (error: Exception) { failure = error }
                assertTrue("$extension parser must fail rather than return error text as document content", failure != null)
                assertTrue("$extension failure needs its original diagnostic", !failure?.message.isNullOrBlank())
            } finally { file.delete() }
        }
    }

    @Test
    fun `downloaded files remain references without automatic document body reads`() = runTest {
        val reads = mockk<ArtifactReadLease>()
        val text = document()
        val binary = UIMessagePart.Document(url = "file:///managed/archive.zip", fileName = "archive.zip", mime = "application/zip")
        val tool = UIMessagePart.Tool(localCallId = kotlin.uuid.Uuid.random(), stepId = kotlin.uuid.Uuid.random(),
            providerCallId = "download", toolName = "webdav_download", input = "{}", output = listOf(text, binary))
        val original = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        val result = DocumentAsPromptTransformer().transform(contextFor(reads), listOf(original)).single()
        assertEquals(original, result)
        verify(exactly = 0) { reads.resolveUri(any()) }
        verify(exactly = 0) { reads.file(any()) }
    }

    @Test
    fun `missing downloaded file drops document without changing successful execution fact`() {
        val rewriter = net.weero.measix.pilot.data.files.ToolArtifactRewriter(File("managed"), mockk())
        val ref = LocalArtifactRef(relativePath = "upload/file.zip", mimeType = "application/zip")
        val reads = mockk<ArtifactReadLease> { every { resolve(ref) } returns null }
        val output = rewriter.materializeToolOutput(reads,
            listOf(UIMessagePart.Text("""{"status":"completed","file":{"path":"/upload/file.zip"}}"""),
                UIMessagePart.Document(url = "file:///managed/upload/file.zip", fileName = "file.zip", mime = "application/zip")),
            rewriter.encodeArtifactRef(null, ref))
        assertEquals(1, output.size)
        val value = kotlinx.serialization.json.Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).toString()
        assertTrue(value.contains("\"status\":\"completed\""))
        assertTrue(value.contains("\"available\":false"))
        assertTrue(value.contains("artifact_missing"))
        assertTrue(!value.contains("/upload/"))
    }

    @Test
    fun `shared request pipeline keeps downloaded image references out of native context and degrades missing files`() = runTest {
        val rewriter = net.weero.measix.pilot.data.files.ToolArtifactRewriter(File("managed"), mockk())
        val ref = LocalArtifactRef(relativePath = "upload/image.png", mimeType = "image/png")
        val doc = UIMessagePart.Document(url = ref.fileUri(File("managed")), fileName = "image.png", mime = "image/png")
        val tool = UIMessagePart.Tool(localCallId = kotlin.uuid.Uuid.random(), stepId = kotlin.uuid.Uuid.random(),
            providerCallId = "download", toolName = "webdav_download", input = "{}", metadata = rewriter.encodeArtifactRef(null, ref),
            output = listOf(UIMessagePart.Text("""{"status":"completed","file":{"path":"/upload/image.png"}}"""), doc))
        val factory = net.weero.measix.pilot.service.turn.TurnPipelineFactory(
            TemplateTransformer(PebbleEngine.Builder().autoEscaping(false).build()), ToolArtifactReplayTransformer(rewriter),
            AttachmentProjectionTransformer(), mockk(), DocumentAsPromptTransformer())
        for (available in listOf(true, false)) {
            val reads = mockk<ArtifactReadLease> {
                every { resolve(ref) } returns if (available) ref else null
                every { resolveUri(any()) } returns if (available) ref else null
            }
            val ctx = contextFor(reads)
            val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
            val result = factory.input().fold(listOf(message)) { messages, transformer -> transformer.transform(ctx, messages) }
            val output = (result.single().parts.single() as UIMessagePart.Tool).output
            assertEquals(available, output.any { it is UIMessagePart.Document })
            assertTrue(output.none { it is UIMessagePart.Image })
            val text = output.filterIsInstance<UIMessagePart.Text>().joinToString { it.text }
            if (available) {
                assertTrue(text.contains("[Attachment path=/upload/image.png type=document]"))
                assertTrue(output.filterIsInstance<UIMessagePart.Text>().any { ctx.requestOrigins.source(it) is RequestPartSource.AttachmentInput })
            } else {
                assertTrue(text.contains("artifact_missing"))
                assertTrue(!text.contains("/upload/"))
            }
            verify(exactly = 0) { reads.file(any()) }
        }
    }

    private fun contextFor(reads: ArtifactReadLease) = TransformerContext(
        realmAccess = RealmAccess.Personal, context = mockk(), model = Model(modelId = "test", displayName = "Test"),
        assistant = resolveTurnAssistantSnapshot(Assistant()),
        promptInputs = testPromptInputs(messageTemplate = "PREFIX[{{ message }}]", placeholderValues = mapOf("char" to "REPLACED")),
        requestOrigins = RequestMessageOriginTracker(), artifactReads = reads, registerUnpublishedResource = {},
        mediaCapabilities = RequestMediaCapabilities(toolOutputImages = RequestImageSupport.STRUCTURED),
    )

    private fun document(name: String = "doc.txt") = UIMessagePart.Document(
        url = "file:///managed/$name", fileName = name, mime = "text/plain",
    )

    @Test
    fun `unmanaged document is never read or treated as successful background`() = runTest {
        val reads = mockk<ArtifactReadLease>()
        coEvery { reads.resolveUri(any()) } returns null
        try {
            DocumentAsPromptTransformer().transform(contextFor(reads), listOf(UIMessage(role = MessageRole.USER, parts = listOf(document()))))
            throw AssertionError("unavailable document must fail")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("document_unavailable: doc.txt"))
        }
        verify(exactly = 0) { reads.file(any()) }
    }

    @Test
    fun `mime mismatch does not open a file`() = runTest {
        val reads = mockk<ArtifactReadLease>()
        coEvery { reads.resolveUri(any()) } returns LocalArtifactRef(relativePath = "upload/doc.txt", mimeType = "application/pdf")
        try {
            DocumentAsPromptTransformer().transform(contextFor(reads), listOf(UIMessage(role = MessageRole.USER, parts = listOf(document()))))
            throw AssertionError("mime mismatch must fail")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message.orEmpty().contains("document_mime_mismatch"))
        }
        verify(exactly = 0) { reads.file(any()) }
    }

    @Test
    fun `document attributes escape metacharacters and fences contain every backtick run`() {
        val text = renderDocumentInput("a\"<&>\n.txt", "/upload/a\"&.txt", "inside ``` and ````` fences {{char}}")
        assertTrue(text.startsWith("<UploadFile name=\"a&quot;&lt;&amp;&gt;&#10;.txt\" path=\"/upload/a&quot;&amp;.txt\">"))
        assertTrue(text.contains("\n``````\ninside ``` and ````` fences {{char}}\n``````\n"))
        assertTrue(!renderDocumentInput("a", null, "").contains("path="))
    }

    @Test
    fun `multiple documents retain attachment order and are opaque to templates`() = runTest {
        val files = listOf("first {{char}} {% if true %}literal{% endif %}", "second ````")
            .map { content -> kotlin.io.path.createTempFile("doc-input", ".txt").toFile().apply { writeText(content) } }
        try {
            val reads = mockk<ArtifactReadLease>()
            val documents = files.mapIndexed { index, file ->
                val doc = document("doc$index.txt")
                val ref = LocalArtifactRef(relativePath = "upload/${file.name}", mimeType = "text/plain")
                coEvery { reads.resolveUri(doc.url) } returns ref
                every { reads.file(ref) } returns file
                doc
            }
            val ctx = contextFor(reads)
            val original = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hello {char}")) + documents)
            val result = DocumentAsPromptTransformer().transform(ctx, listOf(original))
            val parts = result.single().parts
            assertEquals(listOf("doc0.txt", "doc1.txt"), parts.filterIsInstance<UIMessagePart.Document>().map { it.fileName })
            assertTrue((parts[1] as UIMessagePart.Text).text.contains("first {{char}}"))
            assertTrue((parts[3] as UIMessagePart.Text).text.contains("second ````"))
            assertEquals(RequestPartSource.DocumentInput(documents[0]), ctx.requestOrigins.source(parts[1]))
            val afterPlaceholder = PlaceholderTransformer.transform(ctx, result)
            val afterTemplate = TemplateTransformer(PebbleEngine.Builder().autoEscaping(false).build()).transform(ctx, afterPlaceholder)
            assertEquals("PREFIX[hello REPLACED]", (afterTemplate.single().parts[0] as UIMessagePart.Text).text)
            assertSame(parts[1], afterTemplate.single().parts[1])
            assertSame(parts[3], afterTemplate.single().parts[3])
        } finally { files.forEach(File::delete) }
    }

    @Test
    fun `read owner diagnostics and cancellation propagate unchanged`() = runTest {
        val cause = IOException("disk failure")
        val failures = listOf(IOException("read denied", cause), CancellationException("cancel read"))
        for (expected in failures) {
            val reads = mockk<ArtifactReadLease>()
            coEvery { reads.resolveUri(any()) } throws expected
            try {
                DocumentAsPromptTransformer().transform(contextFor(reads), listOf(UIMessage(role = MessageRole.USER, parts = listOf(document()))))
                throw AssertionError("read must fail")
            } catch (actual: Exception) {
                assertEquals(expected::class, actual::class)
                assertEquals(expected.message, actual.message)
                val chain = generateSequence<Throwable>(actual) { it.cause }.toList()
                assertTrue("the original failure must remain visible", chain.any { it === expected })
                expected.cause?.let { originalCause ->
                    assertTrue("the original cause must remain visible", chain.any { it === originalCause })
                }
                if (expected is CancellationException) assertTrue(actual is CancellationException)
            }
        }
    }
    @Test
    fun `missing lease file fails with the original document identity`() = runTest {
        val reads = mockk<ArtifactReadLease>()
        val ref = LocalArtifactRef(relativePath = "upload/doc.txt", mimeType = "text/plain")
        coEvery { reads.resolveUri(any()) } returns ref
        every { reads.file(ref) } returns null
        try {
            DocumentAsPromptTransformer().transform(contextFor(reads), listOf(UIMessage(role = MessageRole.USER, parts = listOf(document()))))
            throw AssertionError("missing lease file must fail")
        } catch (failure: java.io.FileNotFoundException) {
            assertEquals("Managed document unavailable: doc.txt", failure.message)
        }
    }
}
