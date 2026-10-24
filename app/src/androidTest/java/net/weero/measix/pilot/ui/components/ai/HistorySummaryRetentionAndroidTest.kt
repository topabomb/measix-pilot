package net.weero.measix.pilot.ui.components.ai

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.data.datastore.Settings
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Job
import net.weero.measix.pilot.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistorySummaryRetentionAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun boundsDoNotUnderflowOrOverflowAndControlsRemainVisible() {
        val value = mutableStateOf(0)
        compose.setContent { RetentionTestTheme { Box(Modifier.width(280.dp)) {
            HistorySummaryRetentionStepper(value.value) { value.value = it }
        } } }
        val decrease = compose.activity.getString(R.string.history_summary_retention_decrease)
        val increase = compose.activity.getString(R.string.history_summary_retention_increase)
        compose.onNodeWithContentDescription(decrease).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithContentDescription(increase).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, value.value); value.value = Int.MAX_VALUE }
        compose.onNodeWithContentDescription(increase).assertIsNotEnabled()
        compose.onNodeWithContentDescription(decrease).performClick()
        compose.runOnIdle { assertEquals(Int.MAX_VALUE - 1, value.value) }
        compose.onNodeWithContentDescription(increase).performClick().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(Int.MAX_VALUE, value.value) }
    }

    @Test fun confirmingKeepsTheLocalDefaultOfThirtyTwo() {
        var retained: Int? = null
        compose.setContent { RetentionTestTheme {
            CompressContextDialog(onDismiss = {}, onConfirm = { _, _, keep ->
                retained = keep
                Job().apply { complete() }
            })
        } }
        compose.onNodeWithText("32").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.confirm)).performClick()
        compose.runOnIdle { assertEquals(32, retained) }
    }

    @Test fun cancellingDoesNotSubmitASummary() {
        var dismissed = false
        var submissions = 0
        compose.setContent { RetentionTestTheme {
            CompressContextDialog(onDismiss = { dismissed = true }, onConfirm = { _, _, _ ->
                submissions++
                Job().apply { complete() }
            })
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
        compose.runOnIdle { assertTrue(dismissed); assertEquals(0, submissions) }
    }
}

@Composable
private fun RetentionTestTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSettings provides Settings()) { MaterialTheme(content = content) }
}
