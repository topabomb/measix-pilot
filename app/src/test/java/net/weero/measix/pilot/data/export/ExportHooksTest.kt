package net.weero.measix.pilot.data.export

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.core.content.FileProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.service.MediaExportService
import net.weero.measix.pilot.service.CreatedExportDocument
import kotlinx.serialization.SerializationException
import net.weero.measix.pilot.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExportHooksTest {
    @Before fun prepareFileProvider() {
        // JVM tests have no merged manifest; preserve the exact exported file identity at this Android boundary.
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any(), any()) } answers { Uri.fromFile(thirdArg<File>()) }
    }

    @After fun releaseFileProvider() { unmockkStatic(FileProvider::class) }

    @Test
    fun `sharing a prompt name cannot overwrite a file outside its export directory`() = runTest {
        val context = SharingContext()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val exports = MediaExportService(mockk(), backgroundScope)
        val victim = File(context.filesDir, "export-victim.json").apply { writeText("existing private data") }
        try {
            val prompt = PromptInjection.ModeInjection(name = "../../files/export-victim")
            exports.sharePrepared(context, exports.prepareModeInjectionExport(prompt))
            assertEquals("existing private data", victim.readText())
            assertEquals(ModeInjectionSerializer.exportToJson(prompt), context.readShared(context.shared.single()))
        } finally { Dispatchers.resetMain(); victim.delete() }
    }

    @Test
    fun `sharing the same name again preserves the payload of the previous shared uri`() = runTest {
        val context = SharingContext()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val exports = MediaExportService(mockk(), backgroundScope)
        val first = PromptInjection.ModeInjection(name = "shared prompt", content = "first payload")
        val second = first.copy(content = "second payload")
        try {
            exports.sharePrepared(context, exports.prepareModeInjectionExport(first))
            val firstUri = context.shared.single()
            exports.sharePrepared(context, exports.prepareModeInjectionExport(second))
            val secondUri = context.shared.last()
            assertNotEquals(firstUri, secondUri)
            assertEquals(ModeInjectionSerializer.exportToJson(first), context.readShared(firstUri))
            assertEquals(ModeInjectionSerializer.exportToJson(second), context.readShared(secondUri))
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `prepared SAF writes retain close failures and consume the request exactly once`() = runTest {
        val context = mockk<Context>()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        val uri = Uri.parse("content://documents/document/new")
        val closeFailure = IOException("Provider could not close the document")
        val output = object : ByteArrayOutputStream() {
            override fun close() { throw closeFailure }
        }
        every { resolver.openOutputStream(uri, "wt") } returns output
        val exports = MediaExportService(mockk(), backgroundScope)
        val request = exports.prepareModeInjectionExport(PromptInjection.ModeInjection(name = "Selected"))
        mockkStatic(DocumentsContract::class)
        var cleanupAttempts = 0
        every { DocumentsContract.deleteDocument(resolver, uri) } answers { cleanupAttempts++; false }
        try {
            val failure = runCatching { exports.savePreparedDocument(context, CreatedExportDocument(uri), request) }.exceptionOrNull()
            // Coroutine stack recovery can copy the IOException and retain the original as its cause.
            val original = generateSequence(failure) { it.cause }.last()
            assertSame(closeFailure, original)
            assertTrue(requireNotNull(failure).suppressed.any { it.message == "document_cleanup_failed" })
            assertTrue(output.size() > 0)
            val retry = runCatching { exports.savePreparedDocument(context, CreatedExportDocument(uri), request) }.exceptionOrNull()
            assertEquals("document_result_already_claimed", retry?.message)
            assertEquals(1, cleanupAttempts)
        } finally {
            unmockkStatic(DocumentsContract::class)
        }
    }

    @Test
    fun `duplicate SAF result cannot delete a committed destination`() = runTest {
        val context = mockk<Context>()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        val uri = Uri.parse("content://documents/document/committed")
        val output = ByteArrayOutputStream()
        every { resolver.openOutputStream(uri, "wt") } returns output
        val exports = MediaExportService(mockk(), backgroundScope)
        val request = exports.prepareModeInjectionExport(PromptInjection.ModeInjection(name = "Selected"))
        val target = CreatedExportDocument(uri)
        exports.savePreparedDocument(context, target, request)
        val committed = output.toByteArray()
        val failure = runCatching { exports.savePreparedDocument(context, target, request) }.exceptionOrNull()
        assertEquals("document_result_already_claimed", failure?.message)
        assertTrue(committed.isNotEmpty())
        org.junit.Assert.assertArrayEquals(committed, output.toByteArray())
    }

    @Test
    fun `null SAF stream reports failure and removes the unfilled target`() = runTest {
        val context = mockk<Context>()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        val uri = Uri.parse("content://documents/document/empty")
        every { resolver.openOutputStream(uri, "wt") } returns null
        val exports = MediaExportService(mockk(), backgroundScope)
        val request = exports.prepareModeInjectionExport(PromptInjection.ModeInjection(name = "Selected"))
        mockkStatic(DocumentsContract::class)
        var deleted = false
        every { DocumentsContract.deleteDocument(resolver, uri) } answers { deleted = true; true }
        try {
            val failure = runCatching { exports.savePreparedDocument(context, CreatedExportDocument(uri), request) }.exceptionOrNull()
            assertEquals("document_unavailable", failure?.message)
            assertTrue(deleted)
        } finally {
            unmockkStatic(DocumentsContract::class)
        }
    }

    @Test
    fun `malformed import envelopes and payloads preserve the serialization diagnostic`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File.createTempFile("malformed-prompt-", ".json", context.cacheDir)
        try {
            listOf("{", """{"type":"mode_injection","data":[]}""").forEach { content ->
                file.writeText(content)
                val error = requireNotNull(ModeInjectionSerializer.import(context, Uri.fromFile(file)).exceptionOrNull())
                assertTrue("Expected the original serialization failure: $error", error is SerializationException)
                assertFalse("The parser diagnostic must be retained", error.message.isNullOrBlank())
            }
        } finally {
            file.delete()
        }
    }

    private class SharingContext : ContextWrapper(ApplicationProvider.getApplicationContext<Application>()) {
        val shared = mutableListOf<Uri>()
        override fun startActivity(intent: Intent) {
            val send = requireNotNull(intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
            shared += requireNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        }

        fun readShared(uri: Uri): String = requireNotNull(contentResolver.openInputStream(uri))
            .bufferedReader().use { it.readText() }
    }
}
