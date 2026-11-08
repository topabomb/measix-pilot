package net.weero.measix.pilot.utils

import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.flavours.gfm.StrikeThroughDelimiterParser
import org.intellij.markdown.parser.sequentialparsers.DelimiterParser
import org.intellij.markdown.parser.sequentialparsers.EmphasisLikeParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParserManager
import org.intellij.markdown.parser.sequentialparsers.TokensCache
import org.intellij.markdown.parser.sequentialparsers.impl.EmphStrongDelimiterParser

internal class PilotMarkdownFlavour(
    makeHttpsAutoLinks: Boolean = false,
    useSafeLinks: Boolean = true,
    absolutizeAnchorLinks: Boolean = false,
) : GFMFlavourDescriptor(useSafeLinks, absolutizeAnchorLinks, makeHttpsAutoLinks) {
    private val gfmParsers = super.sequentialParserManager

    override val sequentialParserManager = object : SequentialParserManager() {
        override fun getParserSequence(): List<SequentialParser> = gfmParsers.getParserSequence().map { parser ->
            if (parser is EmphasisLikeParser) {
                EmphasisLikeParser(EmphStrongDelimiterParser(), StrikeThroughAtFirstDelimiter())
            } else {
                parser
            }
        }
    }
}

// Adapted from rikkahub/markdown d79a97cc8e, Apache-2.0 (docs/licenses/jetbrains-markdown.txt).
// Keep the original scan and matching rules, but process an opener at index zero as well.
private class StrikeThroughAtFirstDelimiter : DelimiterParser() {
    private val scanner = StrikeThroughDelimiterParser()

    override fun scan(tokens: TokensCache, iterator: TokensCache.Iterator, delimiters: MutableList<Info>): Int =
        scanner.scan(tokens, iterator, delimiters)

    override fun process(
        tokens: TokensCache,
        iterator: TokensCache.Iterator,
        delimiters: MutableList<Info>,
        result: SequentialParser.ParsingResultBuilder,
    ) {
        var index = delimiters.lastIndex
        while (index >= 0) {
            val delimiter = delimiters[index]
            if (delimiter.tokenType != GFMTokenTypes.TILDE || delimiter.closerIndex == -1) {
                index--
                continue
            }
            var opener = index
            var closer = delimiter.closerIndex
            while (EmphStrongDelimiterParser.areAdjacentSameMarkers(delimiters, opener, closer)) {
                opener--
                closer++
            }
            if (index - opener < 2) {
                val range = delimiters[opener].position..delimiters[closer].position + 1
                result.withNode(SequentialParser.Node(range, GFMElementTypes.STRIKETHROUGH))
            }
            index = opener - 1
        }
    }
}
