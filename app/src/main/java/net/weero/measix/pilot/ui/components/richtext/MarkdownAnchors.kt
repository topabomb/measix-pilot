package net.weero.measix.pilot.ui.components.richtext

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester

internal val LocalMarkdownAnchors = compositionLocalOf<MutableMap<String, MutableList<BringIntoViewRequester>>?> { null }

internal fun markdownHeadingText(source: String): String {
    val flavour = org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor()
    val tree = org.intellij.markdown.parser.MarkdownParser(flavour).buildMarkdownTreeFromString(source)
    fun imageLabel(node: org.intellij.markdown.ast.ASTNode): org.intellij.markdown.ast.ASTNode? =
        if (node.type == org.intellij.markdown.MarkdownElementTypes.LINK_TEXT) node
        else node.children.firstNotNullOfOrNull { imageLabel(it) }
    fun labels(node: org.intellij.markdown.ast.ASTNode): String {
        if (node.type == org.intellij.markdown.MarkdownElementTypes.IMAGE) {
            val label = imageLabel(node)
            return label?.let { source.substring(it.startOffset, it.endOffset).removePrefix("[").removeSuffix("]") } ?: ""
        }
        if (node.children.isEmpty()) return source.substring(node.startOffset, node.endOffset)
        return node.children.joinToString("") { labels(it) }
    }
    val document = generateMarkdownDocument(labels(tree)).document
    document.select("img").forEach { it.replaceWith(org.jsoup.nodes.TextNode(it.attr("alt"))) }
    return document.text()
}

internal fun markdownAnchorId(text: String): String = text.trim().lowercase()
    .replace(Regex("[^\\p{L}\\p{N}\\s_-]"), "").replace(Regex("\\s+"), "-")

@Composable
internal fun Modifier.markdownAnchor(text: String, explicitId: String? = null): Modifier {
    val anchors = LocalMarkdownAnchors.current ?: return this
    val id = explicitId?.takeIf { it.isNotBlank() } ?: markdownAnchorId(text)
    val requester = remember { BringIntoViewRequester() }
    DisposableEffect(anchors, id, requester) {
        anchors.getOrPut(id) { mutableListOf() }.add(requester)
        onDispose { anchors[id]?.let { it.remove(requester); if (it.isEmpty()) anchors.remove(id) } }
    }
    return bringIntoViewRequester(requester)
}
