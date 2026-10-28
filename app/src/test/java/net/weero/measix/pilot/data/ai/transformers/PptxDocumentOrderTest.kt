package net.weero.measix.pilot.data.ai.transformers

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import me.rerere.document.PptxParser
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PptxDocumentOrderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `presentation relationships define order and notes regardless of archive filenames`() {
        val file = temporary.newFile("ordered.pptx")
        zip(file, linkedMapOf(
            "_rels/.rels" to relationships("main", "officeDocument", "ppt/presentation.xml"),
            "ppt/slides/slide2.xml" to slide("SECOND"),
            "ppt/slides/slide7.xml" to slide("FIRST"),
            "ppt/slides/slide99.xml" to slide("UNREFERENCED"),
            "ppt/presentation.xml" to """<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId id="257" r:id="last"/><p:sldId id="256" r:id="first"/></p:sldIdLst></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to relationships("first", "slide", "slides/slide2.xml", "last", "slide", "slides/slide7.xml"),
            "ppt/slides/_rels/slide7.xml.rels" to relationships("note", "notesSlide", "../notesSlides/notesSlide9.xml"),
            "ppt/notesSlides/notesSlide9.xml" to notes("FIRST-NOTE"),
            "ppt/notesSlides/notesSlide1.xml" to notes("UNRELATED-NOTE"),
        ))
        val output = PptxParser.parse(file)
        assertTrue(output, output.indexOf("FIRST") < output.indexOf("SECOND"))
        assertTrue(output, output.contains("FIRST-NOTE"))
        assertTrue(output, !output.contains("UNRELATED-NOTE"))
        assertTrue(output, !output.contains("UNREFERENCED"))
    }

    @Test
    fun `speaker notes stop at the body shape before page number and footer shapes`() {
        val file = temporary.newFile("notes-shapes.pptx")
        val notesXml = notes("SPEAKER-NOTE").replace("</p:spTree>", """
            <p:sp><p:nvSpPr><p:nvPr><p:ph type="sldNum"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:fld id="{A9AF8C77-DFE7-4900-8127-BDE71774D576}" type="slidenum"><a:t>PAGE-NUMBER</a:t></a:fld></a:p></p:txBody></p:sp>
            <p:sp><p:nvSpPr><p:nvPr><p:ph type="ftr"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>FOOTER-TEXT</a:t></a:r></a:p></p:txBody></p:sp>
            </p:spTree>
        """.trimIndent())
        zip(file, linkedMapOf(
            "_rels/.rels" to relationships("main", "officeDocument", "ppt/presentation.xml"),
            "ppt/presentation.xml" to """<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId id="256" r:id="slide"/></p:sldIdLst></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to relationships("slide", "slide", "slides/slide1.xml"),
            "ppt/slides/slide1.xml" to slide("SLIDE-CONTENT"),
            "ppt/slides/_rels/slide1.xml.rels" to relationships("notes", "notesSlide", "../notesSlides/notes1.xml"),
            "ppt/notesSlides/notes1.xml" to notesXml,
        ))
        val output = PptxParser.parse(file)
        assertTrue(output, output.contains("SPEAKER-NOTE"))
        assertTrue(output, !output.contains("PAGE-NUMBER"))
        assertTrue(output, !output.contains("FOOTER-TEXT"))
    }

    @Test
    fun `missing and external slide relationships fail instead of guessing files`() {
        val presentation = """<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId id="256" r:id="slide"/></p:sldIdLst></p:presentation>"""
        for (external in listOf(false, true)) {
            val file = temporary.newFile("invalid-$external.pptx")
            val entries = linkedMapOf(
                "_rels/.rels" to relationships("main", "officeDocument", "ppt/presentation.xml"),
                "ppt/presentation.xml" to presentation,
                "ppt/slides/slide1.xml" to slide("Must not guess this file"),
            )
            if (external) entries["ppt/_rels/presentation.xml.rels"] =
                relationships("slide", "slide", "https://remote.example/slide.xml")
                    .replace(" Target=", " TargetMode=\"External\" Target=")
            zip(file, entries)
            val error = org.junit.Assert.assertThrows(java.io.IOException::class.java) { PptxParser.parse(file) }
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(if (external) "external" else "missing"))
        }
    }

    @Test
    fun `encoded part paths preserve spaces unicode and literal percent across relative relationships`() {
        val file = temporary.newFile("encoded.pptx")
        zip(file, linkedMapOf(
            "_rels/.rels" to relationships("main", "officeDocument", "ppt%20deck/presentation%25.xml"),
            "ppt deck/presentation%.xml" to """<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId id="256" r:id="slide"/></p:sldIdLst></p:presentation>""",
            "ppt deck/_rels/presentation%.xml.rels" to relationships("slide", "slide", "slides/%E4%B8%80%20%25.xml"),
            "ppt deck/slides/一 %.xml" to slide("ENCODED-SLIDE"),
            "ppt deck/slides/_rels/一 %.xml.rels" to relationships("note", "notesSlide", "../notes/note%20%25.xml"),
            "ppt deck/notes/note %.xml" to notes("ENCODED-NOTE"),
        ))
        val output = PptxParser.parse(file)
        assertTrue(output, output.contains("ENCODED-SLIDE"))
        assertTrue(output, output.contains("ENCODED-NOTE"))
    }

    private fun slide(text: String) = """<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:sp><p:txBody><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>"""
    private fun notes(text: String) = """<p:notes xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:sp><p:nvSpPr><p:nvPr><p:ph type="body"/></p:nvPr></p:nvSpPr><p:txBody><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:notes>"""
    private fun relationships(vararg values: String): String =
        """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            values.toList().chunked(3).joinToString("") { (id, type, target) ->
                """<Relationship Id="$id" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/$type" Target="$target"/>"""
            } + "</Relationships>"
    private fun zip(file: File, entries: Map<String, String>) = ZipOutputStream(file.outputStream()).use { zip ->
        entries.forEach { (path, content) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray()); zip.closeEntry()
        }
    }
}
