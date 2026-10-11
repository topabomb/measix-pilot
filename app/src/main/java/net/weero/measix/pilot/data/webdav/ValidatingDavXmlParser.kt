package net.weero.measix.pilot.data.webdav

import java.io.IOException
import okhttp3.HttpUrl
import org.xmlpull.v1.XmlPullParser

/** Validate wire identities and required statuses before the DAV library normalizes/defaults them. */
internal class ValidatingDavXmlParser(
    private val delegate: XmlPullParser,
    private val paths: WebDavPaths,
    private val requestUrl: HttpUrl,
) : XmlPullParser by delegate {
    private data class Element(val namespace: String?, val name: String?, var statusSeen: Boolean = false)
    private val elements = mutableListOf<Element>()
    private var capturedText: StringBuilder? = null
    private var capturedDepth = -1

    override fun next(): Int {
        if (delegate.eventType == XmlPullParser.END_DOCUMENT) throw IOException("webdav_xml_incomplete")
        val event = delegate.next()
        if (delegate.depth > 64) throw IOException("webdav_xml_depth_exceeded")
        when (event) {
            XmlPullParser.START_TAG -> {
                val parent = elements.lastOrNull()
                elements += Element(delegate.namespace, delegate.name)
                if (delegate.namespace == "DAV:" && parent?.namespace == "DAV:" &&
                    ((delegate.name == "href" && parent.name == "response") ||
                        (delegate.name == "status" && parent.name in setOf("response", "propstat")))) {
                    capturedText = StringBuilder()
                    capturedDepth = delegate.depth
                }
            }
            XmlPullParser.TEXT -> capturedText?.append(delegate.text)
            XmlPullParser.END_TAG -> {
                val element = elements.removeAt(elements.lastIndex)
                if (delegate.depth == capturedDepth) {
                    val text = capturedText.toString().trim()
                    if (element.name == "href") {
                        if (text.isEmpty()) throw IOException("webdav_href_missing")
                        paths.pathFromHref(text, requestUrl)
                    }
                    else {
                        if (!STATUS.matches(text)) throw IOException("webdav_multistatus_status_invalid")
                        elements.lastOrNull()?.statusSeen = true
                    }
                    capturedText = null
                    capturedDepth = -1
                }
                if (element.namespace == "DAV:" && element.name == "propstat" && !element.statusSeen) {
                    throw IOException("webdav_multistatus_status_missing")
                }
            }
        }
        return event
    }

    // Delegating these convenience methods would bypass the wire checks in next().
    override fun nextText(): String {
        check(eventType == XmlPullParser.START_TAG)
        val text = StringBuilder()
        while (true) {
            when (next()) {
                XmlPullParser.TEXT -> text.append(delegate.text)
                XmlPullParser.END_TAG -> return text.toString()
                else -> throw IOException("webdav_xml_text_expected")
            }
        }
    }

    override fun nextTag(): Int {
        var event = next()
        if (event == XmlPullParser.TEXT && isWhitespace) event = next()
        if (event != XmlPullParser.START_TAG && event != XmlPullParser.END_TAG) {
            throw IOException("webdav_xml_tag_expected")
        }
        return event
    }

    private companion object {
        val STATUS = Regex("HTTP/\\d+\\.\\d+ [1-5]\\d{2}(?: .*)?")
    }
}
