package net.weero.measix.pilot.ui.components.richtext

import net.weero.measix.pilot.utils.MarkdownFence
import net.weero.measix.pilot.utils.prepareMarkdownSource
import net.weero.measix.pilot.utils.markdownFence
import androidx.compose.runtime.staticCompositionLocalOf
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import net.weero.measix.pilot.utils.InlineHtmlTagProvider
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.IdentityHashMap
import java.util.UUID

internal data class MarkdownHtmlDocument(val document: Document, val fences: Map<Element, MarkdownFence>)
internal val LocalMarkdownHtmlFences = staticCompositionLocalOf<Map<Element, MarkdownFence>> { emptyMap() }

internal fun generateMarkdownDocument(content: String): MarkdownHtmlDocument {
    val source = prepareMarkdownSource(content)
    val flavour = GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true)
    val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(source.text)
    // Generated markers are private to this parse, never a closure flag supplied by model HTML.
    val marker = "pilot-fence-" + UUID.randomUUID().toString()
    val fences = ArrayList<MarkdownFence>()
    val providers = flavour.createHtmlGeneratingProviders(LinkMap.buildLinkMap(tree, source.text), null) + mapOf(
        MarkdownTokenTypes.HTML_TAG to InlineHtmlTagProvider,
        MarkdownElementTypes.CODE_FENCE to object : GeneratingProvider {
            override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
                val fence = markdownFence(node, source)
                val index = fences.size
                fences += fence
                visitor.consumeTagOpen(node, "pre", "data-$marker=\"$index\"")
                visitor.consumeTagOpen(node, "code")
                visitor.consumeHtml(escapeMarkdownHtml(fence.code))
                visitor.consumeTagClose("code")
                visitor.consumeTagClose("pre")
            }
        },
        GFMElementTypes.INLINE_MATH to EscapedMathProvider(true),
        GFMElementTypes.BLOCK_MATH to EscapedMathProvider(false),
    )
    val document = Jsoup.parse(HtmlGenerator(source.text, tree, providers, false).generateHtml())
    val byElement = IdentityHashMap<Element, MarkdownFence>()
    document.select("pre[data-$marker]").forEach { element ->
        val index = element.attr("data-$marker").toIntOrNull()
        fences.getOrNull(index ?: -1)?.let { byElement[element] = it }
        element.removeAttr("data-$marker")
    }
    return MarkdownHtmlDocument(document, byElement)
}

private class EscapedMathProvider(private val inline: Boolean) : GeneratingProvider {
    override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
        var formula = node.children.drop(1).dropLast(1).joinToString("") { it.getTextInNode(text) }.trim()
        while (formula.length > 2 && formula.startsWith('`') && formula.endsWith('`')) {
            formula = formula.substring(1, formula.lastIndex)
        }
        visitor.consumeTagOpen(node, "span", "class=\"math\"", "inline=\"$inline\"")
        visitor.consumeHtml(escapeMarkdownHtml(formula))
        visitor.consumeTagClose("span")
    }
}

private fun escapeMarkdownHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
