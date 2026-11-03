package me.rerere.tts.controller

/** Paragraph-aware speech chunks; only a new playback start prefers a short opening. */
class TextChunker(private val maxChunkLength: Int = 150) {
    init {
        require(maxChunkLength > 1) { "max_tts_chunk_length_too_small" }
    }

    fun split(text: String, prioritizeFirst: Boolean = false): List<TtsChunk> {
        val chunks = mutableListOf<String>()
        for (paragraph in text.replace("\r\n", "\n").split(Regex("\n[ \t]*\n+"))) {
            val value = paragraph.trim()
            val annotations = annotationRegex.findAll(value).map { it.range }.toList()
            var offset = 0
            while (offset < value.length) {
                val opening = prioritizeFirst && chunks.isEmpty()
                val end = boundary(value, offset, annotations, opening)
                chunks += value.substring(offset, end).trim()
                offset = end
                while (offset < value.length && value[offset].isWhitespace()) offset++
            }
        }
        return chunks.mapIndexed { index, value -> TtsChunk(text = value, index = index) }
    }

    private val annotationRegex = Regex("\\[[^\\]\r\n]*]|<[^>\r\n]*>|\\([^()\r\n]*\\)")

    private fun boundary(text: String, start: Int, annotations: List<IntRange>, opening: Boolean): Int {
        val hardEnd = minOf(text.length, start + maxChunkLength).let {
            if (it < text.length && text[it - 1].isHighSurrogate() && text[it].isLowSurrogate()) it - 1 else it
        }
        // Scan annotations once per paragraph, then inspect only those intersecting this window.
        val found = annotations.binarySearchBy(start) { it.last }
        val first = if (found >= 0) found else -found - 1
        val active = annotations.subList(first, annotations.size).asSequence().takeWhile { it.first < hardEnd }.toList()
        fun safe(end: Int) = active.none { end > it.first && end <= it.last }
        val sentenceEnds = mutableListOf<Int>()
        val clauseEnds = mutableListOf<Int>()
        val wordEnds = mutableListOf<Int>()
        for (i in start until hardEnd) {
            if (!safe(i + 1)) continue
            val c = text[i]
            val embeddedPeriod = c == '.' && i > start && i + 1 < text.length &&
                text[i - 1].isLetterOrDigit() && text[i + 1].isLetterOrDigit()
            var end = i + 1
            while (end < hardEnd && text[end] in "\"'”’」』）") end++
            if (c in "。！？.!?" && !embeddedPeriod) sentenceEnds += end
            if (c in "，、：；,;:\n") clauseEnds += end
            if (c.isWhitespace() && i > start && safe(i)) wordEnds += i
        }
        if (opening) {
            val searchEnd = minOf(start + 80, hardEnd)
            sentenceEnds.firstOrNull { it <= searchEnd }?.let { return it }
            clauseEnds.filter { it <= searchEnd }.minByOrNull { kotlin.math.abs(it - start - 40) }?.let { return it }
            if (text.length <= minOf(start + 40, hardEnd)) return text.length
            wordEnds.lastOrNull { it <= minOf(start + 40, hardEnd) }?.let { return it }
        } else if (text.length <= hardEnd) return text.length
        // Prefer natural boundaries over cutting a word, number or annotation at the soft target.
        (sentenceEnds + clauseEnds + wordEnds).maxOrNull()?.let { return it }
        if (!safe(hardEnd)) {
            active.firstOrNull { hardEnd > it.first && hardEnd <= it.last && it.first > start }
                ?.let { return it.first }
        }
        return hardEnd
    }
}

data class TtsChunk(
    val id: java.util.UUID = java.util.UUID.randomUUID(),
    val index: Int,
    val text: String,
)
