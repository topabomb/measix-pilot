package me.rerere.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Record startup separately from a real, fully committed loopback-model chat. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        val target = requireNotNull(InstrumentationRegistry.getArguments().getString("targetAppId"))
        rule.collect(packageName = target, includeInStartupProfile = true) {
            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            device.executeShellCommand("cmd statusbar collapse")
            pressHome()
            startActivityAndWait()
            device.waitForIdle()
        }
    }

    @Test
    fun chat() {
        val target = requireNotNull(InstrumentationRegistry.getArguments().getString("targetAppId"))
        ProfileChatServer().use { server ->
            val journey = ProfileChatJourney(target, server)
            rule.collect(packageName = target, includeInStartupProfile = false) {
                val marker = "PROFILE_" + java.util.UUID.randomUUID().toString().replace("-", "")
                journey.prepare(this, marker)
                journey.sendAndAwaitReply(this, marker)
                journey.verifyCommitted(this, marker)
                journey.openChat(this)
                journey.scroll(this)
            }
        }
    }
}
