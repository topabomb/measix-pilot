package net.weero.measix.pilot.ui.pages.assistant.detail

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.weero.measix.pilot.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimeReminderIntervalAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun invalidNumbersDoNotSubmitAndPositiveIntervalCanBeConfirmed() {
        var saved: Int? = null
        compose.setContent { MaterialTheme { TimeReminderIntervalDialog(60, {}, { saved = it }) } }
        val input = compose.onNode(hasSetTextAction())
        val confirm = compose.onNodeWithText(compose.activity.getString(R.string.confirm))
        listOf("", "0", "-1", "2147483648").forEach {
            input.performTextReplacement(it)
            confirm.assertIsNotEnabled()
        }
        input.performTextReplacement("5")
        confirm.assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(5, saved) }
    }

    @Test fun cancellingDoesNotChangeTheStoredInterval() {
        var cancelled = false
        var saved: Int? = null
        compose.setContent { MaterialTheme { TimeReminderIntervalDialog(60, { cancelled = true }, { saved = it }) } }
        compose.onNode(hasSetTextAction()).performTextReplacement("10")
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertTrue(cancelled); assertNull(saved) }
    }
}
