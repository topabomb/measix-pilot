package net.weero.measix.pilot.data.ai.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.weero.measix.pilot.utils.StrictJsonValue

/** Only a complete, unambiguous JSON text can replace the structured result's model projection. */
internal fun JsonElement.isRepresentedByMcpText(texts: Sequence<String>): Boolean = texts.any { text ->
    val parsed = try {
        StrictJsonValue.parse(text, MAX_MCP_FRAME_BYTES)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IllegalStateException) {
        return@any false
    } catch (_: IllegalArgumentException) {
        return@any false
    }
    equivalentJsonValue(parsed, this)
}

private fun equivalentJsonValue(left: JsonElement, right: JsonElement): Boolean = when {
    left is JsonObject && right is JsonObject ->
        left.keys == right.keys && left.all { (key, value) -> equivalentJsonValue(value, right.getValue(key)) }

    left is JsonArray && right is JsonArray ->
        left.size == right.size && left.indices.all { equivalentJsonValue(left[it], right[it]) }

    left is JsonPrimitive && right is JsonPrimitive -> {
        if (left.isString || right.isString) {
            left == right
        } else {
            // Decimal comparison preserves large integer precision and treats 1, 1.0 and 1e0 as one JSON number.
            val leftNumber = left.content.toBigDecimalOrNull()
            val rightNumber = right.content.toBigDecimalOrNull()
            if (leftNumber != null && rightNumber != null) leftNumber.compareTo(rightNumber) == 0 else left == right
        }
    }

    else -> false
}
