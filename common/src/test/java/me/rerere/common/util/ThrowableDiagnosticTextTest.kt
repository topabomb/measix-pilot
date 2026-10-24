package me.rerere.common.util

import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecodingException
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.*
import org.junit.Test

class ThrowableDiagnosticTextTest {
    @Serializable private enum class Choice { FIRST, SECOND }
    private val secret = "PRIVATE_BODY_SENTINEL' as a boolean value\nfor input 'INJECTED'"
    private val key = "PRIVATE_MAP_KEY']\n$.forged['"

    @Test fun `real streaming and tree primitive errors redact values with both debug settings`() {
        for (debug in listOf(false, true)) {
            val json = Json { exceptionsWithDebugInfo = debug }
            val encoded = json.encodeToString(secret)
            val operations: List<() -> Unit> = listOf(
                { json.decodeFromString<Boolean>(encoded) },
                { json.decodeFromString<Double>(encoded) },
                { json.decodeFromString<Char>(encoded) },
                { json.decodeFromString<Choice>(encoded) },
                { json.decodeFromJsonElement<Boolean>(json.parseToJsonElement(encoded)) },
                { json.decodeFromJsonElement<Int>(json.parseToJsonElement(encoded)) },
                { json.decodeFromJsonElement<Choice>(json.parseToJsonElement(encoded)) },
            )
            for (operation in operations) {
                val failure = requireNotNull(runCatching(operation).exceptionOrNull())
                val diagnostic = ThrowableDiagnosticText(failure).sanitize(failure.stackTraceToString())
                assertFalse(diagnostic, diagnostic.contains("PRIVATE_BODY_SENTINEL"))
                assertFalse(diagnostic, diagnostic.contains("INJECTED"))
                assertTrue(diagnostic.contains("<redacted>"))
                assertTrue(diagnostic.contains(failure.javaClass.name))
                assertTrue(diagnostic.contains(failure.stackTrace.first().toString()))
                assertFalse(diagnostic.contains("JSON input:"))
            }
        }
    }

    @Test fun `polymorphic hints and nested wrappers cannot repeat private discriminator`() {
        for (debug in listOf(false, true)) {
            val json = Json { exceptionsWithDebugInfo = debug }
            val encoded = json.encodeToString(mapOf("type" to secret))
            val failure = requireNotNull(runCatching {
                json.decodeFromString(PolymorphicSerializer(Any::class), encoded)
            }.exceptionOrNull())
            val wrapper = IllegalStateException("Import failed: $failure", failure)
            wrapper.addSuppressed(failure)
            val before = wrapper.stackTraceToString()
            val diagnostic = ThrowableDiagnosticText(wrapper).sanitize(before)
            assertFalse(diagnostic, diagnostic.contains("PRIVATE_BODY_SENTINEL"))
            assertFalse(diagnostic, diagnostic.contains("INJECTED"))
            assertTrue(diagnostic.contains("Serializer for subclass '<redacted>'"))
            assertTrue(diagnostic.contains("polymorphic scope"))
            assertTrue(diagnostic.contains("SerializersModule"))
            assertTrue(diagnostic.contains("Import failed"))
            assertTrue(diagnostic.contains("Suppressed:"))
            assertEquals(before, wrapper.stackTraceToString())
            assertSame(failure, wrapper.cause)
        }
    }

    @Test fun `dynamic map paths are private in streaming and ambiguous tree errors`() {
        for (debug in listOf(false, true)) for (tree in listOf(false, true)) {
            val json = Json { exceptionsWithDebugInfo = debug }
            val encoded = json.encodeToString(mapOf(key to secret))
            val failure = requireNotNull(runCatching {
                if (tree) json.decodeFromJsonElement<Map<String, Boolean>>(json.parseToJsonElement(encoded))
                else json.decodeFromString<Map<String, Boolean>>(encoded)
            }.exceptionOrNull())
            val diagnostic = ThrowableDiagnosticText(failure).message(failure).orEmpty()
            assertFalse(diagnostic, diagnostic.contains("PRIVATE_MAP_KEY"))
            assertFalse(diagnostic, diagnostic.contains("forged"))
            assertFalse(diagnostic, diagnostic.contains("PRIVATE_BODY_SENTINEL"))
            assertTrue(diagnostic.contains("boolean"))
            if (tree) assertTrue(diagnostic.contains("redacted ambiguous tree path"))
            else assertTrue(diagnostic.contains("offset"))
        }
    }

