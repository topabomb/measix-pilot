package net.weero.measix.pilot.ui.components.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlin.math.roundToInt

private val hexColor = Regex("#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})")
private val functionalColor = Regex("""(rgb|rgba)\(([^()]*)\)""", RegexOption.IGNORE_CASE)

private val decimalChannel = Regex("""[+-]?(?:[0-9]+(?:\.[0-9]+)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?""")

private fun String.toColorChannel(): Double? = takeIf(decimalChannel::matches)?.toDoubleOrNull()

internal fun parseInlineCodeColor(code: String): Color? {
    val text = code.trim()
    if (hexColor.matches(text)) {
        val digits = text.substring(1)
        val rgba = if (digits.length <= 4) digits.flatMap { listOf(it, it) }.joinToString("") else digits
        val value = rgba.padEnd(8, 'f').toLong(16)
        return Color(((value ushr 8) or ((value and 0xff) shl 24)).toInt())
    }
    val match = functionalColor.matchEntire(text) ?: return null
    val channels = match.groupValues[2].split(',').map(String::trim)
    val hasAlpha = match.groupValues[1].equals("rgba", ignoreCase = true)
    if (channels.size != if (hasAlpha) 4 else 3) return null
    val rgb = channels.take(3).map { channel ->
        channel.toColorChannel()?.takeIf { it in 0.0..255.0 }?.roundToInt() ?: return null
    }
    val alpha = if (hasAlpha) {
        channels[3].toColorChannel()?.takeIf { it in 0.0..1.0 } ?: return null
    } else 1.0
    return Color(rgb[0], rgb[1], rgb[2], (alpha * 255).roundToInt())
}

internal fun AnnotatedString.Builder.appendInlineCode(
    code: String,
    inlineContents: MutableMap<String, InlineTextContent>,
    outline: Color,
) {
    val color = parseInlineCodeColor(code)
    if (color == null) {
        append(' ')
    } else {
        val key = "color:$code"
        inlineContents.putIfAbsent(key, InlineTextContent(
            Placeholder(1.1.em, 0.75.em, PlaceholderVerticalAlign.TextCenter),
        ) {
            val shape = RoundedCornerShape(2.dp)
            Box(Modifier.fillMaxSize().background(color, shape).border(1.dp, outline.copy(alpha = 0.5f), shape))
        })
        appendInlineContent(key, " ")
    }
    append(code)
    append(' ')
}
