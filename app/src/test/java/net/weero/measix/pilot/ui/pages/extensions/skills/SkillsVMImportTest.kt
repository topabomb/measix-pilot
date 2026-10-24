package net.weero.measix.pilot.ui.pages.extensions.skills

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.joinAll
import net.weero.measix.pilot.data.files.SkillFileSaveResult
import net.weero.measix.pilot.data.files.SkillManager
import net.weero.measix.pilot.data.files.SkillBundleImportResult
import net.weero.measix.pilot.data.files.SkillMetadata
import net.weero.measix.pilot.data.files.SkillFrontmatterParser
import net.weero.measix.pilot.data.files.SkillParseResult
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import org.yaml.snakeyaml.error.MarkedYAMLException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SkillsVMImportTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `GitHub failure retains original cause and retry can publish`() = runTest(dispatcher) {
        val manager = mockk<SkillManager>()
        every { manager.listSkills() } returns emptyList()
        every { manager.listInvalidSkillDirectories() } returns emptyList()
        val source = mockk<SkillGitHubSource>()
        val failure = java.io.IOException("transport detail", IllegalStateException("original cause"))
        coEvery { source.listFiles("owner", "repo", "HEAD", "") } throws failure andThen listOf(SkillGitHubFile("SKILL.md", "skill-url"))
        coEvery { source.downloadBytes("skill-url") } returns "---\nname: retry\ndescription: valid\n---\nbody".toByteArray()
        coEvery { manager.saveSkillFileBytesAtomically("retry", any()) } returns SkillFileSaveResult.SUCCESS
        val vm = SkillsVM(manager, dispatcher, source)
        val first = CompletableDeferred<SkillImportOutcome>()
        vm.importSkillFromGitHub("https://github.com/owner/repo", first::complete)
        val rejected = first.await() as SkillImportOutcome.Failure
        org.junit.Assert.assertSame(failure, rejected.cause)
        assertEquals(SkillImportFailure.UNKNOWN, rejected.reason)
        val retry = CompletableDeferred<SkillImportOutcome>()
        vm.importSkillFromGitHub("https://github.com/owner/repo", retry::complete)
        assertEquals(SkillImportOutcome.Success("retry"), retry.await())
    }

    @Test
    fun `markdown import uses typed name and the single-skill atomic owner`() = runTest(dispatcher) {
        val content = "---\nname: markdown-skill\ndescription: typed\n---\nbody"
        val manager = mockk<SkillManager>()
        every { manager.listSkills() } returns emptyList()
        every { manager.listInvalidSkillDirectories() } returns emptyList()
        coEvery { manager.saveSkill("markdown-skill", content) } returns SkillFileSaveResult.SUCCESS
        val (context, uri) = fileInput("SKILL.md", content.toByteArray())
        val result = CompletableDeferred<SkillImportOutcome>()

        SkillsVM(manager, dispatcher).importSkillFromFile(context, uri, result::complete)

        assertEquals(SkillImportOutcome.Success("markdown-skill"), withTimeout(5_000) { result.await() })
        coVerify(exactly = 1) { manager.saveSkill("markdown-skill", content) }
    }

    @Test
    fun `zip import publishes one validated bundle transaction`() = runTest(dispatcher) {
        val manager = mockk<SkillManager>()
        every { manager.listSkills() } returns emptyList()
        every { manager.listInvalidSkillDirectories() } returns emptyList()
        coEvery { manager.importSkillBundleAtomically(any()) } returns SkillBundleImportResult.SUCCESS
        val bytes = zip(
            "bundle/SKILL.md" to "---\nname: zip-skill\ndescription: typed\n---\nbody".toByteArray(),
            "bundle/notes/info.txt" to "asset".toByteArray(),
        )
        val (context, uri) = fileInput("skills.zip", bytes)
        val result = CompletableDeferred<SkillImportOutcome>()

        SkillsVM(manager, dispatcher).importSkillFromFile(context, uri, result::complete)

        assertEquals(SkillImportOutcome.Success("zip-skill"), withTimeout(5_000) { result.await() })
        coVerify(exactly = 1) {
            manager.importSkillBundleAtomically(
                match { entries ->
                    entries.size == 1 &&
                        entries.single().name == "zip-skill" &&
                        entries.single().files.keys == setOf("SKILL.md", "notes/info.txt") &&
                        entries.single().files.getValue("notes/info.txt").contentEquals("asset".toByteArray())
                },
            )
        }
    }

    @Test
    fun `invalid frontmatter fails closed before any save`() = runTest(dispatcher) {
        val manager = mockk<SkillManager>()
        every { manager.listSkills() } returns emptyList()
        every { manager.listInvalidSkillDirectories() } returns emptyList()
        val (context, uri) = fileInput(
            "SKILL.md",
            "---\nname: bad\ndescription: [not, text]\n---\nbody".toByteArray(),
        )
        val result = CompletableDeferred<SkillImportOutcome>()

        SkillsVM(manager, dispatcher).importSkillFromFile(context, uri, result::complete)

        val failure = withTimeout(5_000) { result.await() } as SkillImportOutcome.Failure
        assertEquals(SkillImportFailure.INVALID_SKILL, failure.reason)
        assertTrue(requireNotNull(failure.cause).userVisibleDiagnostic().contains("description"))
        coVerify(exactly = 0) { manager.saveSkill(any(), any()) }
        coVerify(exactly = 0) { manager.saveSkillFileBytesAtomically(any(), any()) }
        coVerify(exactly = 0) { manager.importSkillBundleAtomically(any()) }
    }

    @Test
    fun `invalid second skill rejects the zip before bundle publication`() = runTest(dispatcher) {
        val manager = mockk<SkillManager>()
        every { manager.listSkills() } returns emptyList()
        every { manager.listInvalidSkillDirectories() } returns emptyList()
        val bytes = zip(
            "first/SKILL.md" to "---\nname: first\ndescription: valid\n---\nbody".toByteArray(),
            "second/SKILL.md" to "---\nname: second\ndescription: [invalid]\n---\nbody".toByteArray(),
        )
        val (context, uri) = fileInput("skills.zip", bytes)
        val result = CompletableDeferred<SkillImportOutcome>()

        SkillsVM(manager, dispatcher).importSkillFromFile(context, uri, result::complete)

        val failure = withTimeout(5_000) { result.await() } as SkillImportOutcome.Failure
        assertEquals(SkillImportFailure.INVALID_SKILL, failure.reason)
        val diagnostic = requireNotNull(failure.cause).userVisibleDiagnostic()
        assertTrue(diagnostic.contains("second/SKILL.md"))
        assertTrue(diagnostic.contains("description"))
        coVerify(exactly = 0) { manager.importSkillBundleAtomically(any()) }
    }

    @Test
    fun `malformed YAML retains parser location and cause for every import route`() = runTest(dispatcher) {
        val content = "---\nname: broken\ndescription: [unclosed\n---\nPRIVATE_BODY_NOT_DIAGNOSTIC"
        for (route in listOf("markdown", "zip", "github")) {
            val manager = mockk<SkillManager>()
            every { manager.listSkills() } returns emptyList()
            every { manager.listInvalidSkillDirectories() } returns emptyList()
            val source = mockk<SkillGitHubSource>()
            val vm = SkillsVM(manager, dispatcher, source)
            val result = CompletableDeferred<SkillImportOutcome>()
            if (route == "github") {
                coEvery { source.listFiles("owner", "repo", "HEAD", "") } returns listOf(SkillGitHubFile("SKILL.md", "skill-url"))
                coEvery { source.downloadBytes("skill-url") } returns content.toByteArray()
                vm.importSkillFromGitHub("https://github.com/owner/repo", result::complete).join()
            } else {
                val (context, uri) = if (route == "zip") fileInput("skills.zip", zip("broken/SKILL.md" to content.toByteArray()))
                    else fileInput("SKILL.md", content.toByteArray())
                vm.importSkillFromFile(context, uri, result::complete).join()
            }
            val failure = result.await() as SkillImportOutcome.Failure
            assertEquals(SkillImportFailure.INVALID_SKILL, failure.reason)
            val error = requireNotNull(failure.cause)
            val parserError = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<MarkedYAMLException>().single()
            assertTrue(parserError.problemMark != null)
            val diagnostic = error.userVisibleDiagnostic()
            assertTrue(diagnostic.contains("line"))
            assertTrue(diagnostic.contains("column"))
            assertTrue(!diagnostic.contains("PRIVATE_BODY_NOT_DIAGNOSTIC"))
            if (route == "zip") assertTrue(diagnostic.contains("broken/SKILL.md"))
            coVerify(exactly = 0) { manager.saveSkill(any(), any()) }
            coVerify(exactly = 0) { manager.saveSkillFileBytesAtomically(any(), any()) }
            coVerify(exactly = 0) { manager.importSkillBundleAtomically(any()) }
        }
    }

    @Test
    fun `unexpected parser failure stays unknown and cancellation never completes import`() = runTest(dispatcher) {
        mockkObject(SkillFrontmatterParser)
        try {
            for (zipInput in listOf(false, true)) for (cancel in listOf(false, true)) {
                val manager = mockk<SkillManager>()
                every { manager.listSkills() } returns emptyList()
                every { manager.listInvalidSkillDirectories() } returns emptyList()
                val error = if (cancel) CancellationException("parser cancelled") else IllegalArgumentException("release-parser-sentinel")
                every { SkillFrontmatterParser.parseDocument(any()) } returns SkillParseResult.Error("parser detail", error)
                val content = "---\nname: test\ndescription: test\n---\nbody".toByteArray()
                val (context, uri) = if (zipInput) fileInput("skills.zip", zip("test/SKILL.md" to content))
                    else fileInput("SKILL.md", content)
                val result = CompletableDeferred<SkillImportOutcome>()
                val job = SkillsVM(manager, dispatcher).importSkillFromFile(context, uri, result::complete)
                job.join()
                if (cancel) {
                    assertTrue(job.isCancelled)
                    assertTrue(!result.isCompleted)
                } else {
                    val failure = result.await() as SkillImportOutcome.Failure
                    assertEquals(SkillImportFailure.UNKNOWN, failure.reason)
                    org.junit.Assert.assertSame(error, failure.cause)
                    assertTrue(requireNotNull(failure.cause).userVisibleDiagnostic().contains("release-parser-sentinel"))
                }
                coVerify(exactly = 0) { manager.saveSkill(any(), any()) }
                coVerify(exactly = 0) { manager.importSkillBundleAtomically(any()) }
            }
        } finally {
            unmockkObject(SkillFrontmatterParser)
        }
    }

    @Test
    fun `oversized markdown is rejected before parsing or publication`() = runTest(dispatcher) {
        val manager = mockk<SkillManager>()
        every { manager.listSkills() } returns emptyList()
        every { manager.listInvalidSkillDirectories() } returns emptyList()
        val (context, uri) = fileInput("SKILL.md", ByteArray(4 * 1024 * 1024 + 1) { 'a'.code.toByte() })
        val result = CompletableDeferred<SkillImportOutcome>()

        SkillsVM(manager, dispatcher).importSkillFromFile(context, uri, result::complete)

        assertEquals(
            SkillImportOutcome.Failure(SkillImportFailure.RESOURCE_LIMIT),
            withTimeout(5_000) { result.await() },
        )
        coVerify(exactly = 0) { manager.saveSkill(any(), any()) }
        coVerify(exactly = 0) { manager.importSkillBundleAtomically(any()) }
    }

    @Test
    fun `oversized zip entry is rejected while streaming decompression`() = runTest(dispatcher) {
        val manager = mockk<SkillManager>()
        every { manager.listSkills() } returns emptyList()
        every { manager.listInvalidSkillDirectories() } returns emptyList()
        val bytes = zip(
            "bundle/SKILL.md" to "---\nname: zip-skill\ndescription: valid\n---\nbody".toByteArray(),
            "bundle/large.bin" to ByteArray(4 * 1024 * 1024 + 1),
        )
        val (context, uri) = fileInput("skills.zip", bytes)
        val result = CompletableDeferred<SkillImportOutcome>()

        SkillsVM(manager, dispatcher).importSkillFromFile(context, uri, result::complete)

        assertEquals(
            SkillImportOutcome.Failure(SkillImportFailure.RESOURCE_LIMIT),
            withTimeout(5_000) { result.await() },
        )
        coVerify(exactly = 0) { manager.importSkillBundleAtomically(any()) }
    }

    @Test
    fun `duplicate skill names reject the complete bundle`() = runTest(dispatcher) {
        val manager = mockk<SkillManager>()
        every { manager.listSkills() } returns emptyList()
        every { manager.listInvalidSkillDirectories() } returns emptyList()
        val bytes = zip(
            "first/SKILL.md" to "---\nname: duplicate\ndescription: first\n---\nbody".toByteArray(),
            "second/SKILL.md" to "---\nname: duplicate\ndescription: second\n---\nbody".toByteArray(),
        )
        val (context, uri) = fileInput("skills.zip", bytes)
        val result = CompletableDeferred<SkillImportOutcome>()

        SkillsVM(manager, dispatcher).importSkillFromFile(context, uri, result::complete)

        assertEquals(
            SkillImportOutcome.Failure(SkillImportFailure.INVALID_SKILL),
            withTimeout(5_000) { result.await() },
        )
        coVerify(exactly = 0) { manager.importSkillBundleAtomically(any()) }
    }

    @Test
    fun `GitHub import parses the root skill and atomically publishes every listed file`() = runTest(dispatcher) {
        val manager = mockk<SkillManager>()
        val source = mockk<SkillGitHubSource>()
        every { manager.listSkills() } returns emptyList()
        every { manager.listInvalidSkillDirectories() } returns emptyList()
        lateinit var published: Map<String, ByteArray>
        coEvery { manager.saveSkillFileBytesAtomically("github-skill", any()) } coAnswers {
            published = secondArg()
            SkillFileSaveResult.SUCCESS
        }
        coEvery { source.listFiles("owner", "repo", "main", "skills/example") } returns listOf(
            SkillGitHubFile("SKILL.md", "skill-url"),
            SkillGitHubFile("references/info.md", "reference-url"),
        )
        val skillDocument = "---\nname: github-skill\ndescription: typed\n---\nbody".toByteArray()
        val binaryReference = byteArrayOf(0x00, 0xFF.toByte(), 0xC3.toByte(), 0x28)
        coEvery { source.downloadBytes("skill-url") } returns skillDocument
        coEvery { source.downloadBytes("reference-url") } returns binaryReference
        val result = CompletableDeferred<SkillImportOutcome>()

        SkillsVM(manager, dispatcher, source).importSkillFromGitHub(
            "https://github.com/owner/repo/tree/main/skills/example",
            result::complete,
        )

        assertEquals(SkillImportOutcome.Success("github-skill"), withTimeout(5_000) { result.await() })
        coVerify(exactly = 1) { manager.saveSkillFileBytesAtomically("github-skill", any()) }
        assertArrayEquals(skillDocument, published.getValue("SKILL.md"))
        assertArrayEquals(binaryReference, published.getValue("references/info.md"))
    }

    @Test
    fun `cancelling GitHub import before commit publishes nothing`() = runTest(dispatcher) {
        val manager = mockk<SkillManager>()
        val source = mockk<SkillGitHubSource>()
        val secondDownloadStarted = CompletableDeferred<Unit>()
        val releaseSecondDownload = CompletableDeferred<Unit>()
        every { manager.listSkills() } returns emptyList()
        every { manager.listInvalidSkillDirectories() } returns emptyList()
        coEvery { source.listFiles(any(), any(), any(), any()) } returns listOf(
            SkillGitHubFile("SKILL.md", "skill-url"),
            SkillGitHubFile("references/info.md", "reference-url"),
        )
        coEvery { source.downloadBytes("skill-url") } returns
            "---\nname: cancelled-skill\ndescription: typed\n---\nbody".toByteArray()
        coEvery { source.downloadBytes("reference-url") } coAnswers {
            secondDownloadStarted.complete(Unit)
            releaseSecondDownload.await()
            "reference".toByteArray()
        }

        val job = SkillsVM(manager, dispatcher, source).importSkillFromGitHub(
            "https://github.com/owner/repo/tree/main/skills/example",
        ) { error("cancelled import must not publish a UI result") }
        secondDownloadStarted.await()
        job.cancel()
        releaseSecondDownload.complete(Unit)
        joinAll(job)

        coVerify(exactly = 0) { manager.saveSkillFileBytesAtomically(any(), any()) }
        coVerify(exactly = 0) { manager.saveSkill(any(), any()) }
    }

    private fun fileInput(fileName: String, bytes: ByteArray): Pair<Context, Uri> {
        val resolver = mockk<ContentResolver>()
        val context = mockk<Context>()
        val uri = mockk<Uri>()
        every { context.applicationContext } returns context
        every { context.contentResolver } returns resolver
        every { uri.scheme } returns "file"
        every { uri.path } returns File("/tmp", fileName).path
        every { resolver.openInputStream(uri) } returns ByteArrayInputStream(bytes)
        return context to uri
    }

    private fun metadata(name: String) = SkillMetadata(name = name, description = "typed")

    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            files.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content)
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }
}
