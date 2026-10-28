package net.weero.measix.pilot.ui.components.message

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.Json
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.ai.tools.failToolResult
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.service.ConversationViewLease
import net.weero.measix.pilot.service.MemoryService
import net.weero.measix.pilot.service.MemoryToolRecord
import net.weero.measix.pilot.service.RenderedContentSource
import net.weero.measix.pilot.service.runtime.ToolLivePhase
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.components.message.tools.*
import net.weero.measix.pilot.ui.components.richtext.LocalRenderedContentSource
import net.weero.measix.pilot.ui.components.toast.rememberToasterState
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.ui.context.LocalToaster
import net.weero.measix.pilot.ui.theme.LocalDarkMode
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.IOException
import kotlin.uuid.Uuid

/** Production preview components; owner permission and persistence behavior have separate service tests. */
@RunWith(AndroidJUnit4::class)
class ToolPreviewFailureAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val memory = mockk<MemoryService>()
    private val view = ConversationViewLease(Uuid.random(), RealmAccess.Personal, 0L) {}
    private val isolated = koinApplication { modules(module { single { memory } }) }

    @After fun close() {
        view.close()
        isolated.close()
        compose.activity.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
    }

    @Test fun memoryRecordReadFailureKeepsCopyableCauseWithoutDeletingOrClosingBorrowedView() {
        val context = memoryContext()
        val failure = failure("memory result read failed")
        coEvery { memory.captureToolRecord(view, view.conversationId, context.locator!!) } throws failure
        var dismissed = false
        show { MemoryToolUI.Preview(context) { dismissed = true } }
        assertCopyableFailure(failure)
        compose.runOnIdle { assertFalse(dismissed); assertFalse(view.closed.value) }
        coVerify(exactly = 1) { memory.captureToolRecord(view, view.conversationId, context.locator!!) }
        coVerify(exactly = 0) { memory.deleteToolRecord(any()) }
        compose.onNodeWithContentDescription(label(R.string.tool_ui_delete_memory)).assertDoesNotExist()
    }

    @Test fun failedMemoryDeletionKeepsOriginalRecordForExplicitRetryAndPreservesDiagnostic() {
        val context = memoryContext()
        val record = mockk<MemoryToolRecord>()
        every { record.matches(context.conversationId, context.locator) } returns true
        coEvery { memory.captureToolRecord(view, view.conversationId, context.locator!!) } returns record
        val failure = failure("memory delete failed")
        var attempts = 0
        coEvery { memory.deleteToolRecord(record) } coAnswers { if (++attempts == 1) throw failure }
        var dismissed = false
        show { MemoryToolUI.Preview(context) { dismissed = true } }
        compose.onNodeWithContentDescription(label(R.string.tool_ui_delete_memory)).performClick()
        assertCopyableFailure(failure)
        compose.runOnIdle { assertEquals(1, attempts); assertFalse(dismissed); assertFalse(view.closed.value) }
        compose.onNodeWithContentDescription(label(R.string.update_card_close)).performClick()
        compose.onNodeWithContentDescription(label(R.string.tool_ui_delete_memory)).performClick()
        compose.waitUntil(5_000) { dismissed }
        coVerify(exactly = 2) { memory.deleteToolRecord(record) }
        coVerify(exactly = 1) { memory.captureToolRecord(view, view.conversationId, context.locator!!) }
        assertFalse(view.closed.value)
    }

    @Test fun leavingMemoryPreviewCancelsPendingRecordReadWithoutShowingFailure() {
        val context = memoryContext()
        val entered = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        coEvery { memory.captureToolRecord(view, view.conversationId, context.locator!!) } coAnswers {
            entered.complete(Unit)
            try { awaitCancellation() } finally { exited.complete(Unit) }
        }
        var visible by mutableStateOf(true)
        show { if (visible) MemoryToolUI.Preview(context) { error("Read cancellation must not dismiss its parent") } }
        compose.waitUntil(5_000) { entered.isCompleted }
        compose.runOnIdle { visible = false }
        compose.waitUntil(5_000) { exited.isCompleted }
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        assertFalse(view.closed.value)
        coVerify(exactly = 0) { memory.deleteToolRecord(any()) }
    }

    @Test fun shellOwnerRejectionWithoutProcessOutputKeepsItsReasonAndDetail() {
        val reason = "workspace_unavailable"
        val detail = "Original workspace is unavailable; reopen its file owner."
        val failure = try { failToolResult(reason, detail) } catch (error: ToolExecutionFailure) { error }
        assertNull(failure.cause)
        val context = shellContext(failure.output)
        assertNull(context.tool.clientDiagnostic)
        show { ShellToolUI.Preview(context) {} }
        compose.onNodeWithText(reason, substring = true).assertExists()
        compose.onNodeWithText(detail, substring = true).assertExists()
        compose.onNodeWithText(label(R.string.tool_ui_shell_exit, "?")).assertDoesNotExist()
    }

    @Test fun nonzeroShellExitRetainsItsCommandAndDedicatedStdoutStderrPreview() {
        val output = listOf(UIMessagePart.Text("""{"exitCode":7,"stdout":"partial process output","stderr":"actual process failure","timedOut":false}"""))
        val failure = try { failToolResult(output, "shell_exit_nonzero", "Inspect stderr.") }
            catch (error: ToolExecutionFailure) { error }
        show { ShellToolUI.Preview(shellContext(failure.output)) {} }
        compose.onNodeWithText(label(R.string.tool_ui_shell_exit, "7")).assertIsDisplayed()
        compose.onNodeWithText("stdout").assertExists()
        compose.onNodeWithText("stderr").assertExists()
        compose.onNodeWithText("partial process output", substring = true).assertExists()
        compose.onNodeWithText("actual process failure", substring = true).assertExists()
    }

    private fun failure(message: String) = IOException("$message token=private-token", IllegalStateException("original memory store cause")).apply {
        addSuppressed(IOException("cleanup diagnostic"))
    }

    private fun assertCopyableFailure(failure: Throwable) {
        val diagnostic = failure.userVisibleDiagnostic()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(diagnostic).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(diagnostic).assertIsDisplayed()
        compose.onNodeWithText("private-token", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(label(R.string.chat_page_copy_error)).performClick()
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == diagnostic }
    }

    private fun memoryContext(): ToolUIContext {
        val arguments = Json.parseToJsonElement("""{"action":"create","content":"original memory"}""")
        val tool = UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "memory",
            toolName = "memory_tool", input = arguments.toString(), output = listOf(UIMessagePart.Text("{\"id\":7}")),
            resultStatus = ToolResultStatus.COMPLETED)
        return ToolUIContext(tool, arguments, true, Json.parseToJsonElement("{\"id\":7}"), ToolLivePhase.COMPLETED,
            conversationId = view.conversationId, locator = ToolCallLocator(Uuid.random(), tool.stepId, tool.localCallId))
    }

    private fun shellContext(output: List<UIMessagePart>): ToolUIContext {
        val arguments = Json.parseToJsonElement("""{"command":"echo original command"}""")
        val tool = UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "shell",
            toolName = "workspace_shell", input = arguments.toString(), output = output, resultStatus = ToolResultStatus.FAILED)
        return ToolUIContext(tool, arguments, true, Json.parseToJsonElement((output.first() as UIMessagePart.Text).text), ToolLivePhase.FAILED)
    }

    private fun label(id: Int, vararg values: Any) = compose.activity.getString(id, *values)

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { KoinIsolatedContext(isolated) { MaterialTheme {
            CompositionLocalProvider(LocalSettings provides Settings.dummy(), LocalDarkMode provides false,
                LocalToaster provides rememberToasterState(), LocalAdaptiveLayoutInfo provides rememberAdaptiveLayoutInfo(),
                LocalRenderedContentSource provides RenderedContentSource.Conversation(view), content = content)
        } } }
    }
}
