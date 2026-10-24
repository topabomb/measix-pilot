package me.rerere.common.util

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonDecodingException
import kotlinx.serialization.json.JsonException
import java.util.Collections
import java.util.IdentityHashMap

/** A per-error text projection. Originals, causes, suppressed errors and stack frames stay intact. */
class ThrowableDiagnosticText(error: Throwable) {
    private val replacements: List<Pair<String, String>>

    init {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = java.util.ArrayDeque<Throwable>()
        val errors = mutableListOf<Throwable>()
        pending.add(error)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (!seen.add(current)) continue
            errors += current
            current.cause?.let(pending::add)
            current.suppressed.forEach(pending::add)
        }
        val jsonCauses = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        errors.filterIsInstance<JsonException>().forEach { json ->
            json.cause?.let(pending::add)
            json.suppressed.forEach(pending::add)
        }
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (!jsonCauses.add(current)) continue
            current.cause?.let(pending::add)
            current.suppressed.forEach(pending::add)
        }
        val changes = errors.mapNotNull { current ->
            val original = current.message
            val safe = jsonMessage(current, current in jsonCauses)
            if (!original.isNullOrEmpty() && safe != null && original != safe) original to safe else null
        }
        // Wrappers often copy cause.toString() into their own message. Replace the largest
        // original first so an inner replacement cannot leave the outer input excerpt behind.
        replacements = changes.distinctBy { it.first }.sortedByDescending { it.first.length }
    }

    fun message(error: Throwable): String? = error.message?.let(::sanitize)

    /** Also accepts stackTraceToString(): exact frame, cause and suppressed formatting is retained. */
    fun sanitize(text: String): String = replacements.fold(text) { result, (original, safe) ->
        result.replace(original, safe)
    }
}

private val multiline = setOf(RegexOption.DOT_MATCHES_ALL)
private val dynamicValuePatterns = listOf(
    Regex("^(Expected valid boolean literal prefix, but had )'.*'$", multiline),
    Regex("^(Expected single char, but got )'.*'$", multiline),
    Regex("^(Failed to parse (?:byte|short|int) for input )'.*'$", multiline),
    Regex("^(Failed to parse type '(?:float|double)' for input )'.*'$", multiline),
    Regex("^(Encountered an unknown key )'.*'$", multiline),
    Regex("^(Expected string value for a non-null key )'.*'(, got null literal instead)$", multiline),
    Regex("^(String literal for value of key )'.*'( should be quoted)$", multiline),
    Regex("^(Failed to parse literal )'.*'( as (?:a|an) [A-Za-z]+ value)$", multiline),
    Regex("^(Expected input to contain a single valid number, but got )'.*'( after it)$", multiline),
)
private val polymorphicValue = Regex("^(Serializer for subclass )'(.*)'( is not found in the polymorphic scope of '[^']*')$", multiline)
private val nonFiniteKey = Regex("^(Unexpected special floating-point value (?:NaN|-?Infinity) with key ).*(\\. By default, non-finite floating point values are prohibited because they do not conform JSON specification\\.)$", multiline)
private val longConversion = Regex("^(Can't convert ).*( to Long)$", multiline)
private val numberFormatValue = Regex("^For input string: \".*\"( under radix \\d+)?$", multiline)
private val enumValue = Regex("^(.*? does not contain element with name )'.*'((?: at path .*?)?)$", multiline)
private val mapKeyPath = Regex("\\['.*'\\]", multiline)
private val arrayOnlyPath = Regex("\\$(?:\\[\\d+\\])*")

private fun safePath(path: String?, tree: Boolean): String? = path?.let {
    // 1.12 tree decoding reports both DTO fields and dynamic map keys as $.name, even with
    // debug info disabled. It supplies no descriptor. Do not expose that ambiguous segment.
    if (tree && it != "$" && it != "$.primitive" && !arrayOnlyPath.matches(it)) {
        "<redacted ambiguous tree path>"
    } else mapKeyPath.replace(it, "[<redacted>]")
}

private fun redactJsonValue(reason: String): String {
    for (pattern in listOf(nonFiniteKey, longConversion)) {
        val match = pattern.matchEntire(reason) ?: continue
        return match.groupValues[1] + "<redacted>" + match.groupValues[2]
    }
    for (pattern in dynamicValuePatterns) {
        val match = pattern.matchEntire(reason) ?: continue
        return match.groupValues[1] + "'<redacted>'" + match.groupValues.getOrElse(2) { "" }
    }
    val symbol = Regex("^(Unexpected symbol |Invalid escaped char |Invalid toHexChar char )'.'(.*)$", multiline)
        .matchEntire(reason)
    if (symbol != null) return symbol.groupValues[1] + "'<redacted>'" + symbol.groupValues[2]
    return reason
}

private fun jsonMessage(error: Throwable, jsonCause: Boolean): String? {
    if (error is JsonDecodingException) return buildString {
        val polymorphic = polymorphicValue.matchEntire(error.shortMessage)
        val reason = if (polymorphic != null) polymorphic.groupValues[1] + "'<redacted>'" + polymorphic.groupValues[3]
            else redactJsonValue(error.shortMessage)
        if (error.offset >= 0) append("Unexpected JSON token at offset ${error.offset}: ")
        append(reason)
        safePath(error.path, tree = error.offset < 0)?.let { append(" at path: $it") }
        error.hint?.let { hint ->
            val value = polymorphic?.groupValues?.get(2)?.takeIf(String::isNotEmpty)
            append("\n" + if (value == null) hint else hint.replace(value, "<redacted>"))
        }
    }
    if (error is JsonException) return redactJsonValue(error.shortMessage) + (error.hint?.let { "\n$it" } ?: "")
    if (error is SerializationException) {
        val match = enumValue.matchEntire(error.message.orEmpty())
        if (match != null) return match.groupValues[1] + "'<redacted>'" +
            mapKeyPath.replace(match.groupValues[2], "[<redacted>]")
    }
    if (jsonCause && error is NumberFormatException) {
        val match = numberFormatValue.matchEntire(error.message.orEmpty())
        if (match != null) return "For input string: \"<redacted>\"" + match.groupValues[1]
    }
    return error.message
}
