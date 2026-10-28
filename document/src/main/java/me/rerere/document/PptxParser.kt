package me.rerere.document

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.net.URI
import java.util.zip.ZipFile

private data class SlideContent(
    val slideNumber: Int,
    val content: String,
    val notes: String = ""
)

object PptxParser {
    fun parse(file: File): String {
        return ZipFile(file).use { zipFile ->
            val presentation = relatedParts(zipFile, "", "officeDocument").values.singleOrNull()
                ?: throw IOException("PPTX requires one presentation relationship")
            val slidePaths = relatedParts(zipFile, presentation, "slide")
            val presentationEntry = zipFile.getEntry(presentation)
                ?: throw IOException("PPTX presentation is missing: $presentation")
            val orderedPaths = zipFile.getInputStream(presentationEntry).use { stream ->
                val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
                parser.setInput(stream, "UTF-8")
                val paths = mutableListOf<String>()
                var inSlideList = false
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        if (parser.name == "sldIdLst") inSlideList = true
                        if (inSlideList && parser.name == "sldId") {
                            val id = RELATIONSHIP_NAMESPACES.firstNotNullOfOrNull { parser.getAttributeValue(it, "id") }
                                ?: throw IOException("PPTX slide has no relationship id")
                            paths += slidePaths[id] ?: throw IOException("PPTX slide relationship is missing: $id")
                        }
                    } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "sldIdLst") inSlideList = false
                    parser.next()
                }
                paths
            }
            if (orderedPaths.isEmpty()) throw IOException("PPTX contains no presentation slides")
            val slides = orderedPaths.mapIndexed { index, path ->
                val entry = zipFile.getEntry(path) ?: throw IOException("PPTX slide is missing: $path")
                val content = zipFile.getInputStream(entry).use(::parseSlideXml)
                val notes = relatedParts(zipFile, path, "notesSlide").values.let { paths ->
                    if (paths.size > 1) throw IOException("PPTX slide has multiple notes relationships: $path")
                    paths.singleOrNull()?.let { notesPath ->
                        val notesEntry = zipFile.getEntry(notesPath) ?: throw IOException("PPTX notes are missing: $notesPath")
                        zipFile.getInputStream(notesEntry).use(::parseNotesXml)
                    }.orEmpty()
                }
                SlideContent(index + 1, content, notes)
            }
            formatOutput(slides)
        }
    }

    /** Package relationships own slide order and notes identity; ZIP order and numeric filenames do not. */
    private fun relatedParts(zip: ZipFile, source: String, kind: String): Map<String, String> {
        val parent = source.substringBeforeLast('/', "")
        val relationsPath = (if (parent.isEmpty()) "" else "$parent/") + "_rels/${source.substringAfterLast('/')}.rels"
        val entry = zip.getEntry(relationsPath) ?: return emptyMap()
        return zip.getInputStream(entry).use { stream ->
            val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
            parser.setInput(stream, "UTF-8")
            val paths = linkedMapOf<String, String>()
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship" &&
                    parser.getAttributeValue(null, "Type") in RELATIONSHIP_NAMESPACES.map { "$it/$kind" }) {
                    val id = parser.getAttributeValue(null, "Id")?.takeIf { it.isNotBlank() }
                        ?: throw IOException("PPTX $kind relationship has no id")
                    val target = parser.getAttributeValue(null, "Target")?.takeIf { it.isNotBlank() }
                        ?: throw IOException("PPTX $kind relationship has no target")
                    if (parser.getAttributeValue(null, "TargetMode") == "External") throw IOException("PPTX external $kind relationship is unsupported")
                    val resolved = URI(null, null, "/$source", null).resolve(target).normalize()
                    val path = resolved.path.orEmpty()
                    if (resolved.isAbsolute || resolved.rawAuthority != null || resolved.rawQuery != null || resolved.rawFragment != null ||
                        !path.startsWith('/') || path.startsWith("/../") || path == "/.." || '\\' in path) {
                        throw IOException("PPTX $kind relationship must target a package part")
                    }
                    if (paths.put(id, path.removePrefix("/")) != null) throw IOException("PPTX duplicate relationship id: $id")
                }
                parser.next()
            }
            paths
        }
    }

    private val RELATIONSHIP_NAMESPACES = listOf(
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
        "http://purl.oclc.org/ooxml/officeDocument/relationships",
    )

    private fun formatOutput(slides: List<SlideContent>): String {
        val result = StringBuilder()

        slides.forEach { slide ->
            result.append("## Slide ${slide.slideNumber}\n\n")
            result.append(slide.content)

            if (slide.notes.isNotBlank()) {
                result.append("\n### Speaker Notes\n\n")
                result.append(slide.notes)
            }

            result.append("\n")
        }

        return result.toString().trim()
    }

    private fun parseSlideXml(inputStream: InputStream): String {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")

        val result = StringBuilder()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "sp" -> processShape(parser, result)  // Text box/shape
                        "graphicFrame" -> processGraphicFrame(parser, result)  // Table
                    }
                }
            }
            parser.next()
        }

        return result.toString()
    }

    private fun processShape(parser: XmlPullParser, result: StringBuilder) {
        val shapeStartDepth = parser.depth
        val textContent = StringBuilder()
        var hasBullet = false
        var bulletLevel = 0
        var isNumbered = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "p" -> {
                            // Start of paragraph - check for bullet/numbering
                            val paragraphInfo = processParagraph(parser, textContent)
                            hasBullet = paragraphInfo.first
                            bulletLevel = paragraphInfo.second
                            isNumbered = paragraphInfo.third
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "sp" && parser.depth == shapeStartDepth) {
                        break
                    }
                }
            }
        }

        val text = textContent.toString().trim()
        if (text.isNotBlank()) {
            result.append(text)
            result.append("\n\n")
        }
    }

    private fun processParagraph(parser: XmlPullParser, result: StringBuilder): Triple<Boolean, Int, Boolean> {
        val paragraphStartDepth = parser.depth
        val paragraphText = StringBuilder()
        var hasBullet = false
        var bulletLevel = 0
        var isNumbered = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "pPr" -> {
                            // Paragraph properties - check for bullets
                            val bulletInfo = extractBulletInfo(parser)
                            hasBullet = bulletInfo.first
                            bulletLevel = bulletInfo.second
                            isNumbered = bulletInfo.third
                        }

                        "r" -> {
                            // Text run
                            extractTextRun(parser, paragraphText)
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "p" && parser.depth == paragraphStartDepth) {
                        break
                    }
                }
            }
        }

        val text = paragraphText.toString().trim()
        if (text.isNotBlank()) {
            if (hasBullet) {
                val indent = "  ".repeat(bulletLevel)
                val marker = if (isNumbered) "1. " else "- "
                result.append("$indent$marker$text\n")
            } else {
                result.append("$text\n")
            }
        }

        return Triple(hasBullet, bulletLevel, isNumbered)
    }

    private fun extractBulletInfo(parser: XmlPullParser): Triple<Boolean, Int, Boolean> {
        val pPrStartDepth = parser.depth
        var hasBullet = false
        var level = 0
        var isNumbered = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "buChar" -> {
                            hasBullet = true
                            isNumbered = false
                        }

                        "buAutoNum" -> {
                            hasBullet = true
                            isNumbered = true
                        }

                        "lvl" -> {
                            parser.getAttributeValue(null, "val")?.let {
                                level = it.toIntOrNull() ?: 0
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "pPr" && parser.depth == pPrStartDepth) {
                        break
                    }
                }
            }
        }

        return Triple(hasBullet, level, isNumbered)
    }

    private fun extractTextRun(parser: XmlPullParser, result: StringBuilder) {
        val runStartDepth = parser.depth

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "t") {
                        parser.next()
                        if (parser.eventType == XmlPullParser.TEXT) {
                            result.append(parser.text ?: "")
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "r" && parser.depth == runStartDepth) {
                        break
                    }
                }
            }
        }
    }

    private fun processGraphicFrame(parser: XmlPullParser, result: StringBuilder) {
        val frameStartDepth = parser.depth

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "tbl") {
                        processTable(parser, result)
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "graphicFrame" && parser.depth == frameStartDepth) {
                        break
                    }
                }
            }
        }
    }

    private fun processTable(parser: XmlPullParser, result: StringBuilder) {
        val tableStartDepth = parser.depth
        val rows = mutableListOf<List<String>>()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "tr") {
                        val cells = extractTableRow(parser)
                        if (cells.isNotEmpty()) {
                            rows.add(cells)
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "tbl" && parser.depth == tableStartDepth) {
                        break
                    }
                }
            }
        }

        // Convert to markdown table
        if (rows.isNotEmpty()) {
            val maxCols = rows.maxOfOrNull { it.size } ?: 0

            for ((index, row) in rows.withIndex()) {
                result.append("| ")
                for (colIndex in 0 until maxCols) {
                    val cellContent = if (colIndex < row.size) row[colIndex] else ""
                    result.append("$cellContent | ")
                }
                result.append("\n")

                // Add separator after first row (header)
                if (index == 0) {
                    result.append("| ")
                    repeat(maxCols) {
                        result.append("--- | ")
                    }
                    result.append("\n")
                }
            }
            result.append("\n")
        }
    }

    private fun extractTableRow(parser: XmlPullParser): List<String> {
        val rowStartDepth = parser.depth
        val cells = mutableListOf<String>()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "tc") {
                        val cellText = extractTableCell(parser)
                        cells.add(cellText)
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "tr" && parser.depth == rowStartDepth) {
                        break
                    }
                }
            }
        }

        return cells
    }

    private fun extractTableCell(parser: XmlPullParser): String {
        val cellStartDepth = parser.depth
        val result = StringBuilder()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "t") {
                        parser.next()
                        if (parser.eventType == XmlPullParser.TEXT) {
                            if (result.isNotEmpty()) {
                                result.append(" ")
                            }
                            result.append(parser.text ?: "")
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "tc" && parser.depth == cellStartDepth) {
                        break
                    }
                }
            }
        }

        return result.toString().trim()
    }

    private fun parseNotesXml(inputStream: InputStream): String {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")

        val result = StringBuilder()

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "sp" -> {
                            // Check if this is a notes text shape (not the slide preview)
                            val shapeStartDepth = parser.depth
                            if (isNotesTextShape(parser)) {
                                extractShapeText(parser, result, shapeStartDepth)
                            }
                        }
                    }
                }
            }
            parser.next()
        }

        return result.toString().trim()
    }

    private fun isNotesTextShape(parser: XmlPullParser): Boolean {
        // Notes text typically has ph type="body"
        val currentDepth = parser.depth

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "ph") {
                        val type = parser.getAttributeValue(null, "type")
                        return type == "body"
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.depth <= currentDepth) {
                        return false
                    }
                }
            }
        }
        return false
    }

    private fun extractShapeText(parser: XmlPullParser, result: StringBuilder, shapeStartDepth: Int) {
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "t") {
                        parser.next()
                        if (parser.eventType == XmlPullParser.TEXT) {
                            result.append(parser.text ?: "")
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "sp" && parser.depth == shapeStartDepth) {
                        break
                    }
                    if (parser.name == "p") {
                        result.append("\n")
                    }
                }
            }
        }
    }
}
