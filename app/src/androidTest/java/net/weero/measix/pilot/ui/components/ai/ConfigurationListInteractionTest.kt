package net.weero.measix.pilot.ui.components.ai

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.model.QuickMessage
import net.weero.measix.pilot.ui.hooks.ChatInputState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigurationListInteractionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun quickMessageClickAppendsOnItsOwnLineAndKeepsAttachments() {
        val attachment = UIMessagePart.Document("content://pilot-fixture/report", "report.txt")
        val input = ChatInputState().apply { messageContent = listOf(attachment) }
        val preset = QuickMessage(title = "Insert inspection preset", content = "Review report")
        val presets = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.chat_input_presets)
        compose.setContent {
            MaterialTheme {
                PromptPresetButton(listOf(preset), emptyList(), input, requireInputOwner = {}, opening = null,
                    isDraft = true, onStarterClick = {}, onOpeningDetails = {})
            }
        }
        for ((draft, expected) in listOf(
            "  " to "Review report",
            "line one\nline two" to "line one\nline two\nReview report",
            "line one\n" to "line one\nReview report",
        )) {
            compose.runOnIdle { input.setMessageText(draft) }
            compose.onNodeWithContentDescription(presets).performClick()
            compose.onNodeWithText(preset.title).performClick()
            compose.runOnIdle {
                assertEquals(expected, input.textContent.text.toString())
                assertEquals(expected.length, input.textContent.selection.start)
                assertEquals(listOf(attachment), input.messageContent)
            }
        }
    }

    @Test
    fun quickMessageToggleReportsOriginalReferenceAndRenderedSelection() {
        val reference = ConfigurationReference.parse("managed~dep_example~quick_example")
        var selected by mutableStateOf(emptySet<ConfigurationReference>())
        compose.setContent {
            MaterialTheme {
                QuickMessagesContent(
                    quickMessages = listOf(QuickMessage(id = reference, title = "Example quick message")),
                    selectedIds = selected,
                    onToggle = { id, enabled -> selected = if (enabled) selected + id else selected - id },
                )
            }
        }
        compose.onNodeWithText("Example quick message").assertExists()
        compose.onNode(isToggleable()).assertIsOff().performClick()
        compose.runOnIdle { assertEquals(setOf(reference), selected) }
        compose.onNode(isToggleable()).assertIsOn().performClick()
        compose.runOnIdle { assertEquals(emptySet<ConfigurationReference>(), selected) }
        compose.onNode(isToggleable()).assertIsOff()
    }
}
