package net.weero.measix.pilot.service.turn

import me.rerere.ai.ui.ProviderToolCallSlot
import me.rerere.ai.ui.ProviderContentBlockSlot
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ClaudeReasoningMetadata
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isCompleteImageUrl
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.mergeMessageMetadata
import me.rerere.ai.ui.mergePartMetadata
import me.rerere.ai.ui.mergeReasoningPartMetadata
import me.rerere.ai.ui.renderableImageUrl
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Merges Provider deltas into the already-open Step of the active Assistant only. */
internal class StepOutputAccumulator {
    private var currentStepId: Uuid? = null
    private val localCallsBySlot = mutableMapOf<ProviderToolCallSlot, Uuid>()
    private val contentIndices = mutableMapOf<ProviderContentBlockSlot, Int>()
    fun accumulate(active: UIMessage, chunk: MessageChunk, model: Model?): UIMessage {
        require(active.role == me.rerere.ai.core.MessageRole.ASSISTANT)
        val step = active.parts.filterIsInstance<UIMessagePart.Step>().last()
        require(step.outcome == null && step.modelResult == null) { "Sampling requires an unsampled open Step" }
        val choice = chunk.choices.firstOrNull() ?: return active
        val delta = choice.delta ?: choice.message ?: return active
        if (currentStepId != step.stepId) {
            currentStepId = step.stepId
            localCallsBySlot.clear()
            contentIndices.clear()
        }
        require(choice.toolCallSlots.size == delta.getTools().size) { "Provider Tool deltas require explicit transport slots" }
        require(choice.contentBlockSlots.isEmpty() || choice.contentBlockSlots.size == delta.parts.size) {
            "Provider content deltas require one slot per part"
        }
        var toolOrdinal = 0
        var parts = delta.parts.foldIndexed(active.parts) { index, acc, part ->
            if (part is UIMessagePart.Tool) {
                mergeTool(acc, acc.currentStepStart(), part, choice.toolCallSlots[toolOrdinal++])
            } else if (choice.contentBlockSlots.isNotEmpty()) {
                mergeContentBlock(acc, part, choice.contentBlockSlots[index])
            } else mergePart(acc, part)
        }
        if (delta.parts.none { it is UIMessagePart.Reasoning }) {
            val stepStart = parts.currentStepStart()
            parts = parts.mapIndexed { index, part ->
                if (index >= stepStart && part is UIMessagePart.Reasoning && part.finishedAt == null) {
                    part.copy(finishedAt = Clock.System.now())
                } else part
            }
        }
        return active.copy(
            modelId = active.modelId ?: model?.id,
            parts = parts,
            annotations = (active.annotations + delta.annotations).distinct(),
            providerMetadata = mergeMessageMetadata(active.providerMetadata, delta.providerMetadata),
        )
    }

    private fun mergeContentBlock(acc: List<UIMessagePart>, delta: UIMessagePart, slot: ProviderContentBlockSlot): List<UIMessagePart> {
        val index = contentIndices[slot]
        if (index == null) {
            require(delta is UIMessagePart.Text || delta is UIMessagePart.Reasoning || delta is UIMessagePart.ServerTool || delta is UIMessagePart.Image) { "Unsupported slotted content type" }
            contentIndices[slot] = acc.size
            return acc + if (delta is UIMessagePart.Image) delta.copy(url = renderableImageUrl(delta.url)) else delta
        }
        val previous = acc[index]
        val merged = when {
            previous is UIMessagePart.Image && delta is UIMessagePart.Image -> delta.copy(
                url = if (delta.url.isEmpty()) previous.url else renderableImageUrl(delta.url),
                metadata = mergePartMetadata(previous.metadata, delta.metadata),
            )
            previous is UIMessagePart.ServerTool && delta is UIMessagePart.ServerTool -> {
                require(previous.source == delta.source) { "Provider content block changed source" }
                require(!previous.complete || previous == delta) { "Completed server block changed" }
                delta.copy(metadata = mergePartMetadata(previous.metadata, delta.metadata))
            }
            previous is UIMessagePart.Text && delta is UIMessagePart.Text -> previous.copy(
                text = previous.text + delta.text,
                metadata = mergePartMetadata(previous.metadata, delta.metadata),
            )
            previous is UIMessagePart.Reasoning && delta is UIMessagePart.Reasoning -> previous.copy(
                reasoning = previous.reasoning + delta.reasoning,
                metadata = mergeReasoningPartMetadata(previous.metadata, delta.metadata),
                finishedAt = delta.finishedAt ?: previous.finishedAt,
            )
            else -> error("Provider content block changed type")
        }
        return acc.toMutableList().apply { set(index, merged) }
    }

