package net.weero.measix.pilot.data.ai.tools.local

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.service.runtime.ToolLivePhase
import net.weero.measix.pilot.ui.components.charts.buildAxisScale
import net.weero.measix.pilot.ui.components.charts.formatChartNumber
import net.weero.measix.pilot.ui.components.message.tools.completedChart
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import kotlin.uuid.Uuid

class ChartDisplayToolTest {
    private val valid = """{"style":"bar","series":[{"values":[-3,0,8]}]}"""

    @Test fun `invalid historical chart is rejected before rendering`() {
        val invalid = listOf(
            """{"style":"line","series":[]}""",
            """{"style":"line","series":[{"values":[]}]}""",
            valid.replace("[-3,0,8]", "[1,\"2\",3]"),
            valid.replace("[-3,0,8]", "[1e309]"),
            valid.replace("[-3,0,8]", "[-1e308,1e308]"),
            valid.replace("[-3,0,8]", "[0,2.5e-323]"),
            valid.replace("[-3,0,8]", "[1e-400]"),
            valid.replace("[-3,0,8]", "[-1e-400]"),
            valid.replace("\"series\"", "\"y_axis\":{\"scale\":\"log\"},\"series\""),
            valid.replace("\"series\"", "\"x_axis\":{\"data\":[\"a\"]},\"series\""),
            valid.replace("[-3,0,8]", "[" + List(1001) { "1" }.joinToString(",") + "]"),
            valid.replace("\"series\"", "\"title\":\"${"a".repeat(161)}\",\"series\""),
            """{"style":"scatter","y_axis":{"scale":"log","max":0},"series":[{"points":[{"x":1,"y":2}]}]}""",
        )
        invalid.forEach { raw -> assertThrows(raw.take(150), IllegalArgumentException::class.java) { ChartSpec.parse(Json.parseToJsonElement(raw)) } }
    }

    @Test fun `bounded scales preserve zero baseline and represent constant and extreme accepted domains`() {
        for (values in listOf(listOf(-1e100, 1e100), listOf(1e-100, 2e-100), listOf(7.0), listOf(0.0))) {
            val scale = buildAxisScale(values, null, null, false, true)
            assertTrue(scale.min <= 0 && scale.max >= 0)
            assertTrue(scale.ticks.size <= 6)
            assertTrue(scale.ticks.all { it.isFinite() })
            assertTrue(values.all { scale.fraction(it).isFinite() })
        }
        val log = buildAxisScale(listOf(1e-100, 1e100), null, null, true, false)
        assertEquals(6, log.ticks.size)
        assertTrue(log.ticks.all { it.isFinite() && it > 0 })
        assertEquals(0f, log.fraction(1e-100))
        assertEquals(1f, log.fraction(1e100))
        val narrow = buildAxisScale(listOf(1000.001, 1000.003), null, null, false, false)
        assertEquals(6, narrow.ticks.map {
            net.weero.measix.pilot.ui.components.charts.formatChartNumber(it, decimals = narrow.tickDecimals)
        }.distinct().size)
    }

    @Test fun `linear tick labels preserve decimal coordinates and endpoints`() {
        val previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            fun labels(low: Double, high: Double): List<String> {
                val scale = buildAxisScale(listOf(low, high), null, null, false, false)
                return scale.ticks.map { formatChartNumber(it, decimals = scale.tickDecimals) }
            }
            assertEquals(listOf("1.5", "8.7", "15.9", "23.1", "30.3", "37.5"), labels(1.5, 37.5))
            assertEquals(listOf("1", "5.8", "10.6", "15.4", "20.2", "25"), labels(1.0, 25.0))
            assertEquals(listOf("1000.001", "1000.0014", "1000.0018", "1000.0022", "1000.0026", "1000.003"),
                labels(1000.001, 1000.003))
            assertEquals(listOf("-2.5", "-1.5", "-0.5", "0.5", "1.5", "2.5"), labels(-2.5, 2.5))
            assertEquals(listOf("1.0e-100", "1.2e-100", "1.4e-100", "1.6e-100", "1.8e-100", "2.0e-100"),
                labels(1e-100, 2e-100))
            assertEquals("1.50", formatChartNumber(1.5, ".2f", 0))
            val narrow = labels(0.0001, 0.0001000000000000002)
            assertEquals(6, narrow.distinct().size)
            assertTrue(narrow.all { "e" in it && it.length < 30 })
            assertEquals("2", formatChartNumber(1.5, ".0f", 20))
            val log = buildAxisScale(listOf(1.0001, 1.0002), null, null, true, false)
            val logLabels = log.ticks.map { formatChartNumber(it, decimals = log.tickDecimals) }
            assertEquals(6, logLabels.distinct().size)
            assertEquals("1.0001", logLabels.first())
            assertEquals("1.0002", logLabels.last())
            val adjacent = buildAxisScale(listOf(1.0, Math.nextUp(1.0)), null, null, false, false)
            assertEquals(2, adjacent.ticks.size)
            val narrowLog = buildAxisScale(listOf(10.000000000000002, 10.00000000000001), null, null, true, false)
            assertTrue(narrowLog.ticks.all { it in narrowLog.min..narrowLog.max })
            assertTrue(narrowLog.ticks.zipWithNext().all { (a, b) -> b > a })
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test fun `only committed completed result renders the validated chart`() = runTest {
        val result = UIMessagePart.Tool(Uuid.random(), Uuid.random(), "call", "chart_display", valid,
            output = listOf(UIMessagePart.Text("""{"status":"completed","message":"Chart displayed to the user."}""")), resultStatus = ToolResultStatus.COMPLETED)
        for (phase in ToolLivePhase.entries) {
            if (phase == ToolLivePhase.COMPLETED) assertNotNull(completedChart(result, phase))
            else assertNull(completedChart(result, phase))
        }
        assertNull(completedChart(result.copy(resultStatus = null), ToolLivePhase.COMPLETED))
        assertNull(completedChart(result.copy(resultStatus = ToolResultStatus.FAILED), ToolLivePhase.COMPLETED))
        assertNull(completedChart(result.copy(input = "broken"), ToolLivePhase.COMPLETED))
        assertNull(completedChart(result.copy(output = listOf(UIMessagePart.Text("{}"))), ToolLivePhase.COMPLETED))
        val spaced = " ".repeat(70_000) + valid
        ChartSpec.parse(Json.parseToJsonElement(spaced))
        assertNotNull(completedChart(result.copy(input = spaced), ToolLivePhase.COMPLETED))
        ChartSpec.parse(Json.parseToJsonElement(valid.replace("[-3,0,8]", "[0,0.0,-0,0e-999]")))
    }
}
