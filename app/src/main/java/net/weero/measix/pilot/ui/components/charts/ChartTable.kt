package net.weero.measix.pilot.ui.components.charts

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.ai.tools.local.ChartSpec
import net.weero.measix.pilot.data.ai.tools.local.ChartStyle

private const val TABLE_PAGE_ROWS = 6

/** Only one bounded page is composed, even for the maximum accepted tool payload. */
@Composable
internal fun ChartTable(spec: ChartSpec, seriesNames: List<String>, modifier: Modifier = Modifier) {
    val rows = remember(spec, seriesNames) {
        if (spec.style == ChartStyle.Scatter) spec.series.flatMapIndexed { index, series ->
            series.points.map { listOf(seriesNames[index], formatChartNumber(it.x, spec.xAxis.format), formatChartNumber(it.y, spec.yAxis.format)) }
        } else (0 until spec.categoryCount).map { index ->
            listOf(spec.categoryLabel(index)) + spec.series.map { formatChartNumber(it.values[index], spec.yAxis.format) }
        }
    }
    val headers = if (spec.style == ChartStyle.Scatter) listOf(stringResource(R.string.chart_mode_chart), spec.xAxis.title ?: "X", spec.yAxis.title ?: "Y")
        else listOf(spec.xAxis.title ?: "X") + seriesNames
    var page by remember(spec) { mutableIntStateOf(0) }
    val pages = (rows.size + TABLE_PAGE_ROWS - 1) / TABLE_PAGE_ROWS
    Column(modifier) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row { headers.forEach { Text(it, Modifier.width(128.dp).padding(6.dp), maxLines = 2) } }
            rows.drop(page * TABLE_PAGE_ROWS).take(TABLE_PAGE_ROWS).forEach { row ->
                Row { row.forEach { Text(it, Modifier.width(128.dp).padding(6.dp), maxLines = 2) } }
            }
        }
        if (pages > 1) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { page-- }, enabled = page > 0, modifier = Modifier.size(48.dp)) {
                Icon(HugeIcons.ArrowLeft01, stringResource(R.string.chart_previous_page))
            }
            Text("${page + 1} / $pages", Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1)
            IconButton(onClick = { page++ }, enabled = page < pages - 1, modifier = Modifier.size(48.dp)) {
                Icon(HugeIcons.ArrowRight01, stringResource(R.string.chart_next_page))
            }
        }
    }
}
