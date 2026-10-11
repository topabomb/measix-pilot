package net.weero.measix.pilot.ui.components.message.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.json.*
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.ai.tools.local.ChartSpec
import net.weero.measix.pilot.service.runtime.ToolLivePhase
import net.weero.measix.pilot.ui.components.charts.ChartCard

/** Shared by live presentation and image export; output existence alone grants no success. */
internal fun completedChart(tool: UIMessagePart.Tool, phase: ToolLivePhase): ChartSpec? {
    if (tool.toolName != "chart_display" || phase != ToolLivePhase.COMPLETED ||
        tool.resultStatus != ToolResultStatus.COMPLETED || tool.runtimeState.archive != null) return null
    return try {
        val output = tool.output.singleOrNull() as? UIMessagePart.Text ?: return null
        if (output.text.length > 1024) return null
        val result = Json.parseToJsonElement(output.text) as? JsonObject ?: return null
        if (result["status"] != JsonPrimitive("completed")) return null
        ChartSpec.parse(Json.parseToJsonElement(tool.input))
    } catch (_: IllegalArgumentException) { null }
}

internal object ChartToolUI : ToolUIRenderer {
    override val toolName = "chart_display"
    @Composable override fun displayName(context: ToolUIContext) = stringResource(R.string.assistant_page_local_tools_chart_title)
    override fun hasSummary(context: ToolUIContext) = completedChart(context.tool, context.phase) != null
    @Composable override fun Summary(context: ToolUIContext) {
        remember(context.tool, context.phase) { completedChart(context.tool, context.phase) }?.let { ChartCard(it) }
    }
    @Composable override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val spec = remember(context.tool, context.phase) { completedChart(context.tool, context.phase) }
        if (spec != null) ChartCard(spec) else DefaultToolPreview(context, toolDisplayName = displayName(context))
    }
}
