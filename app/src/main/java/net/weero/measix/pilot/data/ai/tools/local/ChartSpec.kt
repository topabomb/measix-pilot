package net.weero.measix.pilot.data.ai.tools.local

import kotlinx.serialization.json.*
import kotlin.math.abs

internal const val MAX_CHART_CHARACTERS = 64 * 1024
internal const val MAX_CHART_POINTS = 1000
internal const val MAX_CHART_SERIES = 8

enum class ChartStyle { Line, Bar, Scatter }
enum class ChartScaleType { Linear, Log }

data class ChartAxis(
    val title: String? = null,
    val data: List<String> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    val scale: ChartScaleType = ChartScaleType.Linear,
    val format: String? = null,
)
data class ChartPoint(val x: Double, val y: Double)
data class ChartSeries(
    val name: String?,
    val color: String?,
    val values: List<Double>,
    val points: List<ChartPoint>,
)

/** The tool and all renderers use this parser; malformed points are never silently dropped. */
data class ChartSpec private constructor(
    val style: ChartStyle,
    val title: String?,
    val xAxis: ChartAxis,
    val yAxis: ChartAxis,
    val series: List<ChartSeries>,
) {
    val categoryCount: Int get() = series.first().values.size
    fun categoryLabel(index: Int): String = xAxis.data.getOrNull(index) ?: (index + 1).toString()

    companion object {
        fun parse(element: JsonElement): ChartSpec {
            require(element.toString().length <= MAX_CHART_CHARACTERS) { "Chart payload exceeds $MAX_CHART_CHARACTERS characters" }
            val obj = element as? JsonObject ?: errorArgument("Chart must be an object")
            val style = when (obj.text("style", required = true)) {
                "line" -> ChartStyle.Line
                "bar" -> ChartStyle.Bar
                "scatter" -> ChartStyle.Scatter
                else -> errorArgument("style must be line, bar, or scatter")
            }
            val x = obj.axis("x_axis")
            val y = obj.axis("y_axis")
            require(y.data.isEmpty() && (style != ChartStyle.Scatter || x.data.isEmpty())) { "Only categorical x_axis supports data labels" }
            if (style != ChartStyle.Scatter) require(x.min == null && x.max == null && x.scale == ChartScaleType.Linear) { "Categorical x_axis cannot have numeric bounds or log scale" }
            if (style == ChartStyle.Bar) require(y.min == null && y.max == null && y.scale == ChartScaleType.Linear) { "Bars require a linear zero baseline without explicit bounds" }
            val input = obj["series"] as? JsonArray ?: errorArgument("series must be an array")
            require(input.size in 1..MAX_CHART_SERIES) { "Chart requires 1..$MAX_CHART_SERIES series" }
            var total = 0
            val series = input.map { element ->
                val item = element as? JsonObject ?: errorArgument("Each series must be an object")
                val color = item.text("color")
                require(color == null || Regex("#[0-9a-fA-F]{6}|#[0-9a-fA-F]{3}").matches(color)) { "color must be #RGB or #RRGGBB" }
                val scatter = style == ChartStyle.Scatter
                require((if (scatter) "values" else "points") !in item) { "Use points for scatter; values for line and bar" }
                val data = item[if (scatter) "points" else "values"] as? JsonArray ?: errorArgument("Series data must be an array")
                require(data.isNotEmpty()) { "Series data must not be empty" }
                total += data.size
                require(total <= MAX_CHART_POINTS) { "Chart exceeds $MAX_CHART_POINTS total points" }
                val points = if (scatter) data.map { point ->
                    val p = point as? JsonObject ?: errorArgument("Scatter points must have numeric x and y")
                    ChartPoint(number(p["x"]), number(p["y"]))
                } else emptyList()
                val values = if (scatter) emptyList() else data.map(::number)
                ChartSeries(item.text("name"), color, values, points)
            }
            if (style != ChartStyle.Scatter) {
                val count = series.first().values.size
                require(series.all { it.values.size == count }) { "All categorical series must have equal lengths" }
                require(x.data.isEmpty() || x.data.size == count) { "x_axis.data must match series lengths" }
            }
            val ys = if (style == ChartStyle.Scatter) series.flatMap { it.points.map(ChartPoint::y) } else series.flatMap { it.values }
            y.validateDomain(ys)
            if (style == ChartStyle.Scatter) x.validateDomain(series.flatMap { it.points.map(ChartPoint::x) })
            return ChartSpec(style, obj.text("title"), x, y, series)
        }
    }
}

private fun errorArgument(detail: String): Nothing = throw IllegalArgumentException(detail)

private fun number(element: JsonElement?): Double {
    val primitive = element as? JsonPrimitive
    val value = primitive?.takeUnless { it.isString }?.doubleOrNull
        ?: errorArgument("Chart values must be numbers")
    require(value != 0.0 || primitive.content.toBigDecimalOrNull()?.signum() == 0) { "Nonzero chart value underflows the supported range" }
    require(value.isFinite() && abs(value) <= 1e100 && (value == 0.0 || abs(value) >= 1e-100)) {
        "Chart values must be zero or finite numbers with magnitude between 1e-100 and 1e100"
    }
    return value
}

private fun JsonObject.text(key: String, required: Boolean = false): String? {
    val raw = this[key] ?: return if (required) errorArgument("$key is required") else null
    val text = (raw as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?: errorArgument("$key must be a string")
    require(text.length <= 160 && text.none { it.isISOControl() }) { "$key must be at most 160 printable characters" }
    return text
}

private fun JsonObject.axis(key: String): ChartAxis {
    val raw = this[key] ?: return ChartAxis()
    val axis = raw as? JsonObject ?: errorArgument("$key must be an object")
    val scale = when (axis.text("scale")) {
        null, "linear" -> ChartScaleType.Linear
        "log" -> ChartScaleType.Log
        else -> errorArgument("Axis scale must be linear or log")
    }
    val labels = axis["data"]?.let { data ->
        val array = data as? JsonArray ?: errorArgument("Axis data must be an array")
        require(array.size <= MAX_CHART_POINTS) { "Too many category labels" }
        array.map { JsonObject(mapOf("label" to it)).text("label", required = true)!! }
    }.orEmpty()
    val format = axis.text("format")
    require(format == null || Regex("(,)?(\\.[0-8])?[fd%]").matches(format)) { "Unsupported numeric format; use .0f through .8f, d, or %" }
    val min = axis["min"]?.let(::number)
    val max = axis["max"]?.let(::number)
    require(min == null || max == null || min < max) { "Axis min must be less than max" }
    require(scale != ChartScaleType.Log || listOfNotNull(min, max).all { it > 0 }) { "Log bounds must be positive" }
    return ChartAxis(axis.text("title"), labels, min, max, scale, format)
}

private fun ChartAxis.validateDomain(values: List<Double>) {
    require(scale != ChartScaleType.Log || values.all { it > 0 }) { "Log values must be positive" }
    val low = min ?: values.min()
    val high = max ?: values.max()
    require(low <= values.min() && high >= values.max()) { "Explicit bounds must contain all data points" }
    require(low < high || (min == null && max == null && low == high)) { "Axis bounds do not form an increasing domain" }
    require(scale != ChartScaleType.Log || low == high || kotlin.math.log10(low) < kotlin.math.log10(high)) {
        "Log domain is too narrow to represent"
    }
}
