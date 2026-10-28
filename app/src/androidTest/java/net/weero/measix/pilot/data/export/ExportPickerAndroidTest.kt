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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.weero.measix.pilot.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExportPickerAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun exportResultKeepsThePromptSelectedBeforeThePickerOpened() {
        val original = PromptInjection.ModeInjection(name = "original", content = "original payload")
        val prompt = mutableStateOf(original)
        var exporter: ExporterState<PromptInjection.ModeInjection>? = null
        var requestCode = 0
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                requestCode = code
                assertEquals("original.json", input)
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val output = File.createTempFile("prompt-picker-", ".json", compose.activity.cacheDir)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    exporter = rememberExporter(prompt.value, ModeInjectionSerializer)
                }
            }
            compose.runOnIdle { requireNotNull(exporter).exportToFile() }
            compose.runOnIdle { prompt.value = original.copy(name = "updated", content = "updated payload") }
            compose.runOnIdle {
                registry.dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(output)))
            }
            compose.waitUntil(5_000) { output.length() > 0 }
            assertEquals(ModeInjectionSerializer.exportToJson(original), output.readText())
        } finally { output.delete() }
    }
}
