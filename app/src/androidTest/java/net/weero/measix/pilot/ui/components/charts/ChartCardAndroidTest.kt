package net.weero.measix.pilot.ui.components.charts

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import net.weero.measix.pilot.R
import net.weero.measix.pilot.data.ai.tools.local.ChartSpec
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue

class ChartCardAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun chartStylesAndBoundedTableRemainUsableOnCompactWidth() {
        val sample = mutableStateOf(ChartSpec.parse(Json.parseToJsonElement(
            """{"style":"line","title":"Revenue","series":[{"values":[1,3,2]}]}""")))
        compose.setContent {
            MaterialTheme {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) { ChartCard(sample.value) }
            }
        }
        for (style in listOf("line", "bar", "scatter")) {
            val series = if (style == "scatter") """{"points":[{"x":1,"y":2},{"x":3,"y":4}]}"""
                else """{"values":[-3,0,8]}"""
            compose.runOnIdle {
                sample.value = ChartSpec.parse(Json.parseToJsonElement("""{"style":"$style","title":"Revenue","series":[$series]}"""))
            }
            compose.onNodeWithText("Revenue").assertIsDisplayed()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.chart_mode_table)).performClick()
            compose.onNodeWithText("X").assertExists()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.chart_mode_chart)).performClick()
        }
        compose.runOnIdle {
            sample.value = ChartSpec.parse(Json.parseToJsonElement(
                """{"style":"scatter","y_axis":{"format":".0%"},"series":[{"points":[{"x":1,"y":0.5}]}]}"""))
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.chart_mode_table)).performClick()
        compose.onNodeWithText("50%").assertExists()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.chart_mode_chart)).performClick()
        compose.runOnIdle {
            sample.value = ChartSpec.parse(Json.parseToJsonElement(
                """{"style":"line","series":[{"values":[${(1..1000).joinToString(",")}]}]}"""))
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.chart_mode_table)).performClick()
        compose.onNodeWithText("1 / 167").assertExists()
        compose.onAllNodesWithText("7").assertCountEquals(0)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.chart_next_page)).performScrollTo().performClick()
        compose.onNodeWithText("2 / 167").assertExists()
        compose.onAllNodesWithText("7").assertCountEquals(2)
        compose.onAllNodesWithText("1").assertCountEquals(0)
        compose.onAllNodesWithText("1000").assertCountEquals(0)
    }

    @Test fun narrowTableKeepsLargeFontPageCountAndControlsSeparate() {
        fun spec(rows: Int) = ChartSpec.parse(Json.parseToJsonElement(
            """{"style":"line","series":[{"values":[${(1..rows).joinToString(",")}]}]}"""))
        val sample = mutableStateOf(spec(1000))
        compose.setContent {
            MaterialTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.5f)) {
                    ChartTable(sample.value, listOf("Series"), Modifier.width(240.dp))
                }
            }
        }
        val previous = compose.onNodeWithContentDescription(compose.activity.getString(R.string.chart_previous_page))
        val next = compose.onNodeWithContentDescription(compose.activity.getString(R.string.chart_next_page))
        previous.assertIsNotEnabled().assertWidthIsAtLeast(48.dp)
        next.assertWidthIsAtLeast(48.dp)
        val count = compose.onNodeWithText("1 / 167").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(previous.fetchSemanticsNode().boundsInRoot.right <= count.left)
        assertTrue(count.right <= next.fetchSemanticsNode().boundsInRoot.left)
        next.performClick()
        compose.onNodeWithText("2 / 167").assertIsDisplayed()
        // The same controls reach the final page without replaying 166 identical transitions.
        compose.runOnIdle { sample.value = spec(13) }
        compose.onNodeWithText("1 / 3").assertIsDisplayed()
        repeat(2) { next.performClick() }
        compose.onNodeWithText("3 / 3").assertIsDisplayed()
        next.assertIsNotEnabled()
        previous.performClick()
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
    }
}
