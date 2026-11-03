package net.weero.measix.pilot.ui.components.ui.permission

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import net.weero.measix.pilot.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupLocalNetworkPermissionAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    @SdkSuppress(minSdkVersion = 37)
    fun deniedPermissionKeepsContentAvailableAndDoesNotRequestAgainAfterRestoration() {
        val registry = RecordingRegistry()
        val restoration = showStartup(granted = false, registry)
        compose.runOnIdle {
            assertEquals(listOf(Manifest.permission.ACCESS_LOCAL_NETWORK), registry.permissions)
        }
        denyAndExpectWarning(registry)
        compose.onNodeWithText("Application content").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Application content").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, registry.permissions.size) }
    }

    @Test
    @SdkSuppress(minSdkVersion = 37)
    fun pendingRequestRetainsItsResultCallbackAfterRestoration() {
        val registry = RecordingRegistry()
        val restoration = showStartup(granted = false, registry)
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(1, registry.permissions.size) }
        denyAndExpectWarning(registry)
        compose.onNodeWithText("Application content").assertIsDisplayed()
    }

    @Test
    @SdkSuppress(minSdkVersion = 37)
    fun alreadyGrantedPermissionDoesNotRequestAgain() {
        val registry = RecordingRegistry()
        showStartup(granted = true, registry)
        compose.onNodeWithText("Application content").assertIsDisplayed()
        compose.runOnIdle { assertTrue(registry.permissions.isEmpty()) }
    }

    @Test
    @SdkSuppress(maxSdkVersion = 36)
    fun olderAndroidDoesNotRequestLocalNetworkPermission() {
        val registry = RecordingRegistry()
        showStartup(granted = false, registry)
        compose.onNodeWithText("Application content").assertIsDisplayed()
        compose.runOnIdle { assertTrue(registry.permissions.isEmpty()) }
    }

    private fun denyAndExpectWarning(registry: RecordingRegistry) {
        val message = compose.activity.getString(R.string.permission_local_network_denied)
        val event = InstrumentationRegistry.getInstrumentation().uiAutomation.executeAndWaitForEvent(
            { compose.runOnIdle { registry.dispatchResult(requireNotNull(registry.requestCode), false) } },
            { it.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && message in it.text },
            5_000,
        )
        event.recycle()
    }

    private fun showStartup(granted: Boolean, registry: RecordingRegistry): StateRestorationTester {
        val context = object : ContextWrapper(compose.activity) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission == Manifest.permission.ACCESS_LOCAL_NETWORK) {
                    if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                } else super.checkPermission(permission, pid, uid)
        }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        return StateRestorationTester(compose).also { restoration ->
            restoration.setContent {
                CompositionLocalProvider(LocalContext provides context, LocalActivityResultRegistryOwner provides owner) {
                    StartupLocalNetworkPermission()
                    Text("Application content")
                }
            }
        }
    }

    private class RecordingRegistry : ActivityResultRegistry() {
        val permissions = mutableListOf<String>()
        var requestCode: Int? = null

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            permissions += input as String
            this.requestCode = requestCode
        }
    }
}
