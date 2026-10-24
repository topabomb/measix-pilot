package me.rerere.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Frame costs of actual send/stream/render/scroll, not a model-latency measurement. */
@RunWith(AndroidJUnit4::class)
@LargeTest
@SdkSuppress(minSdkVersion = 29)
class ChatBenchmarks {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun chatCompilationNone() = benchmark(CompilationMode.None())

    @Test fun chatCompilationBaselineProfiles() =
        benchmark(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun benchmark(compilationMode: CompilationMode) {
        val target = requireNotNull(InstrumentationRegistry.getArguments().getString("targetAppId"))
        ProfileChatServer().use { server ->
            val journey = ProfileChatJourney(target, server)
            var pending: Pair<MacrobenchmarkScope, String>? = null
            var marker = ""
            rule.measureRepeated(
                packageName = target,
                metrics = listOf(FrameTimingMetric()),
                compilationMode = compilationMode,
                // Startup is measured separately. Prepare and focus a new chat before tracing frames.
                startupMode = null,
                iterations = 10,
                setupBlock = {
                    // Macrobenchmark has no teardown callback. Validate the previous durable result
                    // outside the next trace; the final iteration is validated after measureRepeated.
                    pending?.let { (_, previous) -> journey.verifyCommitted(this, previous) }
                    pending = null
                    marker = "PROFILE_" + java.util.UUID.randomUUID().toString().replace("-", "")
                    journey.prepare(this, marker)
                },
            ) {
                journey.sendAndAwaitReply(this, marker)
                journey.scroll(this)
                pending = this to marker
            }
            val (scope, lastMarker) = requireNotNull(pending) { "Chat benchmark did not measure a reply" }
            journey.verifyCommitted(scope, lastMarker)
        }
    }
}
