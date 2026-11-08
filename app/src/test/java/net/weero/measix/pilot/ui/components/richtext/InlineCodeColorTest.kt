package net.weero.measix.pilot.ui.components.richtext

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InlineCodeColorTest {
    @Test
    fun cssAlphaComesLastAndInvalidChannelCountsAreRejected() {
        assertEquals(0x44aabbcc, parseInlineCodeColor("#abc4")?.toArgb())
        assertEquals(0x80ff0000.toInt(), parseInlineCodeColor("RGBA(255, 0, 0, 0.5)")?.toArgb())
        for (invalid in listOf("#abcde", "rgb(0,0,0,1)", "rgba(0,0,0)", "rgb(256,0,0)", "rgba(0,0,0,NaN)", "rgb(0x1p7,0,0)", "rgb(12f,0,0)")) {
            assertNull(invalid, parseInlineCodeColor(invalid))
        }
    }
}
