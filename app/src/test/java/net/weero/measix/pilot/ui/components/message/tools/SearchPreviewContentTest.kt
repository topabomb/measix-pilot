package net.weero.measix.pilot.ui.components.message.tools

import kotlinx.serialization.json.Json
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SearchPreviewContentTest {
    @Test
    fun `malformed or archived search output uses generic details while valid empty result remains valid`() {
        for (raw in listOf(
            "null", "\"truncated output\"", "{}", "{\"items\":\"broken\"}",
            "{\"items\":[42]}", "{\"items\":[{\"url\":\"https://example.com\"}]}",
            "{\"items\":[],\"images\":[{}]}", "{\"items\":[],\"answer\":{}}",
        )) assertNull(raw, searchPreviewContent(Json.parseToJsonElement(raw)))
        assertNotNull(searchPreviewContent(Json.parseToJsonElement("""{"items":[],"answer":null,"images":null}""")))
        assertNotNull(searchPreviewContent(Json.parseToJsonElement("""{
            "items":[{"url":"https://example.com","title":"Title","text":"Body"}],
            "answer":"Answer","images":["https://example.com/image.png"]
        }""")))
    }
}
