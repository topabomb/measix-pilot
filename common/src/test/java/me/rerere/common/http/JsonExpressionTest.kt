package me.rerere.common.http

import java.util.Locale
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonExpressionTest {
    @Test
    fun `string literals require an unescaped closing quote`() {
        for (input in listOf("\"", "\"balance\\\"")) {
            assertFalse(input, isJsonExprValid(input))
            org.junit.Assert.assertThrows(RuntimeException::class.java) {
                evaluateJsonExpr(input, buildJsonObject {})
            }
        }
        val escaped = "\"balance\\\"\""
        assertTrue(isJsonExprValid(escaped))
        assertEquals("balance\"", evaluateJsonExpr(escaped, buildJsonObject {}))
    }

    @Test
    fun `JSON numeric arithmetic does not round intermediate balances`() {
        val balance = buildJsonObject { put("credits", 0.004) }
        assertEquals("4", balance.getByKey("credits * 1000"))
    }

    @Test
    fun `JSON numeric balances are independent of display locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val balance = buildJsonObject { put("credits", 1.25) }
            assertEquals("1.25", balance.getByKey("credits"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
