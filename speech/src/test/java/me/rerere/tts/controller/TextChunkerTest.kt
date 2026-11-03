package me.rerere.tts.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {
    @Test
    fun `unpunctuated text is always split below the hard limit`() {
        val chunks = TextChunker(maxChunkLength = 16).split("a".repeat(41))

        assertEquals(listOf(16, 16, 9), chunks.map { it.text.length })
        assertEquals("a".repeat(41), chunks.joinToString("") { it.text })
    }

    @Test
    fun `hard split never separates a surrogate pair`() {
        val text = "1234567😀89abcdefghij"
        val chunks = TextChunker(maxChunkLength = 8).split(text)

        assertEquals(text, chunks.joinToString("") { it.text })
        assertTrue(chunks.all { it.text.length <= 8 })
        assertTrue(chunks.none { it.text.last().isHighSurrogate() })
        assertTrue(chunks.none { it.text.first().isLowSurrogate() })
    }

    @Test fun `opening ends at sentence while continuous append keeps larger chunks`() {
        val text = "这是第一句。这里是第二句，接下来还有需要继续朗读的内容。"
        val chunker = TextChunker(160)
        assertEquals("这是第一句。", chunker.split(text, true).first().text)
        assertEquals(listOf(text), chunker.split(text, false).map { it.text })
        assertEquals(text, chunker.split(text, true).joinToString("") { it.text })
    }

    @Test fun `opening respects decimal annotation and closing quotation`() {
        val text = "[轻声，微笑]他说：“结果是3.14。”接下来继续解释。"
        val chunks = TextChunker(160).split(text, true)
        assertEquals("[轻声，微笑]他说：“结果是3.14。”", chunks.first().text)
        assertEquals(text, chunks.joinToString("") { it.text })
    }

    @Test fun `English opening does not split words at soft target`() {
        val chunks = TextChunker(160).split("This is a reasonably long opening sentence with several words and no punctuation at all", true)
        assertEquals("This is a reasonably long opening", chunks.first().text)
        assertEquals("sentence with several words and no punctuation at all", chunks[1].text)
    }

    @Test fun `paragraphs survive and annotations near hard boundary remain whole`() {
        assertEquals(listOf("一段", "二段"), TextChunker(160).split("一段\r\n\r\n二段", true).map { it.text })
        val text = "1234567890[微笑，轻声]结束"
        val chunks = TextChunker(16).split(text, true)
        assertEquals(listOf("1234567890", "[微笑，轻声]结束"), chunks.map { it.text })
    }
}
