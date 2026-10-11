package net.weero.measix.pilot.ui.components.charts

import androidx.compose.ui.graphics.Color
import net.weero.measix.pilot.data.ai.tools.local.ChartSpec
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

private val chartPalette = listOf(Color(0xFF4A7FD6), Color(0xFFE2774A), Color(0xFF4FAE7B),
    Color(0xFF9B6FD1), Color(0xFFD9A93A), Color(0xFFD65C8A), Color(0xFF3FA7B5), Color(0xFFB0694E))

internal fun ChartSpec.seriesColors(): List<Color> = series.mapIndexed { index, series ->
    series.color?.drop(1)?.let { hex ->
        val expanded = if (hex.length == 3) hex.map { "$it$it" }.joinToString("") else hex
        Color(0xFF000000 or expanded.toLong(16))
    } ?: chartPalette[index % chartPalette.size]
}

/** Six ticks at most; all arithmetic stays in Double until the bounded screen fraction. */
internal class ChartAxisScale(val min: Double, val max: Double, val log: Boolean) {
    private val low = if (log) log10(min) else min
    private val high = if (log) log10(max) else max
    init { require(low.isFinite() && high.isFinite() && low < high) }
    val ticks: List<Double> = (0..5).map { index ->
        val value = low + (high - low) * index / 5
        when (index) {
            0 -> min
            5 -> max
            else -> (if (log) 10.0.pow(value) else value).coerceIn(min, max)
        }
    }.distinct()
    val tickDecimals: Int = if (log) {
        val spacing = ticks.zipWithNext { a, b -> b - a }.min()
        maxOf(0, kotlin.math.ceil(-log10(spacing)).toInt() + 1,
            BigDecimal.valueOf(min).stripTrailingZeros().scale(),
            BigDecimal.valueOf(max).stripTrailingZeros().scale())
    } else run {
        // Preserve the decimal endpoints and step, not merely distinguish adjacent ticks.
        // Decimal arithmetic avoids deriving precision from binary subtraction noise.
        val start = BigDecimal.valueOf(min)
        val end = BigDecimal.valueOf(max)
        val step = (end - start).divide(BigDecimal.valueOf(5))
        maxOf(start.stripTrailingZeros().scale(), end.stripTrailingZeros().scale(),
            step.stripTrailingZeros().scale())
    }
    fun fraction(value: Double): Float {
        val scaled = if (log) log10(value) else value
        return ((scaled - low) / (high - low)).coerceIn(-1.0, 2.0).toFloat()
    }
}

internal fun buildAxisScale(values: List<Double>, userMin: Double?, userMax: Double?, log: Boolean,
    includeZero: Boolean): ChartAxisScale {
    var low = userMin ?: values.min()
    var high = userMax ?: values.max()
    if (includeZero) { low = minOf(low, 0.0); high = maxOf(high, 0.0) }
    if (low == high) {
        if (log) { low /= 10; high *= 10 } else {
            val padding = if (low == 0.0) 1.0 else abs(low) * 0.1
            low -= padding
            high += padding
        }
    }
    return ChartAxisScale(low, high, log)
}

internal fun formatChartNumber(value: Double, format: String? = null, decimals: Int? = null): String {
    val magnitude = abs(value)
    if (format == null && magnitude != 0.0 && (magnitude < 1e-5 || magnitude >= 1e9 || (decimals ?: 0) > 16)) {
        val precision = decimals?.let { (kotlin.math.floor(log10(magnitude)).toInt() + it).coerceIn(0, 16) } ?: 2
        return String.format(Locale.getDefault(), "%.$precision" + "e", value)
    }
    val digits = format?.substringAfter('.', "")?.firstOrNull()?.digitToIntOrNull()
        ?: decimals?.coerceIn(0, 16) ?: if (magnitude >= 100) 2 else 4
    val number = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        isGroupingUsed = format?.startsWith(',') == true
        minimumFractionDigits = if (format == null) 0 else if (format.endsWith('d')) 0 else digits
        maximumFractionDigits = if (format?.endsWith('d') == true) 0 else digits
    }
    return if (format?.endsWith('%') == true) number.format(value * 100) + "%" else number.format(value)
}
