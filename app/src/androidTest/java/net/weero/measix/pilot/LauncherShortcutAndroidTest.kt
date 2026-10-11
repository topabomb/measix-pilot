package net.weero.measix.pilot

import android.content.Context
import android.content.pm.ShortcutManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherShortcutAndroidTest {
    @Test
    fun manifestShortcutsTargetTheirInstalledApplication() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shortcuts = context.getSystemService(ShortcutManager::class.java).manifestShortcuts
        assertEquals(setOf("camera", "image_generation"), shortcuts.map { it.id }.toSet())
        shortcuts.forEach { shortcut ->
            val component = shortcut.intent?.component
            assertNotNull("${shortcut.id} must not resolve to another installed variant", component)
            assertEquals(context.packageName, component?.packageName)
            val resolved = context.packageManager.resolveActivity(requireNotNull(shortcut.intent), 0)
            assertEquals(context.packageName, resolved?.activityInfo?.packageName)
        }
    }
}
