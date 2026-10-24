package net.weero.measix.pilot.ui.components.richtext

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LatexConsumerAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun productionRendererDrawsMacrosUnicodeAlphabetsAndComplexFormula() {
        val formulas = listOf(
            """\newcommand{\pilotReleaseSquare}[1]{#1^{2}}\pilotReleaseSquare{x}""",
            """\text{αβγΩ}""",
            """\text{Привет}""",
            """\[\begin{aligned}\int_0^\infty e^{-x^2}\,dx &= \frac{\sqrt{\pi}}{2}\\[4pt] A &= \begin{pmatrix}1&2\\3&4\end{pmatrix}\end{aligned}\]""",
        )
        formulas.forEach { formula ->
            val drawable = requireNotNull(getLatexDrawable(formula, 32f, Color.BLACK, Color.TRANSPARENT)) {
                "Production renderer fell back for $formula"
            }
            assertTrue(drawable.bounds.width() > 0)
            assertTrue(drawable.bounds.height() > 0)
            val bitmap = Bitmap.createBitmap(
                drawable.bounds.width(), drawable.bounds.height(), Bitmap.Config.ARGB_8888
            )
            try {
                drawable.draw(Canvas(bitmap))
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("Formula produced no ink: $formula", pixels.any { Color.alpha(it) > 0 })
            } finally {
                bitmap.recycle()
            }
        }
        val measured = assumeLatexSize("""\(\frac{1}{2}+\sqrt{x}\)""", 32f)
        assertTrue(measured.width() > 0 && measured.height() > 0)
        assertTrue(splitLatex("a+b+c+d+e", 80f, 32f, Color.BLACK).isNotEmpty())
    }

    @Test
    fun invalidFormulaFallsBackToItsOriginalVisibleText() {
        val invalid = """\pilotUnknownReleaseCommand{x}"""
        assertNull(getLatexDrawable(invalid, 32f, Color.BLACK, Color.TRANSPARENT))
        assertTrue(assumeLatexSize(invalid, 32f).isEmpty)
        compose.setContent {
            MaterialTheme { LatexText(latex = invalid, fontSize = 16.sp) }
        }
        compose.onNodeWithText(invalid).assertIsDisplayed()
    }
}
