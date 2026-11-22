package net.weero.measix.pilot.ui.components.ui.permission

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionStateAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun requestWithoutAPermissionResultCanBeRetried() {
        val permission = "net.weero.measix.pilot.test.permission.UNGRANTED"
        val info = PermissionInfo(permission = permission, displayName = {}, usage = {})
        val registry = RecordingRegistry()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        lateinit var state: PermissionState
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                state = rememberPermissionState(info)
            }
        }

        compose.runOnIdle {
            state.requestPermissions()
            registry.dispatchResult(registry.requests.single(), Activity.RESULT_CANCELED, null)
            assertEquals(PermissionStatus.NotRequested, state.permissionStates[permission])

            state.requestPermissions()
            assertEquals(2, registry.requests.size)
            registry.dispatchResult(registry.requests.last(), mapOf(permission to false))
            assertEquals(PermissionStatus.DeniedPermanently, state.permissionStates[permission])
        }
    }

    private class RecordingRegistry : ActivityResultRegistry() {
        val requests = mutableListOf<Int>()

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            requests += requestCode
        }
    }
}
