package net.weero.measix.pilot.ui.pages.chat

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.R
import net.weero.measix.pilot.service.ConversationInputQueueUiModel
import net.weero.measix.pilot.service.QueuedInputUiModel
import net.weero.measix.pilot.service.QueuedInputUiPhase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class ChatInputQueueAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun clearingRequiresReviewAgainWhenInputArrivesDuringConfirmation() {
        val first = QueuedInputUiModel(Uuid.random(), 0, listOf(UIMessagePart.Text("first")),
            QueuedInputUiPhase.WAITING, canEdit = true, canRemove = true)
        val second = first.copy(id = Uuid.random(), parts = listOf(UIMessagePart.Text("second")))
        val model = mutableStateOf(ConversationInputQueueUiModel(listOf(first), null))
        var cleared: Map<Uuid, Long>? = null
        compose.setContent {
            MaterialTheme {
                ChatInputQueue(model.value, previews = { _, _ -> emptyMap() },
                    onRemove = { _, _ -> }, onResume = {}, onEdit = { _, _ -> }, onClear = { cleared = it })
            }
        }
        val clear = compose.activity.getString(R.string.chat_queue_clear)
        val confirm = hasText(clear) and hasClickAction() and hasAnyAncestor(isDialog())
        compose.onNodeWithText(clear).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.chat_queue_clear_confirm, 1)).assertIsDisplayed()
        compose.onNode(confirm).assertIsEnabled()
        compose.runOnIdle { model.value = model.value.copy(items = listOf(first, second)) }
        compose.onNode(confirm).assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.chat_queue_changed)).assertIsDisplayed()
        compose.runOnIdle { assertNull(cleared) }
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
        compose.onNodeWithText(clear).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.chat_queue_clear_confirm, 2)).assertIsDisplayed()
        compose.onNode(confirm).performClick()
        compose.runOnIdle { assertEquals(mapOf(first.id to 0L, second.id to 0L), cleared) }
    }
}
