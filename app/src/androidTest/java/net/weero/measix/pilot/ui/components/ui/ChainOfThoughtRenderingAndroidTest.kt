package net.weero.measix.pilot.ui.components.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.weero.measix.pilot.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChainOfThoughtRenderingAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun controlledContentVisibilityDoesNotFollowExpandedAndPinnedInteractionSurvivesOuterCollapse() {
        val expanded = mutableStateOf(false)
        val visible = mutableStateOf(true)
        var approvals = 0
        compose.setContent {
            MaterialTheme {
                ChainOfThought(steps = listOf("approval", "hidden", "tail"), collapsedVisibleCount = 1,
                    keepVisibleWhenCollapsed = { it == "approval" }) { step ->
                    ControlledChainOfThoughtStep(
                        expanded = expanded.value, onExpandedChange = { expanded.value = it },
                        label = { Text(step) }, contentVisible = visible.value,
                        content = { if (step == "approval") androidx.compose.material3.TextButton(onClick = { approvals++ }) { Text("Approve pending") } else Text("content-$step") },
                    )
                }
            }
        }
        compose.onNodeWithText("hidden").assertDoesNotExist()
        compose.onNodeWithText("Approve pending").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, approvals); assertFalse(expanded.value) }
        compose.onNodeWithText("approval").performClick()
        compose.runOnIdle { assertTrue(expanded.value); visible.value = false }
        compose.onNodeWithText("Approve pending").assertDoesNotExist()
        compose.runOnIdle { visible.value = true; expanded.value = false }
        compose.onNodeWithText("Approve pending").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.chain_of_thought_show_more_steps, 1)).performClick()
        compose.onNodeWithText("hidden").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.chain_of_thought_collapse)).performClick()
        compose.onNodeWithText("hidden").assertDoesNotExist()
        compose.onNodeWithText("Approve pending").assertIsDisplayed()
    }
}
