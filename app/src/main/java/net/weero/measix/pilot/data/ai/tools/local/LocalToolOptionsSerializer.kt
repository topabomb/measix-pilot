package net.weero.measix.pilot.data.ai.tools.local

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

/** Retired local options are removed only at their typed compatibility boundary. */
object LocalToolOptionsSerializer : JsonTransformingSerializer<List<LocalToolOption>>(ListSerializer(LocalToolOption.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement = if (element is JsonArray) {
        JsonArray(element.filterNot { (it as? JsonObject)?.get("type") == JsonPrimitive("javascript_engine") })
    } else element
}
