package net.weero.measix.pilot.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownAnchorsTest {
    @Test fun `headings use displayed labels rather than hidden link destinations`() {
        assertEquals("安装", markdownAnchorId(markdownHeadingText("[安装](guide.md)")))
        assertEquals("hello-code", markdownAnchorId(markdownHeadingText("Hello **code**")))
        assertEquals("图例", markdownAnchorId(markdownHeadingText("![图例](image.png)")))
    }
}
