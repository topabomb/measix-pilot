package me.rerere.ai.core

import me.rerere.common.configuration.ConfigurationReference
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.ProviderReplayProjection
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.partsAreValidToUpload
import me.rerere.ai.ui.partsToText

/**
 * The normalized, provider-facing request message — the ONLY message type a Provider consumes.
 *
 * Produced exclusively by the app-layer `RequestAssembler` from the durable `UIMessage` projection.
 * It carries just the wire-relevant facts and drops every durable-only field (id, timestamps,
 * usage, terminal outcome): [role], model-visible [parts] with durable boundaries
 * ([UIMessagePart.Step]) and Tool client diagnostics already removed at assembly time, the lossless replay [providerMetadata],
 * and the request-only [providerReplayProjection]. Adapters retain transcript semantics; opaque
 * continuation blocks may be excluded from the wire when their original protocol evidence no longer matches.
 */
data class ModelRequestMessage(
    val role: MessageRole,
    val parts: List<UIMessagePart>,
    val modelId: ConfigurationReference? = null,
    val providerMetadata: JsonObject? = null,
    val providerReplayProjection: ProviderReplayProjection? = null,
    /** Counts from durable Step boundaries, supplied by RequestAssembler before stripping markers. */
    val samplingPartCounts: List<Int> = emptyList(),
) {
    init {
        require(samplingPartCounts.isEmpty() ||
            (samplingPartCounts.all { it >= 0 } && samplingPartCounts.sum() == parts.size)) {
            "Sampling boundaries must cover request parts exactly"
        }
        // Durable transcript boundaries never reach the Provider; the assembler drops them before construction.
        require(parts.none { it is UIMessagePart.Step }) {
            "ModelRequestMessage must not carry UIMessagePart.Step"
        }
        require(parts.none(::hasClientDiagnostic)) {
            "ModelRequestMessage must not carry Tool client diagnostics"
        }
    }

    /** Whether this message carries at least one non-blank, uploadable part. */
    fun isValidToUpload(): Boolean = partsAreValidToUpload(parts)

    /** Concatenation of the message's text parts; a convenience for assertions and diagnostics. */
    fun toText(): String = partsToText(parts)

    companion object {
        fun system(prompt: String) = ModelRequestMessage(
            role = MessageRole.SYSTEM,
            parts = listOf(UIMessagePart.Text(prompt)),
        )

        fun user(prompt: String) = ModelRequestMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(prompt)),
        )

        fun assistant(prompt: String) = ModelRequestMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(prompt)),
        )
    }
}

private fun hasClientDiagnostic(part: UIMessagePart): Boolean = part is UIMessagePart.Tool &&
    (part.clientDiagnostic != null || part.output.any(::hasClientDiagnostic))
