package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.ResponseFunctionResult
import me.rerere.ai.ui.UIMessagePart

/** Correlate only within this response and the actual sent function definitions. */
internal fun responseFunctionResults(
    items: List<JsonObject>,
    sentFunctions: Set<String>,
    endpoint: String?,
    profile: ResponseEndpointProfile,
): Map<String, ResponseFunctionResult> {
    val results = items.filter { it.string("type") == "function_call_output" }
    if (results.isEmpty()) return emptyMap()
    val source = requireNotNull(endpoint?.takeIf { it.isNotBlank() }) { "responses_result_source_missing" }
    val calls = items.filter { it.string("type") == "function_call" }.groupBy { it.string("call_id") }
    val resolved = linkedMapOf<String, ResponseFunctionResult>()
    results.forEach { result ->
        val id = requireNotNull(result.string("call_id")?.takeIf { it.isNotBlank() }) { "responses_result_call_id_missing" }
        check(id !in resolved) { "responses_result_duplicate: multiple results for one call_id" }
        val call = calls[id]?.singleOrNull() ?: error("responses_result_ambiguous: result needs exactly one response call")
        check(call.string("name") in sentFunctions) { "responses_result_unknown_tool: call was not in the sent function set" }
        val output = result["output"] ?: error("responses_result_output_missing")
        check(output is JsonPrimitive && output.isString || output is kotlinx.serialization.json.JsonArray || output is JsonObject) {
            "responses_result_output_invalid: expected string, array or object"
        }
        resolved[id] = ResponseFunctionResult(id, output, source, profile.sourceProfile)
    }
    return resolved
}

/** Keep structured/empty values distinct in metadata; display has no resource ownership. */
internal fun JsonElement.resultDisplayParts(): List<UIMessagePart> = listOf(UIMessagePart.Text(
    if (this is JsonPrimitive && isString) content else toString(),
))

private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
