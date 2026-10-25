package net.weero.measix.pilot.ui.components.webview

import org.junit.Assert.*
import org.junit.Test
import org.jsoup.Jsoup

class StaticFileHtmlTest {
    @Test fun `static preview keeps styling images and anchors but cannot execute page actions`() {
        val html = """<base href="https://elsewhere/"><meta http-equiv="refresh" content="0;url=https://elsewhere/">
            <link rel="icon" href="missing.ico"><link rel="stylesheet" href="theme.css">
            <script src="app.js"></script><iframe src="remote"></iframe><form action="post"><input></form>
            <img src="pic.png" onerror="alert(1)"><a href="#target">go</a><p id="target">Hello</p>"""
        val doc = Jsoup.parse(prepareStaticFileHtml(html))
        assertTrue(doc.select("script[src],base,iframe,form,[onerror],link[rel=icon]").isEmpty())
        assertEquals("theme.css", doc.selectFirst("link[rel=stylesheet]")!!.attr("href"))
        assertEquals("pic.png", doc.selectFirst("img")!!.attr("data-preview-src"))
        assertEquals("#target", doc.selectFirst("a")!!.attr("href"))
        assertTrue(doc.text().contains("Hello"))
        assertEquals(1, doc.select("script").size)
        val nonce = doc.selectFirst("script")!!.attr("nonce")
        assertTrue(nonce.isNotEmpty())
        assertTrue(doc.selectFirst("meta[http-equiv=Content-Security-Policy]")!!.attr("content").contains("script-src 'nonce-$nonce'"))
        assertFalse(doc.selectFirst("img")!!.hasAttr("src"))
        assertEquals(1, doc.select("meta[name=viewport]").size)
    }
}
