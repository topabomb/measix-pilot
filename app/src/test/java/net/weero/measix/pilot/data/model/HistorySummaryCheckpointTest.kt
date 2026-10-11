package net.weero.measix.pilot.data.model

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.Json
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.request.DurableMessageLocator
import net.weero.measix.pilot.data.ai.request.RequestContextPlanner
import net.weero.measix.pilot.service.runtime.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class HistorySummaryCheckpointTest {
    private fun original() = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(
        UIMessage.user("old request"), UIMessage.assistant("old answer"),
        UIMessage.user("recent request"), UIMessage.assistant("recent answer"),
    ).map(MessageNode::of)).toSnapshot()

    private fun locators(snapshot: ConversationAggregateSnapshot) = snapshot.nodes.associate {
        it.currentMessage.id to ContextMessageLocator(it.id, it.currentMessage.id)
    }

    private fun insertion(snapshot: ConversationAggregateSnapshot, count: Int, text: String = "summary"): InsertHistorySummaryCheckpoint {
        val locations = locators(snapshot)
        val containers = checkpointContainerIds(snapshot.modelContextEntries)
        val ordinary = snapshot.nodes.filter { it.id !in containers }
        val previous = selectHistorySummary(snapshot.currentMessages(), locations, snapshot.modelContextEntries)
        val semantic = ordinary.map { locations.getValue(it.currentMessage.id) }
        val checkpoint = HistorySummaryCheckpoint(covered = ordinary.take(count).map {
            HistorySummaryCoveredMessage(locations.getValue(it.currentMessage.id),
                historySummaryFingerprint(it.currentMessage, snapshot.modelContextEntries, semantic))
        }, previousEntryId = previous?.entry?.id,
            previousSummaryFingerprint = previous?.message?.let { historySummaryFingerprint(it, snapshot.modelContextEntries, semantic) })
        val node = MessageNode.of(UIMessage.user(text))
        val origin = messageOriginEntry(node, ConversationContextSource.HistorySummary(null, "summarize", checkpoint = checkpoint))
        val index = ordinary.getOrNull(count)?.let { snapshot.nodes.indexOf(it) } ?: snapshot.nodes.size
        return InsertHistorySummaryCheckpoint(snapshot.nodes, snapshot.modelContextEntries, node, origin, index)
    }

    private fun plan(snapshot: ConversationAggregateSnapshot, limit: Int = 2) = RequestContextPlanner().planRequest(
        snapshot.currentMessages(), locators(snapshot).mapValues { (_, v) -> DurableMessageLocator(v.nodeId, v.messageId) },
        snapshot.modelContextEntries, snapshot.contextAdmissions, limit,
    )

    @Test fun `checkpoint preserves prefix and window keeps summary plus complete recent user turn`() {
        val initial = original()
        val command = insertion(initial, 2)
        val change = ConversationTransition.plan(initial, command, 10) as ConversationChange.Durable
        val result = change.snapshot
        assertEquals(initial.nodes.take(2), result.nodes.take(2))
        assertEquals(initial.nodes.takeLast(2), result.nodes.takeLast(2))
        assertEquals(listOf("summary", "recent request", "recent answer"), plan(result).messages.map { it.toText() })
        val mutation = (change.write as ConversationWrite.Mutate).mutation
        assertEquals(listOf(2, 3, 4), mutation.upsertedNodeIndices)
        assertTrue(mutation.deletedNodeIds.isEmpty())
        val raced = ConversationTransition.apply(initial, AppendUserMessage(UIMessage.user("raced")))
        assertThrows(IllegalStateException::class.java) { ConversationTransition.apply(raced, command) }
    }

    @Test fun `editing covered history invalidates checkpoint without sending its container as user text`() {
        val initial = original()
        val result = ConversationTransition.apply(initial, insertion(initial, 2))
        val edited = ConversationTransition.apply(result, EditMessageVariant(initial.nodes.first().id, UIMessage.user("changed")))
        assertNull(selectHistorySummary(edited.currentMessages(), locators(edited), edited.modelContextEntries))
        assertFalse(plan(edited, 100).messages.any { it.toText() == "summary" })
        val restored = ConversationTransition.apply(edited, SelectNodeVariant(initial.nodes.first().id, 0))
        assertNotNull(selectHistorySummary(restored.currentMessages(), locators(restored), restored.modelContextEntries))
    }

    @Test fun `summary edits retain source and whole node deletion never resurrects an older variant`() {
        val initial = original()
        val command = insertion(initial, 2)
        val saved = ConversationTransition.apply(initial, command)
        val edited = ConversationTransition.apply(saved, EditMessageVariant(command.node.id, UIMessage.user("edited summary")))
        assertEquals(2, edited.modelContextEntries.size)
        assertEquals("edited summary", plan(edited).messages.first().toText())
        for (parts in listOf(listOf(UIMessagePart.Text(" ")), listOf(UIMessagePart.Image("https://example.com/image.png")))) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                ConversationTransition.apply(saved, EditMessageVariant(command.node.id, UIMessage.user("").copy(parts = parts)))
            }
            assertTrue(failure.message!!.startsWith("summary_requires_text"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationTransition.apply(saved, EditMessageVariant(command.node.id, UIMessage.user("fake user"), truncateAfterNode = true))
        }
        val deleted = ConversationTransition.apply(edited, DeleteMessage(edited.nodes[2].currentMessage.id))
        assertEquals(initial.nodes, deleted.nodes)
        assertTrue(deleted.modelContextEntries.isEmpty())
    }

    @Test fun `identity-only clone preserves evidence but semantic changes cannot repair it`() {
        val initial = original()
        val saved = ConversationTransition.apply(initial, insertion(initial, 2))
        val ids = saved.nodes.flatMap { it.messages }.associate { it.id to Uuid.random() }
        val nodes = saved.nodes.associate { it.id to Uuid.random() }
        val copied = saved.nodes.map { it.copy(id = nodes.getValue(it.id), messages = it.messages.map { m -> m.copy(id = ids.getValue(m.id)) }) }
        val entries = ConversationModelContextApplicability.remapForClone(saved.modelContextEntries, nodes, ids, copied)
        val clone = saved.copy(nodes = copied, modelContextEntries = entries)
        assertNotNull(selectHistorySummary(clone.currentMessages(), locators(clone), entries))
        val changed = clone.copy(nodes = copied.mapIndexed { index, node -> if (index != 0) node else
            node.copy(messages = listOf(node.currentMessage.copy(parts = listOf(UIMessagePart.Text("changed payload"))))) })
        assertNull(selectHistorySummary(changed.currentMessages(), locators(changed), entries))
        assertEquals(saved.modelContextEntries.single().summaryCheckpoint()!!.covered.map { it.fingerprint },
            entries.single().summaryCheckpoint()!!.covered.map { it.fingerprint })
    }

    @Test fun `later summary requires original predecessor content even when its identity is unchanged`() {
        val initial = original()
        val firstCommand = insertion(initial, 2)
        val first = ConversationTransition.apply(initial, firstCommand)
        val second = ConversationTransition.apply(first, insertion(first, 4, "second summary"))
        assertEquals("second summary", plan(second).messages.first().toText())
        val changed = second.copy(nodes = second.nodes.map { node -> if (node.id != firstCommand.node.id) node else
            node.copy(messages = listOf(node.currentMessage.copy(parts = listOf(UIMessagePart.Text("mutated predecessor"))))) })
        assertEquals("mutated predecessor", selectHistorySummary(changed.currentMessages(), locators(changed), changed.modelContextEntries)?.message?.toText())
        assertFalse(plan(changed).messages.any { it.toText() == "second summary" })
    }

    @Test fun `subassistant fork lineage does not change evidence while result content does`() {
        val call = SubAssistantCallMetadata(runId = "old-run", previousRunId = "previous",
            targetAssistantId = "assistant", targetNameSnapshot = "Helper", childConversationId = "child", childTaskNodeId = "task")
        val tool = UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(),
            providerCallId = "call", toolName = "assistant_call", input = "{}",
            output = listOf(UIMessagePart.Text("saved result"))).mergeSubAssistantCallMetadata(Json, call)
        val message = UIMessage.assistant("").copy(parts = listOf(tool))
        fun fingerprint(part: UIMessagePart.Tool) = historySummaryFingerprint(message.copy(parts = listOf(part)), emptyList(), emptyList())
        val remapped = tool.mergeSubAssistantCallMetadata(Json, call.copy(runId = "new-run", previousRunId = null,
            childConversationId = "new-child", childTaskNodeId = "new-task"))
        assertEquals(fingerprint(tool), fingerprint(remapped))
        assertNotEquals(fingerprint(tool), fingerprint(remapped.copy(output = listOf(UIMessagePart.Text("different result")))))
        assertNotEquals(fingerprint(tool), fingerprint(tool.mergeSubAssistantCallMetadata(Json, call.copy(reason = "changed"))))
    }

    @Test fun `new summary cannot enter a request sealed before that summary existed`() {
        val initial = original()
        val step = UIMessagePart.Step(Uuid.random(), 0, kotlin.time.Instant.fromEpochMilliseconds(1))
        val ownerNode = initial.nodes.last().copy(messages = listOf(initial.nodes.last().currentMessage.copy(parts = listOf(step))))
        val owner = ContextMessageLocator(ownerNode.id, ownerNode.currentMessage.id)
        val anchor = initial.nodes[2]
        val system = ConversationModelContextEntry(owner.nodeId, owner.messageId, anchor.id, anchor.currentMessage.id,
            ConversationContextPayload(source = ConversationContextSource.System(emptyList()), body = ConversationContextBody.Inline("system")))
        val selection = TurnContextSelection(systemEntryId = system.id, ruleEntryIds = emptyList(),
            timeReminderEnabled = false, timeZoneId = "UTC")
        val original = initial.copy(nodes = initial.nodes.dropLast(1) + ownerNode, modelContextEntries = listOf(system), contextAdmissions = listOf(
            ConversationContextAdmission(owner, step.stepId, ContextMessageLocator(initial.nodes.first().id, initial.nodes.first().currentMessage.id), selection, emptyList()),
        ))
        val saved = ConversationTransition.apply(original, insertion(original, 2))
        val request = RequestContextPlanner().planRequest(saved.currentMessages(),
            locators(saved).mapValues { (_, v) -> DurableMessageLocator(v.nodeId, v.messageId) },
            saved.modelContextEntries, saved.contextAdmissions, 100, owner, step.stepId)
        assertFalse(request.messages.any { it.toText() == "summary" })
        assertTrue(request.messages.any { it.toText() == "old request" })
    }

    @Test fun `missing dependency invalidates but cyclic and wrong typed dependencies reject persisted input`() {
        val initial = original()
        val first = ConversationTransition.apply(initial, insertion(initial, 2))
        val second = ConversationTransition.apply(first, insertion(first, 4, "second"))
        fun validate(entries: List<ConversationModelContextEntry>) = ConversationContextIntegrity.validate(
            second.nodes, entries, emptyList(), null, second.header.scope)
        val firstEntry = second.modelContextEntries.first()
        val secondEntry = second.modelContextEntries.last()
        fun dependency(entry: ConversationModelContextEntry, id: Uuid) = entry.copy(payload = entry.payload.copy(
            source = (entry.payload.source as ConversationContextSource.HistorySummary).let { source ->
                source.copy(checkpoint = source.checkpoint!!.copy(previousEntryId = id, previousSummaryFingerprint = "0".repeat(64)))
            }))
        val missing = listOf(firstEntry, dependency(secondEntry, Uuid.random()))
        validate(missing)
        assertEquals(firstEntry.id, selectHistorySummary(second.currentMessages(), locators(second), missing)?.entry?.id)
        val cycle = listOf(dependency(firstEntry, secondEntry.id), secondEntry)
        assertEquals("invalid_history_summary_dependency", assertThrows(IllegalArgumentException::class.java) { validate(cycle) }.message)
        val legacy = firstEntry.copy(payload = firstEntry.payload.copy(
            source = (firstEntry.payload.source as ConversationContextSource.HistorySummary).copy(checkpoint = null)))
        assertEquals("invalid_history_summary_dependency", assertThrows(IllegalArgumentException::class.java) {
            validate(listOf(legacy, secondEntry))
        }.message)
    }
}
