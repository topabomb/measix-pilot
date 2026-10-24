package net.weero.measix.pilot.ui.pages.setting

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.weero.measix.pilot.ui.components.toast.rememberToasterState
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.ui.context.LocalSettings
import net.weero.measix.pilot.ui.context.LocalToaster
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderCreationAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun cancelledCreationDoesNotSubmitTheDraft() {
        var cancelled = false
        var saves = 0
        compose.setContent {
            CompositionLocalProvider(LocalSettings provides Settings(), LocalToaster provides rememberToasterState()) {
                MaterialTheme {
                    ProviderCreationPage(ProviderSetting.Google(name = "Unsaved provider"), false, null,
                        onCancel = { cancelled = true }, onSave = { saves++ })
                }
            }
        }
        compose.onNodeWithText("Unsaved provider").assertExists()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.back)).performClick()
        compose.runOnIdle { assertTrue(cancelled); assertEquals(0, saves) }
    }

    @Test fun failedSaveRetainsEditedFieldsAndAllowsRetry() {
        val draft = mutableStateOf<ProviderSetting>(ProviderSetting.Google(name = "Original"))
        val saving = mutableStateOf(false)
        val error = mutableStateOf<String?>(null)
        var saves = 0
        compose.setContent {
            CompositionLocalProvider(LocalSettings provides Settings(), LocalToaster provides rememberToasterState()) {
                MaterialTheme {
                    ProviderCreationPage(draft.value, saving.value, error.value, onCancel = {}, onSave = {
                        draft.value = it
                        saving.value = true
                        saves++
                    })
                }
            }
        }
        compose.onNodeWithText("Original").performTextReplacement("Edited before failure")
        val save = compose.activity.getString(R.string.setting_provider_page_save)
        compose.onNodeWithText(save).performScrollTo().performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.back)).assertIsNotEnabled()
        compose.runOnIdle { saving.value = false; error.value = "IOException: storage unavailable" }
        compose.onNodeWithText("Edited before failure").assertExists()
        compose.onNodeWithText("IOException: storage unavailable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(save).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, saves); assertEquals("Edited before failure", draft.value.name) }
    }
}