    @Test fun `invalid JSON omits input but preserves parser reason offset and complete frames`() {
        val failure = requireNotNull(runCatching {
            Json.parseToJsonElement("{\"content\":\"PRIVATE_BODY_SENTINEL\",\"value\":}")
        }.exceptionOrNull()) as JsonDecodingException
        val diagnostic = ThrowableDiagnosticText(failure).sanitize(failure.stackTraceToString())
        assertFalse(diagnostic.contains("PRIVATE_BODY_SENTINEL"))
        assertTrue(diagnostic.contains(failure.shortMessage))
        assertTrue(diagnostic.contains("offset ${failure.offset}"))
        assertTrue(diagnostic.contains("at path: $"))
        failure.stackTrace.forEach { assertTrue(diagnostic.contains(it.toString())) }
    }

    @Test fun `nonfinite tree values hide dynamic key and keep numeric reason and hint`() {
        for (debug in listOf(false, true)) {
            val json = Json { exceptionsWithDebugInfo = debug }
            val input = "{${json.encodeToString(key)}:NaN}"
            val decoding = requireNotNull(runCatching {
                json.decodeFromJsonElement<Map<String, Double>>(json.parseToJsonElement(input))
            }.exceptionOrNull())
            val encoding = requireNotNull(runCatching {
                json.encodeToJsonElement(mapOf(key to Double.NaN))
            }.exceptionOrNull())
            for (failure in listOf(decoding, encoding)) {
                val diagnostic = ThrowableDiagnosticText(failure).message(failure).orEmpty()
                assertFalse(diagnostic, diagnostic.contains("PRIVATE_MAP_KEY"))
                assertFalse(diagnostic, diagnostic.contains("forged"))
                assertTrue(diagnostic.contains("Unexpected special floating-point value NaN"))
                assertTrue(diagnostic.contains("allowSpecialFloatingPointValues"))
            }
        }
    }

    @Test fun `number format payload is private only within a JSON decoding cause chain`() {
        val ordinary = NumberFormatException("For input string: \"42x\"")
        assertEquals(ordinary.message, ThrowableDiagnosticText(ordinary).message(ordinary))
        assertEquals(ordinary.stackTraceToString(), ThrowableDiagnosticText(ordinary).sanitize(ordinary.stackTraceToString()))
        val serializer = object : kotlinx.serialization.KSerializer<Int> {
            override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
                "NumericText", kotlinx.serialization.descriptors.PrimitiveKind.STRING)
            override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Int) = encoder.encodeString(value.toString())
            override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Int = decoder.decodeString().toInt()
        }
        val failure = requireNotNull(runCatching {
            Json.decodeFromString(serializer, "\"PRIVATE_BODY_SENTINEL\"")
        }.exceptionOrNull())
        assertTrue(failure is JsonDecodingException)
        assertTrue(failure.cause is NumberFormatException)
        val diagnostic = ThrowableDiagnosticText(failure).sanitize(failure.stackTraceToString())
        assertFalse(diagnostic.contains("PRIVATE_BODY_SENTINEL"))
        assertTrue(diagnostic.contains("NumberFormatException"))
        assertTrue(diagnostic.contains("For input string: \"<redacted>\""))
    }

    @Test fun `ordinary diagnostics retain exact message causes suppressed errors and stack formatting`() {
        val cause = java.io.IOException("/workspace/report.json disk full at byte 42")
        val error = IllegalStateException("Import failed", cause)
        error.addSuppressed(java.io.IOException("cleanup rename failed"))
        val diagnostic = ThrowableDiagnosticText(error)
        assertEquals(error.message, diagnostic.message(error))
        assertEquals(error.stackTraceToString(), diagnostic.sanitize(error.stackTraceToString()))
    }
}
