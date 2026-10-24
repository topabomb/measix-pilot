package net.weero.measix.pilot.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownUtilsTest {
    @Test fun `speech preserves visible text without HTML markup or comments`() {
        assertEquals("标题\n内容", "<details>\n<summary>标题</summary>\n\n内容\n\n</details>".stripMarkdown())
        assertEquals("这是重点文字", "这是<b class='x'>重点</b><span style='color:red'>文字</span>".stripMarkdown())
        assertEquals("第一行\n第二行", "第一行<br/>第二行".stripMarkdown())
        assertEquals("正文", "<!-- hidden --><style>.x{color:red}</style>正文".stripMarkdown())
    }

    @Test fun `speech decodes entities once and retains comparison operators`() {
        assertEquals("a < b & c", "a &lt; b &amp; c".stripMarkdown())
        assertEquals("<tag> &lt;tag&gt;", "&lt;tag&gt; &amp;lt;tag&amp;gt;".stripMarkdown())
        assertEquals("条件 x<y>z", "条件 x<y>z".stripMarkdown())
        assertEquals("List<String> 与 <answer>正文</answer>", "List<String> 与 <answer>正文</answer>".stripMarkdown())
        assertEquals("条件 x<y && y>0", "条件 x<y && y>0".stripMarkdown())
        assertEquals("1 < 2 且 3 > 2", "1 < 2 且 3 > 2".stripMarkdown())
        assertEquals("(开心) 你好 [笑]", "(开心) 你好 [笑]".stripMarkdown())
    }

    @Test fun `speech retains link labels and list structure`() {
        assertEquals("标题\n这是重点和链接\n第一项\n第二项\n引用",
            "# 标题\n\n这是**重点**和[链接](https://example.com)\n\n- 第一项\n- 第二项\n\n> 引用".stripMarkdown())
    }

    @Test fun `speech excludes code images and math without removing surrounding words`() {
        assertEquals("运行 即可\n结束", "运行 `ls` 即可\n\n```kotlin\nval a = 1\n```\n\n结束".stripMarkdown())
        assertEquals("开始 结束", "开始 ![图片](https://example.com/a.png) 结束".stripMarkdown())
        assertEquals("开始 结束", "开始 \\(x < y\\) 结束".stripMarkdown())
        assertEquals("开始\n结束", "开始\n\n\$\$x+y\$\$\n\n结束".stripMarkdown())
        assertEquals("开始", "开始\n\n````text\nnot spoken\n```\nstill code".stripMarkdown())
    }
}
