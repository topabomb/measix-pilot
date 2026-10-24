package me.rerere.ai.provider.images

import kotlinx.serialization.json.*

/** Model constraints for Images JSON/form requests, not Responses tool defaults. */
internal fun validateOpenAIImageRequest(modelId: String, body: JsonObject) {
    val enhanced = modelId in setOf(
        "gpt-image-2.5-sunburst", "gpt-image-2.5-sunburst-2026-09-08",
        "gpt-image-2.5-flare", "gpt-image-2.5-flare-2026-09-08",
    )
    if (!enhanced && modelId !in setOf("gpt-image-2", "gpt-image-2-2026-04-21")) return
    fun invalid(field: String, detail: String): Nothing =
        throw IllegalArgumentException("openai_image_parameter_invalid: model=$modelId field=$field $detail")
    fun string(field: String): String? {
        val value = body[field]?.takeUnless { it == JsonNull } ?: return null
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: invalid(field, "must be a string")
    }
    fun option(field: String, allowed: Set<String>, default: String): String {
        val value = string(field) ?: default
        if (value !in allowed) invalid(field, "allowed=${allowed.joinToString()}")
        return value
    }
    option("quality", if (enhanced) setOf("auto", "low", "medium", "high", "xhigh", "max")
        else setOf("auto", "low", "medium", "high"), "auto")
    val background = option("background", setOf("auto", "opaque", "transparent"), "auto")
    val format = option("output_format", setOf("png", "webp", "jpeg"), "png")
    if (background == "transparent" && format == "jpeg") invalid("output_format", "transparent requires png or webp")
    body["output_compression"]?.takeUnless { it == JsonNull }?.let {
        val value = (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.intOrNull
        if (value == null || value !in 0..100 || format !in setOf("webp", "jpeg")) {
            invalid("output_compression", "requires integer 0..100 and webp or jpeg")
        }
    }
    body["n"]?.takeUnless { it == JsonNull }?.let {
        val value = (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.intOrNull
        if (value == null || value !in 1..10) invalid("n", "requires integer 1..10")
    }
    string("size")?.takeUnless { it == "auto" }?.let { size ->
        val match = Regex("^([1-9][0-9]*)x([1-9][0-9]*)$").matchEntire(size)
            ?: invalid("size", "requires WIDTHxHEIGHT or auto")
        val width = match.groupValues[1].toIntOrNull() ?: invalid("size", "width out of range")
        val height = match.groupValues[2].toIntOrNull() ?: invalid("size", "height out of range")
        if (width % 16 != 0 || height % 16 != 0 || width.toLong() > height.toLong() * 3 ||
            height.toLong() > width.toLong() * 3) invalid("size", "requires multiples of 16 and aspect ratio 1:3..3:1")
        // The service owns its evolving pixel/edge limits; do not infer independent edge caps from a resolution example.
    }
    if (body["response_format"]?.let { it != JsonNull } == true) {
        invalid("response_format", "unsupported; GPT image models return base64")
    }
}
