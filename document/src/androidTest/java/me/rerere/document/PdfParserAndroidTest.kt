package me.rerere.document

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PdfParserAndroidTest {
    @Test
    fun extractsPagesAndCanReopenAfterMalformedInput() {
        val file = File.createTempFile("pdf-parser-", ".pdf",
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            file.writeText("not a PDF")
            assertThrows(RuntimeException::class.java) { PdfParser.parserPdf(file) }
            val pdf = PdfDocument()
            try {
                repeat(2) { index ->
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(300, 400, index + 1).create())
                    page.canvas.drawText("Document page ${index + 1}", 20f, 40f, Paint().apply { textSize = 16f })
                    pdf.finishPage(page)
                }
                file.outputStream().use { pdf.writeTo(it) }
            } finally {
                pdf.close()
            }
            repeat(3) {
                val text = PdfParser.parserPdf(file)
                assertTrue(text, text.contains("Page 1:") && text.contains("Document page 1"))
                assertTrue(text, text.contains("Page 2:") && text.contains("Document page 2"))
            }
        } finally {
            file.delete()
        }
    }
}
