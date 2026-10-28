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

    @Test fun providerShareRemainsOpenWhenItsHostRecomposes() {
        val revision = mutableStateOf(0)
        val provider = ProviderSetting.Google(name = "Shared provider")
        compose.setContent {
            CompositionLocalProvider(
                LocalSettings provides Settings(),
                net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo provides net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo(),
            ) {
                MaterialTheme {
                    val share = net.weero.measix.pilot.ui.components.ui.rememberShareSheetState()
                    androidx.compose.material3.Text("Revision ${revision.value}")
                    androidx.compose.material3.TextButton(onClick = { share.show(provider) }) {
                        androidx.compose.material3.Text("Open share")
                    }
                    net.weero.measix.pilot.ui.components.ui.ShareSheet(share)
                }
            }
        }
        compose.onNodeWithText("Open share").performClick()
        compose.onNodeWithText("共享你的LLM模型").assertIsDisplayed()
        compose.runOnIdle { revision.value++ }
        compose.onNodeWithText("共享你的LLM模型").assertIsDisplayed()
    }

    @Test fun malformedServiceAccountImportRetainsSafeDiagnosticAndOriginalDraft() {
        val original = ProviderSetting.Google(name = "Original account", vertexAI = true,
            useServiceAccount = true, projectId = "original-project")
        val draft = mutableStateOf<ProviderSetting>(original)
        var requestCode = 0
        val registry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int,
                contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
                input: I, options: androidx.core.app.ActivityOptionsCompat?) {
                requestCode = code
            }
        }
        val owner = object : androidx.activity.result.ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        val secret = "PRIVATE_KEY_SENTINEL_DO_NOT_DISPLAY"
        val input = java.io.File.createTempFile("service-account-", ".json", compose.activity.cacheDir)
        input.writeText("""{"private_key":"$secret","project_id":}""")
        lateinit var toaster: net.weero.measix.pilot.ui.components.toast.ToasterState
        try {
            compose.setContent {
                toaster = rememberToasterState()
                CompositionLocalProvider(LocalSettings provides Settings(), LocalToaster provides toaster,
                    net.weero.measix.pilot.ui.adaptive.LocalAdaptiveLayoutInfo provides net.weero.measix.pilot.ui.adaptive.rememberAdaptiveLayoutInfo(),
                    androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) {
                    MaterialTheme {
                        ProviderCreationPage(draft.value, false, null, onCancel = {}, onSave = { draft.value = it })
                    }
                }
            }
            compose.onNodeWithText(compose.activity.getString(R.string.setting_provider_page_import_service_account_json))
                .performScrollTo().performClick()
            compose.runOnIdle {
                registry.dispatchResult(requestCode, android.app.Activity.RESULT_OK,
                    android.content.Intent().setData(android.net.Uri.fromFile(input)))
            }
            compose.runOnIdle {
                assertFalse("Import error disclosed private JSON payload", toaster.items.any { secret in it.message })
                assertEquals(original, draft.value)
            }
            compose.onNodeWithText(compose.activity.getString(R.string.chat_conversation_diagnostics))
                .performScrollTo().performClick()
            compose.onNode(hasText("JsonDecodingException", substring = true) and
                hasAnyAncestor(hasAnyDescendant(hasContentDescription(compose.activity.getString(R.string.update_card_close)))))
                .assertIsDisplayed()
            compose.onNodeWithText(secret, substring = true).assertDoesNotExist()
        } finally { input.delete() }
    }

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
