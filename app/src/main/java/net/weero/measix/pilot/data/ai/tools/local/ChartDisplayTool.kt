package net.weero.measix.pilot.data.ai.tools.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private val CHART_STYLES = listOf("line", "bar", "scatter")
private val AXIS_SCALES = listOf("linear", "log")
private const val MAX_SERIES = MAX_CHART_SERIES
private const val MAX_SERIES_POINTS = MAX_CHART_POINTS

internal fun buildChartDisplayTool(): Tool = Tool(
    name = "chart_display",
    description = """
        Display a line, bar, or scatter chart to the user inside the chat.
        For line and bar charts, every series must use 'values' (numbers aligned by position with x_axis.data)
        and must not use 'points'. For scatter charts, every series must use 'points' ({x, y} numbers)
        and must not use 'values'.
        Bar charts always start from 0, so axis 'min'/'max' are not allowed for bar charts.
        For line and bar charts the X axis is categorical: x_axis only supports 'title' and 'data',
        so x_axis 'min'/'max' and log scale are not allowed; use y_axis for range and log scale.
        Axis 'data' labels are only allowed on x_axis for line and bar charts.
        At most $MAX_SERIES series and $MAX_SERIES_POINTS total points. All categorical series have equal lengths. Bar axes are linear.
        Values must be zero or have finite magnitude from 1e-100 to 1e100. Labels are at most 160 characters.
        Normalized JSON payload is at most 65536 characters.
        Explicit numeric bounds must contain every data point.
        The chart is rendered for the user directly; you don't need to repeat the raw data in your reply.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("style", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { CHART_STYLES.forEach { add(it) } })
                    put("description", "Chart type.")
                })
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Chart title, shown at the top.")
                })
                put("x_axis", buildAxisSchema())
                put("y_axis", buildAxisSchema())
                put("series", buildJsonObject {
                    put("type", "array")
                    put("description", "One or more data series (1-$MAX_SERIES).")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("name", buildJsonObject {
                                put("type", "string")
                                put("description", "Series name; used for the legend when there are multiple series.")
                            })
                            put("color", buildJsonObject {
                                put("type", "string")
                                put("description", "Hex color such as '#FF8800' or '#F80'. Some clients may ignore it.")
                            })
                            put("values", buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "number") })
                                put(
                                    "description",
                                    "1D data for line/bar charts, aligned by position with x_axis.data."
                                )
                            })
                            put("points", buildJsonObject {
                                put("type", "array")
                                put("description", "2D data points for scatter charts.")
                                put("items", buildJsonObject {
                                    put("type", "object")
                                    put("properties", buildJsonObject {
                                        put("x", buildJsonObject { put("type", "number") })
                                        put("y", buildJsonObject { put("type", "number") })
                                    })
                                    put("required", buildJsonArray {
                                        add("x")
                                        add("y")
                                    })
                                })
                            })
                        })
                    })
                })
            },
            required = listOf("style", "series")
        )
    },
    validateArguments = { args ->
        try { ChartSpec.parse(args); null } catch (error: IllegalArgumentException) {
            buildJsonObject { put("reason", "invalid_arguments"); put("detail", error.message) }
        }
    },
    outputPolicy = me.rerere.ai.core.ToolOutputPolicy.PRESERVE,
    execute = { args ->
        ChartSpec.parse(args)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("status", "completed")
            put("message", "Chart displayed to the user.")
        }.toString()))
    },
)

private fun buildAxisSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("title", buildJsonObject {
            put("type", "string")
            put("description", "Axis title, usually including the unit.")
        })
        put("data", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "string") })
            put("description", "Category labels (e.g. months) for line/bar charts, aligned with series values. " +
                "Only allowed on x_axis, and not for scatter charts.")
        })
        put("min", buildJsonObject {
            put("type", "number")
            put("description", "Lower bound of the display range. Allowed on y_axis for line charts and on both axes for scatter charts.")
        })
        put("max", buildJsonObject {
            put("type", "number")
            put("description", "Upper bound of the display range. Allowed on y_axis for line charts and on both axes for scatter charts.")
        })
        put("scale", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { AXIS_SCALES.forEach { add(it) } })
            put("description", "Scale type, default linear. Log is allowed on y_axis for line/scatter charts and on x_axis for scatter charts only.")
        })
        put("format", buildJsonObject {
            put("type", "string")
            put(
                "description",
                "Numeric format: .0f through .8f, d or %. Optional comma prefix for grouping."
            )
        })
    })
}
