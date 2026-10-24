package me.rerere.ai.provider.providers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.*

/** One candidate's ordered Parts. Server ids are payload, never merge identities. */
internal class GoogleContentState {
    private var nextIndex = 0
    private var textSlot: ProviderContentBlockSlot? = null
    private var textKind: String? = null
    private val serverParts = mutableListOf<Pair<ProviderContentBlockSlot, UIMessagePart.ServerTool>>()

    fun decorate(choice: UIMessageChoice): UIMessageChoice {
        val delta = choice.delta ?: choice.message ?: UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        val parts = delta.parts.toMutableList()
        val slots = parts.map { part ->
            val kind = when (part) {
                is UIMessagePart.Text -> "text"
                is UIMessagePart.Reasoning -> "thought"
                else -> null
            }
            val metadata = part.metadataAs<GoogleThoughtMetadata>()
            val mergeable = kind != null && metadata?.thoughtSignature == null && metadata?.inlineData == null
            val merge = mergeable && kind == textKind
            val slot = if (merge) requireNotNull(textSlot) else ProviderContentBlockSlot("google", nextIndex++)
            textKind = kind.takeIf { mergeable }
            textSlot = slot.takeIf { textKind != null }
            if (part is UIMessagePart.ServerTool && !part.complete) serverParts += slot to part
            slot
        }.toMutableList()
        if (choice.finishReason == "STOP") {
            serverParts.forEach { (slot, part) ->
                parts += part.copy(complete = true)
                slots += slot
            }
            serverParts.clear()
        }
        val content = delta.copy(parts = parts)
        return if (choice.message != null) choice.copy(message = content, contentBlockSlots = slots)
        else choice.copy(delta = content, contentBlockSlots = slots)
    }
}
