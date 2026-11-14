package net.weero.measix.pilot.utils

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StrictJsonValueTest {
    @Test fun `unescaped control characters are rejected in both object keys and string values`() {
        for (code in 0..0x1f) {
            val control = code.toChar()
            for (text in listOf("{\"a${control}b\":1}", "{\"value\":\"a${control}b\"}")) {
                assertThrows("Unescaped U+${code.toString(16).padStart(4, '0')}", IllegalStateException::class.java) {
                    StrictJsonValue.parse(text, 1024)
                }
            }
        }
    }

    @Test fun `escaped control characters preserve their decoded keys and values`() {
        for (code in 0..0x1f) {
            val value = "a${code.toChar()}b"
            val expected = buildJsonObject { put(value, value) }
            assertEquals(expected, StrictJsonValue.parse(expected.toString(), 1024))
        }
    }

    @Test fun `JSON whitespace outside strings and non-control string characters remain valid`() {
        val value = " a${0x7f.toChar()}b "
        assertEquals(buildJsonObject { put("value", value) },
            StrictJsonValue.parse("{\r\n\t\"value\":\"$value\" }\n", 1024))
    }
}
