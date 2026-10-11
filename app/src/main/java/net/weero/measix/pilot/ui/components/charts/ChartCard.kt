package net.weero.measix.pilot.ui.components.charts

import net.weero.measix.pilot.data.ai.tools.local.ChartSpec
import net.weero.measix.pilot.data.ai.tools.local.ChartStyle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.ChartLineData01
import me.rerere.hugeicons.stroke.ChartScatter
import me.rerere.hugeicons.stroke.Table
import net.weero.measix.pilot.R

enum class ChartDisplayMode { Chart, Table }

/**
 * 图表卡片: 标题、图表/表格切换、绘图区与图例
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartCard(
    spec: ChartSpec,
    modifier: Modifier = Modifier,
    initialMode: ChartDisplayMode = ChartDisplayMode.Chart,
) {
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    val colors = remember(spec) { spec.seriesColors() }
    val seriesNames = spec.series.mapIndexed { index, series ->
        series.name ?: stringResource(R.string.chart_series_name, index + 1)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    spec.title?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (mode == ChartDisplayMode.Chart) {
                        spec.yAxis.title?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                ChartModeToggle(
                    style = spec.style,
                    mode = mode,
                    onModeChange = { mode = it },
                )
            }

            when (mode) {
                ChartDisplayMode.Chart -> {
                    ChartPlot(
                        spec = spec,
                        colors = colors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                    )
                    spec.xAxis.title?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (spec.series.size > 1 || spec.series.any { it.name != null }) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            spec.series.indices.forEach { index ->
                                LegendItem(color = colors[index], label = seriesNames[index])
                            }
                        }
                    }
                }

                ChartDisplayMode.Table -> {
                    ChartTable(spec = spec, seriesNames = seriesNames, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun ChartModeToggle(
    style: ChartStyle,
    mode: ChartDisplayMode,
    onModeChange: (ChartDisplayMode) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ChartModeButton(
                icon = when (style) {
                    ChartStyle.Line -> HugeIcons.ChartLineData01
                    ChartStyle.Bar -> HugeIcons.ChartColumn
                    ChartStyle.Scatter -> HugeIcons.ChartScatter
                },
                contentDescription = stringResource(R.string.chart_mode_chart),
                selected = mode == ChartDisplayMode.Chart,
                onClick = { onModeChange(ChartDisplayMode.Chart) },
            )
            ChartModeButton(
                icon = HugeIcons.Table,
                contentDescription = stringResource(R.string.chart_mode_table),
                selected = mode == ChartDisplayMode.Table,
                onClick = { onModeChange(ChartDisplayMode.Table) },
            )
        }
    }
}

@Composable
private fun ChartModeButton(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .then(if (selected) Modifier.shadow(1.dp, shape) else Modifier)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .size(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
    }
}
