package net.weero.measix.pilot.ui.components.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import net.weero.measix.pilot.R
import net.weero.measix.pilot.ui.components.toast.Toaster
import net.weero.measix.pilot.ui.components.toast.rememberToasterState
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration

class ToastHostAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun visibleToastDoesNotBlockPageInputOrActions() {
        compose.setContent {
            MaterialTheme {
                val state = rememberToasterState()
                var input by remember { mutableStateOf("") }
                var clicked by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { state.show("progress", duration = Duration.INFINITE) }
                Box(Modifier.fillMaxSize()) {
                    Column {
                        Spacer(Modifier.height(180.dp))
                        TextField(input, onValueChange = { input = it }, modifier = Modifier.testTag("input"))
                        Button(onClick = { clicked = true }) { Text(if (clicked) "clicked" else "action") }
                    }
                    Toaster(state)
                }
            }
        }
        compose.onNodeWithText("progress").assertIsDisplayed()
        compose.onNodeWithTag("input").performTextInput("typing while visible")
        compose.onNodeWithTag("input").assertTextEquals("typing while visible")
        compose.onNodeWithText("action").performClick()
        compose.onNodeWithText("clicked").assertIsDisplayed()
        compose.onNodeWithText("progress").assertIsDisplayed()
    }

    @Test
    fun clippedMessageOpensItsCompleteCopyableText() {
        val message = (1..40).joinToString("\n") { "diagnostic line $it" }
        compose.setContent {
            MaterialTheme {
                val state = rememberToasterState()
                LaunchedEffect(Unit) { state.show(message, duration = Duration.INFINITE) }
                androidx.compose.runtime.CompositionLocalProvider(
                    net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo provides net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo()
                ) { Box(Modifier.fillMaxSize()) { Toaster(state) } }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.toast_details)).performClick()
        compose.onAllNodesWithText(message, useUnmergedTree = true).assertCountEquals(2)
    }
}