    private fun mergePart(acc: List<UIMessagePart>, deltaPart: UIMessagePart): List<UIMessagePart> {
        val stepStart = acc.currentStepStart()
        return when (deltaPart) {
            is UIMessagePart.Text -> {
                if (deltaPart.text.isEmpty() && deltaPart.metadata == null) {
                    acc
                } else {
                    val insertIndex = acc.firstPendingToolIndex(stepStart)
                    val lastPart = acc.getOrNull(insertIndex - 1)
                    if (lastPart is UIMessagePart.Text &&
                        !lastPart.hasProviderPartBoundary() &&
                        !deltaPart.hasProviderPartBoundary()
                    ) {
                        acc.mapIndexed { index, part ->
                            if (index == insertIndex - 1) {
                                lastPart.copy(text = lastPart.text + deltaPart.text)
                            } else {
                                part
                            }
                        }
                    } else {
                        acc.insertAt(insertIndex, deltaPart)
                    }
                }
            }

            is UIMessagePart.Image -> {
                val insertIndex = acc.firstPendingToolIndex(stepStart)
                val lastPart = acc.getOrNull(insertIndex - 1)
                val incomingComplete = isCompleteImageUrl(deltaPart.url)
                if (lastPart is UIMessagePart.Image &&
                    !lastPart.hasProviderPartBoundary() &&
                    !deltaPart.hasProviderPartBoundary() &&
                    !incomingComplete
                ) {
                    acc.mapIndexed { index, part ->
                        if (index == insertIndex - 1) {
                            lastPart.copy(
                                url = lastPart.url + deltaPart.url,
                                metadata = deltaPart.metadata ?: lastPart.metadata,
                            )
                        } else {
                            part
                        }
                    }
                } else {
                    acc.insertAt(
                        insertIndex,
                        UIMessagePart.Image(
                            url = renderableImageUrl(deltaPart.url),
                            metadata = deltaPart.metadata,
                        ),
                    )
                }
            }

            is UIMessagePart.Reasoning -> {
                if (deltaPart.reasoning.isEmpty() && deltaPart.metadata == null) {
                    acc
                } else {
                    val reasoningIndex = (acc.lastIndex downTo stepStart).firstOrNull { index ->
                        acc[index] is UIMessagePart.Reasoning
                    }
                    if (reasoningIndex != null &&
                        !acc[reasoningIndex].hasProviderPartBoundary() &&
                        !deltaPart.hasProviderPartBoundary()
                    ) {
                        val existing = acc[reasoningIndex] as UIMessagePart.Reasoning
                        acc.mapIndexed { index, part ->
                            if (index == reasoningIndex) {
                                UIMessagePart.Reasoning(
                                    reasoning = existing.reasoning + deltaPart.reasoning,
                                    createdAt = existing.createdAt,
                                    finishedAt = null,
                                    metadata = mergeReasoningPartMetadata(
                                        existing.metadata,
                                        deltaPart.metadata,
                                    ),
                                )
                            } else {
                                part
                            }
                        }
                    } else {
                        acc.insertAt(reasoningIndex?.plus(1) ?: stepStart, deltaPart)
                    }
                }
            }

            is UIMessagePart.Tool -> error("Tool merge requires a transport slot")
            is UIMessagePart.ServerTool -> error("Server tool merge requires a content slot")

            is UIMessagePart.Audio, is UIMessagePart.Video, is UIMessagePart.Document ->
                acc.insertAt(acc.firstPendingToolIndex(stepStart), deltaPart)
            is UIMessagePart.Step -> error("Provider output cannot create a durable Step")
        }
    }

    private fun mergeTool(
        acc: List<UIMessagePart>,
        stepStart: Int,
        deltaPart: UIMessagePart.Tool,
        slot: ProviderToolCallSlot,
    ): List<UIMessagePart> {
        val localCallId = localCallsBySlot[slot]
        val existingIndex = localCallId?.let { id ->
            (stepStart until acc.size).firstOrNull { index ->
                (acc[index] as? UIMessagePart.Tool)?.localCallId == id
            } ?: error("Transport slot lost its active Tool owner")
        }
        return if (existingIndex == null) {
            val stamped = stampNewTool(deltaPart, (acc[stepStart - 1] as UIMessagePart.Step).stepId)
            localCallsBySlot[slot] = stamped.localCallId
            acc + stamped
        } else {
            acc.mapIndexed { index, part ->
                if (index == existingIndex) {
                    (part as UIMessagePart.Tool).merge(deltaPart)
                } else {
                    part
                }
            }
        }
    }

    /** Assign the current Step id and a fresh random local call id to a newly seen tool call. */
    private fun stampNewTool(tool: UIMessagePart.Tool, stepId: Uuid): UIMessagePart.Tool =
        tool.copy(stepId = stepId, localCallId = Uuid.random())

    private fun List<UIMessagePart>.currentStepStart(): Int =
        indexOfLast { it is UIMessagePart.Step } + 1

    private fun List<UIMessagePart>.firstPendingToolIndex(stepStart: Int): Int {
        val relativeIndex = subList(stepStart, size).indexOfFirst {
            it is UIMessagePart.Tool && !it.hasReplayResult
        }
        return if (relativeIndex >= 0) stepStart + relativeIndex else size
    }

    private fun List<UIMessagePart>.insertAt(index: Int, part: UIMessagePart): List<UIMessagePart> {
        // Legacy unslotted media may be inserted before tools; transport slots still address
        // their original content blocks after the visible list shifts.
        contentIndices.replaceAll { _, position -> if (position >= index) position + 1 else position }
        return toMutableList().apply { add(index, part) }
    }

    private fun UIMessagePart.hasProviderPartBoundary(): Boolean {
        val googleMetadata = metadataAs<GoogleThoughtMetadata>()
        val claudeMetadata = metadataAs<ClaudeReasoningMetadata>()
        return googleMetadata?.thoughtSignature != null ||
            googleMetadata?.inlineData != null ||
            claudeMetadata?.redactedData != null
    }
}
