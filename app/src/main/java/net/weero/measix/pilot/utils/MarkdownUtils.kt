package net.weero.measix.pilot.utils

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Tag

/** Unknown inline tags are literal text (comparisons and generic type names), not markup. */
internal object InlineHtmlTagProvider : GeneratingProvider {
    private val tagName = Regex("""^</?([A-Za-z][A-Za-z0-9:-]*)""")
    override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
        val raw = text.substring(node.startOffset, node.endOffset)
        val name = tagName.find(raw)?.groupValues?.get(1)
        visitor.consumeHtml(if (name != null && !Tag.isKnownTag(name.lowercase(java.util.Locale.ROOT))) {
            raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        } else raw)
    }
}

/** Parse once for speech. The resulting plain text must never be parsed or entity-decoded again. */
fun String.stripMarkdown(): String {
    val source = prepareMarkdownSource(this).text
    val flavour = GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true)
    val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(source)
    val silent = object : GeneratingProvider {
        override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: org.intellij.markdown.ast.ASTNode) = Unit
    }
    val providers = flavour.createHtmlGeneratingProviders(LinkMap.buildLinkMap(tree, source), null) +
        mapOf(MarkdownTokenTypes.HTML_TAG to InlineHtmlTagProvider) +
        listOf(MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK, MarkdownElementTypes.CODE_SPAN,
            MarkdownElementTypes.IMAGE, GFMElementTypes.INLINE_MATH, GFMElementTypes.BLOCK_MATH).associateWith { silent }
    val document = Jsoup.parse(HtmlGenerator(source, tree, providers, false).generateHtml())
    return buildString {
        fun visit(node: Node) {
            when (node) {
                is TextNode -> append(node.wholeText)
                is Element -> {
                    when (node.normalName()) {
                        "script", "style", "pre", "code", "img", "math" -> return
                        "br" -> { append('\n'); return }
                    }
                    node.childNodes().forEach(::visit)
                    if (node.isBlock) append('\n')
                    else if (node.normalName() in setOf("td", "th")) append(' ')
                }
            }
        }
        visit(document.body())
    }.replace(Regex("[ \\t]*\\n\\s*"), "\n")
        .replace(Regex("[ \\t]{2,}"), " ").trim()
}

fun String.extractThinkingTitle(): String? {
    // 按行分割文本
    val lines = this.lines()

    // 从后往前查找最后一个符合条件的加粗文本行
    for (i in lines.indices.reversed()) {
        val line = lines[i].trim()

        // 检查是否为加粗格式且独占一整行
        val boldPattern = Regex("^\\*\\*(.+?)\\*\\*$")
        val match = boldPattern.find(line)

        if (match != null) {
            // 返回加粗标记内的文本内容
            return match.groupValues[1].trim().takeUnless { it.isBlank() }
        }
    }

    return null
}
