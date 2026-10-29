package me.rerere.document

import com.artifex.mupdf.fitz.Document
import java.io.File

object PdfParser {
    fun parserPdf(file: File): String {
        val document = Document.openDocument(file.absolutePath)
        try {
            require(document.isPDF) { "Document is not a PDF" }
            return buildString {
                for (i in 0 until document.countPages()) {
                    val page = document.loadPage(i)
                    try {
                        val text = page.toStructuredText()
                        try {
                            append("---")
                            append("Page ${i + 1}:\n")
                            append(text.asText())
                            appendLine()
                        } finally {
                            text.destroy()
                        }
                    } finally {
                        page.destroy()
                    }
                }
            }
        } finally {
            document.destroy()
        }
    }
}
