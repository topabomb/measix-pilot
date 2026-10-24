package me.rerere.baselineprofile

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue

/** One real UI journey shared by profile collection and measured chat frames. */
internal class ProfileChatJourney(private val target: String, private val server: ProfileChatServer) {
    fun prepare(scope: MacrobenchmarkScope, marker: String) = with(scope) {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("cmd statusbar collapse")
        startActivityAndWait(fixture("prepare", marker))
        awaitFixture(this, "profile:prepared")
        openChat(this)
        requireNotNull(device.wait(Until.findObject(By.res("chat_input")), 60_000)).text = marker
        requireNotNull(device.wait(Until.findObject(By.res("chat_send_button").enabled(true)), 10_000))
        device.waitForIdle()
    }

    fun sendAndAwaitReply(scope: MacrobenchmarkScope, marker: String) = with(scope) {
        requireNotNull(device.wait(Until.findObject(By.res("chat_send_button").enabled(true)), 10_000)).click()
        assertTrue("Current reply did not render to its end",
            device.wait(Until.hasObject(By.textContains("PROFILE_REPLY_DONE_$marker")), 60_000))
    }

    fun verifyCommitted(scope: MacrobenchmarkScope, marker: String) = with(scope) {
        startActivityAndWait(fixture("verify", marker))
        awaitFixture(this, "profile:committed:$marker")
        server.checkHealthy()
    }

    fun openChat(scope: MacrobenchmarkScope) = with(scope) {
        // Starting the fixture replaces the task; Back would return to Home.
        startActivityAndWait(Intent().apply {
            component = ComponentName(target, "net.weero.measix.pilot.RouteActivity")
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        requireNotNull(device.wait(Until.findObject(By.res("chat_input")), 60_000))
        device.waitForIdle()
    }

    fun scroll(scope: MacrobenchmarkScope) = with(scope) {
        repeat(3) {
            device.swipe(device.displayWidth / 2, device.displayHeight / 3,
                device.displayWidth / 2, device.displayHeight * 3 / 4, 20)
            device.waitForIdle()
        }
    }

    private fun fixture(operation: String, marker: String) = Intent().apply {
        component = ComponentName(target, "net.weero.measix.pilot.benchmark.ProfileFixtureActivity")
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra("dedicatedProfileDevice", true)
        putExtra("operation", operation)
        putExtra("port", server.port)
        putExtra("marker", marker)
    }

    private fun awaitFixture(scope: MacrobenchmarkScope, expected: String) = with(scope) {
        val result = device.wait(Until.findObject(By.text(
            java.util.regex.Pattern.compile("^profile:.*", java.util.regex.Pattern.DOTALL)
        )), 65_000)
        if (result?.text != expected) {
            val hierarchy = java.io.ByteArrayOutputStream()
            device.dumpWindowHierarchy(hierarchy)
            android.util.Log.e("ProfileFixtureHierarchy", hierarchy.toString("UTF-8"))
        }
        assertTrue("Expected $expected; fixture reported: ${result?.text ?: "no result before timeout"}",
            result?.text == expected)
    }
}
