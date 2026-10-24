package net.weero.measix.pilot.utils

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** Parsing changes are a view; offsets still address the unmodified message for copy/export. */
internal class MarkdownSource(val original: String, val text: String, private val offsets: IntArray) {
    fun originalSlice(start: Int, end: Int): String = original.substring(offsets[start], offsets[end])
}

internal fun prepareMarkdownSource(original: String): MarkdownSource {
    val normalized = StringBuilder()
    val positions = ArrayList<Int>()
    var index = 0
    while (index < original.length) {
        positions += index
        val char = original[index++]
        normalized.append(if (char == '\r') '\n' else char)
        if (char == '\r' && index < original.length && original[index] == '\n') index++
    }
    positions += original.length
    val input = normalized.toString()
    // Use the parser's ranges: four-backtick, indented, inline and unclosed code all stay literal.
    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(input)
    val protected = ArrayList<IntRange>()
    fun collect(node: ASTNode) {
        if (node.type in setOf(MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_SPAN,
                MarkdownElementTypes.CODE_BLOCK)) {
            protected += node.startOffset until node.endOffset
        } else node.children.forEach(::collect)
    }
    collect(tree)
    val output = StringBuilder()
    val mapped = ArrayList<Int>()
    fun copy(start: Int, end: Int) {
        for (i in start until end) { output.append(input[i]); mapped += positions[i] }
    }
    fun delimiter(value: String, at: Int) {
        value.forEach { output.append(it); mapped += positions[at] }
    }
    var cursor = 0
    // Consume escaped backslash pairs in the same pass, before considering math delimiters.
    val delimiters = Regex("""\\\\|\\\((.+?)\\\)|\\\[([\s\S]+?)\\]""")
    delimiters.findAll(input).forEach { match ->
        val body = match.groups[1] ?: match.groups[2]
        if (body == null || protected.any { match.range.first <= it.last && match.range.last >= it.first }) {
            return@forEach
        }
        copy(cursor, match.range.first)
        val marker = if (match.groups[1] != null) "$" else "$$"
        delimiter(marker, match.range.first)
        copy(body.range.first, body.range.last + 1)
        delimiter(marker, match.range.last - 1)
        cursor = match.range.last + 1
    }
    copy(cursor, input.length)
    mapped += original.length
    return MarkdownSource(original, output.toString(), mapped.toIntArray())
}

internal data class MarkdownFence(
    val code: String,
    val originalCode: String,
    val language: String,
    val closed: Boolean,
    val startOffset: Int,
)

internal fun markdownFence(node: ASTNode, source: MarkdownSource): MarkdownFence {
    val contentIndex = node.children.indexOfFirst { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
    val start = if (contentIndex < 0) null else node.children.take(contentIndex)
        .lastOrNull { it.type == MarkdownTokenTypes.EOL }?.endOffset
    val end = node.children.lastOrNull { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }?.endOffset
    val language = node.children.firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }
        ?.let { source.text.substring(it.startOffset, it.endOffset) } ?: "plaintext"
    return MarkdownFence(
        code = if (start != null && end != null) source.text.substring(start, end).trimIndent() else "",
        originalCode = if (start != null && end != null) source.originalSlice(start, end) else "",
        language = language,
        closed = node.children.any { it.type == MarkdownTokenTypes.CODE_FENCE_END },
        startOffset = node.startOffset,
    )
}
