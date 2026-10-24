package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.*
import me.rerere.ai.ui.*
import kotlin.uuid.Uuid

/** Response-local display identities. Raw replay remains owned by ResponseStreamState. */
internal class ResponseDisplayState {
    private class Item(val serial: Int, var id: String? = null, var index: Int? = null)
    private val byId = mutableMapOf<String, Item>()
    private val byIndex = mutableMapOf<Int, Item>()
    private var nextItem = 0
    private val responseId = Uuid.random().toString()
    private var nextSlot = 0
    private val stableSlots = mutableMapOf<String, ProviderContentBlockSlot>()
    private var activeTextKey: String? = null
    private var activeTextSlot: ProviderContentBlockSlot? = null

    fun observe(event: JsonObject) {
        when (event.string("type")) {
            "response.output_item.added", "response.output_item.done" -> {
                val type = (event["item"] as? JsonObject)?.string("type")
                if (type != "function_call" && type != "function_call_output") item(event)
            }
            "response.completed" -> ((event["response"] as? JsonObject)?.get("output") as? JsonArray)
                ?.forEachIndexed { index, raw ->
                    val type = (raw as? JsonObject)?.string("type")
                    if (type != "function_call" && type != "function_call_output") item(buildJsonObject {
                        put("output_index", index); put("item", raw)
                    })
                }
        }
    }

    fun decorate(event: JsonObject, choice: UIMessageChoice): UIMessageChoice {
        val parts = (choice.delta ?: choice.message)?.parts.orEmpty()
        if (parts.isEmpty() || choice.contentBlockSlots.isNotEmpty()) return choice
        var toolOrdinal = 0
        val slots = parts.map { part ->
            if (part is UIMessagePart.Tool) {
                activeTextKey = null
                stableSlots.getOrPut("tool:${choice.toolCallSlots[toolOrdinal++]}", ::newSlot)
            } else {
                val identityEvent = if (part is UIMessagePart.ServerTool) buildJsonObject {
                    put("item", part.block)
                    event["output_index"]?.let { put("output_index", it) }
                } else event
                val item = item(identityEvent)
                if (part is UIMessagePart.Text) {
                    val explicitContent = event["content_index"]?.jsonPrimitive?.intOrNull
                    check("content_index" !in event || explicitContent != null && explicitContent >= 0) { "responses_content_index_invalid" }
                    val content = explicitContent ?: 0
                    val kind = if (event.string("type")?.contains("refusal") == true) "refusal" else "text"
                    val key = "${item.serial}:$content:$kind"
                    if (activeTextKey != key) {
                        activeTextKey = key
                        activeTextSlot = newSlot()
                    }
                    requireNotNull(activeTextSlot)
                } else {
                    activeTextKey = null
                    val kind = when (part) {
                        is UIMessagePart.Reasoning -> "reasoning"
                        is UIMessagePart.Image -> "image"
                        is UIMessagePart.ServerTool -> "server"
                        else -> error("responses_display_part_unsupported")
                    }
                    stableSlots.getOrPut("${item.serial}:$kind", ::newSlot)
                }
            }
        }
        return choice.copy(contentBlockSlots = slots)
    }

    private fun newSlot() = ProviderContentBlockSlot(responseId, nextSlot++)

    private fun item(event: JsonObject): Item {
        val raw = event["item"] as? JsonObject
        val outerId = event.string("item_id")
        val innerId = raw?.string("id")
        check(outerId == null || innerId == null || outerId == innerId) { "responses_display_identity_conflict" }
        val id = outerId ?: innerId
        val index = event["output_index"]?.jsonPrimitive?.intOrNull
        check("output_index" !in event || index != null && index >= 0) { "responses_display_index_invalid" }
        check(!id.isNullOrBlank() || index != null) { "responses_display_identity_missing" }
        val named = id?.let(byId::get)
        val numbered = index?.let(byIndex::get)
        check(named == null || numbered == null || named === numbered) { "responses_display_identity_conflict" }
        val item = named ?: numbered ?: Item(nextItem++)
        check(id == null || item.id == null || item.id == id) { "responses_display_identity_conflict" }
        check(index == null || item.index == null || item.index == index) { "responses_display_identity_conflict" }
        if (id != null) item.id = id
        if (index != null) item.index = index
        if (id != null) byId[id] = item
        if (index != null) byIndex[index] = item
        return item
    }
}

private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
