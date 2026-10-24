package net.weero.measix.pilot.ui.components.richtext

import net.weero.measix.pilot.utils.MarkdownSource
import net.weero.measix.pilot.utils.MarkdownFence
import net.weero.measix.pilot.utils.prepareMarkdownSource
import net.weero.measix.pilot.utils.markdownFence
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownSourceTest {
    @Test fun `HTML backed render path preserves unknown inline tags and generic type names`() {
        val result = generateMarkdownDocument("条件 x<y>z、List<String> 和 <b>重点</b>、<answer>正文</answer>")
        assertEquals("条件 x<y>z、List<String> 和 重点、<answer>正文</answer>", result.document.body().text())
        assertEquals("重点", result.document.select("b").single().text())
        assertTrue(result.document.select("y, string, answer").isEmpty())
    }

    private fun fences(source: MarkdownSource): List<MarkdownFence> {
        val result = mutableListOf<MarkdownFence>()
        fun visit(node: ASTNode) {
            if (node.type == MarkdownElementTypes.CODE_FENCE) result += markdownFence(node, source)
            else node.children.forEach(::visit)
        }
        visit(MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source.text))
        return result
    }

    @Test fun `both render paths close CRLF CR and four backtick fences and preserve source`() {
        listOf("\r\n", "\r", "\n").forEach { eol ->
            val original = "\\(x\\)\n\n````html\n<div>\nline\n</div>\n````".replace("\n", eol)
            val source = prepareMarkdownSource(original)
            val direct = fences(source).single()
            val html = generateMarkdownDocument(original).fences.values.single()
            assertTrue(direct.closed)
            assertEquals(direct, html)
            assertEquals("<div>\nline\n</div>", direct.code)
            assertEquals("<div>${eol}line${eol}</div>", direct.originalCode)
        }
    }

    @Test fun `unclosed code and inline code are not changed by math preprocessing`() {
        val code = "````html\r\n\\[literal\\]\r\n```\r\n\\(still code\\)"
        val prepared = prepareMarkdownSource(code)
        assertEquals(code.replace("\r\n", "\n"), prepared.text)
        assertFalse(fences(prepared).single().closed)
        assertFalse(generateMarkdownDocument(code).fences.values.single().closed)
        assertEquals("`\\(literal\\)` and $" + "x$", prepareMarkdownSource("`\\(literal\\)` and \\(x\\)").text)
    }

    @Test fun `single pass keeps code ranges stable after earlier math shrinks`() {
        val original = "\\(one\\) \\(two\\)\n\n```text\n\\[literal\\]\n```\n\n\\[a<b & c\\]"
        val prepared = prepareMarkdownSource(original)
        assertTrue(prepared.text.contains("```text\n\\[literal\\]\n```"))
        assertTrue(prepared.text.endsWith("$$" + "a<b & c" + "$$"))
        assertEquals("\\[literal\\]", fences(prepared).single().originalCode)
    }

    @Test fun `escaped backslashes and formula line spacing do not become block math`() {
        val formula = "\\[\\begin{cases}a\\\\[4pt]b\\end{cases}\\]"
        val prepared = prepareMarkdownSource(formula)
        assertEquals("$$" + "\\begin{cases}a\\\\[4pt]b\\end{cases}" + "$$", prepared.text)
        assertEquals("\\\\[4pt] literal", prepareMarkdownSource("\\\\[4pt] literal").text)
        assertEquals("\\begin{cases}a\\\\b\\end{cases}", processLatex(formula))
        assertEquals("a\\\\[label]b", processLatex("a\\\\[label]b"))
    }

    @Test fun `HTML generation escapes math exactly once without losing comparisons`() {
        val document = generateMarkdownDocument("<b>intro</b>\n\n$" + "0<x<1 & y>0$\n\n" + "$$" + "\\lambda(x)<k & z" + "$$")
        val math = document.document.select("span.math")
        assertEquals(listOf("0<x<1 & y>0", "\\lambda(x)<k & z"), math.map { it.wholeText() })
        assertEquals("true", math[0].attr("inline"))
        assertEquals("false", math[1].attr("inline"))
    }

    @Test fun `model HTML cannot supply trusted fence closure or original source`() {
        val result = generateMarkdownDocument("<pre data-pilot-fence=\"0\"><code class=\"language-html\">fake</code></pre>\n\n```html\n<div>")
        assertEquals(2, result.document.select("pre").size)
        assertEquals(1, result.fences.size)
        assertFalse(result.fences.values.single().closed)
        assertEquals("<div>", result.fences.values.single().originalCode)
        assertFalse(result.fences.containsKey(result.document.select("pre").first()))
    }
}
