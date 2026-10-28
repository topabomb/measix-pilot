package net.weero.measix.pilot.data.export

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.core.content.FileProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ExportHooksTest {
    @Before fun prepareFileProvider() {
        // JVM tests have no merged manifest; preserve the exact exported file identity at this Android boundary.
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } answers { Uri.fromFile(thirdArg<File>()) }
    }

    @After fun releaseFileProvider() { unmockkStatic(FileProvider::class) }

    @Test
    fun `sharing a prompt name cannot overwrite a file outside its export directory`() = runTest {
        val context = SharingContext()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
        val victim = File(context.filesDir, "export-victim.json").apply { writeText("existing private data") }
        try {
            val prompt = PromptInjection.ModeInjection(name = "../../files/export-victim")
            ExporterState(prompt, ModeInjectionSerializer, context, scope, mockk()).exportAndShare()
            runCurrent()
            job.children.toList().joinAll()
            assertEquals("existing private data", victim.readText())
            assertEquals(ModeInjectionSerializer.exportToJson(prompt), context.readShared(context.shared.single()))
        } finally { scope.cancel(); victim.delete() }
    }

    @Test
    fun `sharing the same name again preserves the payload of the previous shared uri`() = runTest {
        val context = SharingContext()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
        val first = PromptInjection.ModeInjection(name = "shared prompt", content = "first payload")
        val second = first.copy(content = "second payload")
        try {
            ExporterState(first, ModeInjectionSerializer, context, scope, mockk()).exportAndShare()
            runCurrent()
            job.children.toList().joinAll()
            val firstUri = context.shared.single()
            ExporterState(second, ModeInjectionSerializer, context, scope, mockk()).exportAndShare()
            runCurrent()
            job.children.toList().joinAll()
            val secondUri = context.shared.last()
            assertNotEquals(firstUri, secondUri)
            assertEquals(ModeInjectionSerializer.exportToJson(first), context.readShared(firstUri))
            assertEquals(ModeInjectionSerializer.exportToJson(second), context.readShared(secondUri))
        } finally { scope.cancel() }
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
