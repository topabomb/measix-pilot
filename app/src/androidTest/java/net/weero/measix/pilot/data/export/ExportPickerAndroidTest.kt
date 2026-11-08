package net.weero.measix.pilot.data.export

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.mockk
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.weero.measix.pilot.data.model.PromptInjection
import net.weero.measix.pilot.service.MediaExportService
import net.weero.measix.pilot.ui.components.ui.DocumentExportActions
import net.weero.measix.pilot.ui.components.ui.ExportAction
import net.weero.measix.pilot.ui.components.ui.rememberExportDocumentLauncher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExportPickerAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pickerKeepsThePreparedPromptAcrossRestorationAndCanReuseTheUriOnTheNextLaunch() {
        val original = PromptInjection.ModeInjection(name = "original", content = "original payload")
        val updated = original.copy(content = "updated payload")
        val prompt = mutableStateOf(original)
        val scope = MainScope()
        val service = MediaExportService(mockk(), scope)
        val actions = DocumentExportActions(service, scope)
        var export: (() -> Unit)? = null
        var requestCode = 0
        var launches = 0
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                requestCode = code
                launches++
                assertEquals("original.json", input)
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val output = File.createTempFile("prompt-picker-", ".json", compose.activity.cacheDir)
        try {
            val restoration = StateRestorationTester(compose)
            restoration.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    val launchDocument = rememberExportDocumentLauncher(actions, "application/json")
                    val context = LocalContext.current
                    val preparation = rememberCoroutineScope()
                    export = {
                        val clicked = prompt.value
                        preparation.launch {
                            actions.execute(context, ExportAction.SAVE, { service.prepareModeInjectionExport(clicked) }, launchDocument)
                        }
                    }
                }
            }
            compose.runOnIdle { requireNotNull(export).invoke() }
            compose.waitUntil(5_000) { launches == 1 }
            compose.runOnIdle { prompt.value = updated }
            restoration.emulateSavedInstanceStateRestore()
            compose.runOnIdle {
                registry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(output)))
            }
            compose.waitUntil(5_000) { actions.completedName != null }
            assertEquals(ModeInjectionSerializer.exportToJson(original), output.readText())

            output.writeText("")
            compose.runOnIdle { requireNotNull(export).invoke() }
            compose.waitUntil(5_000) { launches == 2 }
            compose.runOnIdle {
                registry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(output)))
            }
            compose.waitUntil(5_000) { actions.completedName != null }
            assertEquals(ModeInjectionSerializer.exportToJson(updated), output.readText())
        } finally {
            compose.runOnIdle { actions.close() }
            scope.cancel()
            output.delete()
        }
    }
}
