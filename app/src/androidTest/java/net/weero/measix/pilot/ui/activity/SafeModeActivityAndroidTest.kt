package net.weero.measix.pilot.ui.activity

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.weero.measix.pilot.R
import kotlinx.coroutines.runBlocking
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class SafeModeActivityAndroidTest {
    @get:Rule
    val compose = createAndroidComposeRule<SafeModeActivity>()

    @Test
    fun assistantPickerOpensInsideSafeModeComposition() {
        compose.onNodeWithText(compose.activity.getString(R.string.safe_mode_switch_assistant))
            .performClick()

        compose.onNodeWithText(compose.activity.getString(R.string.safe_mode_assistants))
            .assertIsDisplayed()
    }

    @Test
    fun selectingRecoveryAssistantClearsOnlyPersonalRecentConversation() {
        val settings = GlobalContext.get().get<SettingsStore>()
        compose.waitUntil(10_000) { !settings.userSettings.value.init }
        val original = settings.userSettings.value
        val personal = ConfigurationScope.Personal
        val enterprise = ConfigurationScope.Enterprise(EnterpriseAuthority("safe_mode_test"), Uuid.random().toString())
        val originalRecent = runBlocking { settings.lastConversation(personal) }
        val enterpriseRecent = Uuid.random()
        val replacement = Assistant(name = "Safe mode recovery test")
        try {
            runBlocking {
                settings.updateLocal { it.copy(assistants = it.assistants + replacement) }
                settings.rememberConversation(personal, Uuid.random())
                settings.rememberConversation(enterprise, enterpriseRecent)
            }
            compose.onNodeWithText(compose.activity.getString(R.string.safe_mode_switch_assistant)).performClick()
            compose.onNodeWithText(replacement.name).performScrollTo().performClick()
            compose.waitUntil(10_000) { settings.userSettings.value.assistantId == replacement.id }
            runBlocking {
                assertNull(settings.lastConversation(personal))
                assertEquals(enterpriseRecent, settings.lastConversation(enterprise))
            }
        } finally {
            runBlocking {
                settings.updateLocal { current -> current.copy(
                    assistants = current.assistants.filterNot { it.id == replacement.id },
                    assistantId = original.assistantId,
                ) }
                settings.rememberConversation(personal, originalRecent)
                settings.clearEnterprisePreferences(enterprise)
            }
        }
    }
}
