package me.rerere.ai.provider.providers

import kotlinx.serialization.json.*
import me.rerere.ai.registry.ModelRegistry

/** Validate the final body after custom fields, before any transport is opened. */
internal fun validateClaudeRequestParameters(body: JsonObject): JsonObject {
    val model = (body["model"] as? JsonPrimitive)?.content ?: return body
    if (!ModelRegistry.CLAUDE_5_5.match(model)) return body
    fun reject(code: String, detail: String): Nothing = throw IllegalArgumentException("$code: $detail")
    fun numericDefault(key: String, accepted: (Double) -> Boolean) {
        if (key !in body) return
        val value = (body[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
        if (value == null || !value.isFinite() || !accepted(value)) {
            reject("unsupported_sampling_parameter", "$model does not support the supplied $key; remove it or use its default")
        }
    }
    numericDefault("temperature") { it == 1.0 }
    numericDefault("top_p") { it in 0.99..1.0 }
    if ("top_k" in body) reject("unsupported_sampling_parameter", "$model does not support top_k; remove it")
    val effort = if ("output_config" in body) {
        val output = body["output_config"] as? JsonObject ?: reject("unsupported_reasoning_effort", "output_config must be an object")
        if ("effort" !in output) null else {
            val value = (output["effort"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (value !in setOf("low", "medium", "high", "xhigh", "max")) reject("unsupported_reasoning_effort", "Unsupported effort value")
            value
        }
    } else null
    if ("thinking" in body) {
        val thinking = body["thinking"] as? JsonObject
            ?: reject("unsupported_reasoning_mode", "thinking must be an object")
        val mode = (thinking["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val supported = mode == "adaptive" || mode == "between_tools" && ModelRegistry.CLAUDE_SONNET_5_5.match(model)
        if (!supported || "budget_tokens" in thinking) {
            reject("unsupported_reasoning_mode", "$model requires adaptive thinking; Sonnet also supports between_tools")
        }
        if (mode == "between_tools" && effort in setOf("xhigh", "max")) {
            reject("unsupported_reasoning_effort", "between_tools supports high effort or below")
        }
    }
    if ("tool_choice" in body) {
        val choice = ((body["tool_choice"] as? JsonObject)?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (choice !in setOf("auto", "none")) reject("unsupported_tool_choice", "$model supports only auto or none")
    }
    return JsonObject(body - "temperature" - "top_p")
}
