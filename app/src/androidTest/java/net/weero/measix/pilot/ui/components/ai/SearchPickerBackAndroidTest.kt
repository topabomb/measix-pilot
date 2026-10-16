package net.weero.measix.pilot.ui.components.ai

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo
import net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchPickerBackAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun backReturnsToSettingsThenRemovesModalInCurrentWindow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val title = context.getString(R.string.search_picker_title)
        val select = context.getString(R.string.search_picker_select_provider)
        val settings = Settings()
        var expandedWindow = false
        compose.setContent {
            MaterialTheme {
                val layout = rememberAdaptiveLayoutInfo()
                SideEffect { expandedWindow = layout.useExpandedModal }
                CompositionLocalProvider(LocalAdaptiveLayoutInfo provides layout) {
                    SearchPickerButton(
                        enableSearch = true, settings = settings,
                        selectedSearchServiceId = settings.searchServices.first().id,
                        modifier = Modifier.testTag("search-button"),
                        onUpdateSearchMode = {}, onUpdateSearchService = {},
                        builtInSearchEnabled = false, supportsBuiltInSearch = false,
                        onManageSearchServices = {},
                    )
                }
            }
        }
        compose.runOnIdle {
            InstrumentationRegistry.getArguments().getString("searchPickerWindow")?.let { expected ->
                require(expected == "compact" || expected == "expanded")
                assertEquals("Device window must match the requested native modal branch", expected == "expanded", expandedWindow)
            }
        }
        compose.onNodeWithTag("search-button").performClick()
        compose.onNodeWithText(select, substring = true).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty() }
        // A native dialog window must finish its accessibility/focus events before system Back.
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(100, 5_000)
        pressBack()
        compose.waitUntil(5_000) { compose.onNodeWithText(title).isDisplayed() }
        compose.onNodeWithText(title).assertIsDisplayed()
        pressBack()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty() }
        // An invisible sheet window must not keep intercepting the next opening gesture.
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithTag("search-button").performClick()
        compose.onNodeWithText(title).assertIsDisplayed()
    }
}
