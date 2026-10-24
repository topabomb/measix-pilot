package net.weero.measix.pilot.service.runtime

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.service.ArtifactSubmission
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationInputQueueTest {
    private val selection = RealmSelection(RealmAccess.Personal, 1)

    private fun TestScope.runtime(): ConversationRuntime {
        val id = Uuid.random()
        return ConversationRuntime(id, Conversation.ofId(id).toSnapshot(), backgroundScope, {})
    }

    private fun enqueue(runtime: ConversationRuntime, text: String, submission: ArtifactSubmission? = null) =
        runtime.enqueueInput(selection, runtime.durable.header.assistantId, listOf(UIMessagePart.Text(text)), true, submission)

    @Test fun `completed request cannot dispatch until its typed result is reconciled`() = runTest {
        for (outcome in InputQueueRequestOutcome.entries) {
            val runtime = runtime()
            val turn = Uuid.random()
            val worker = Job()
            runtime.installTurnWorker(turn, worker, requireQueueOutcome = true)
            enqueue(runtime, "next")
            val epoch = runtime.inputQueue.value.epoch
            val dispatch = requireNotNull(runtime.nextQueuedInputDispatch())
            val next = Job()
            runtime.releaseTurnWorker(turn, worker)
            assertFalse(runtime.reconcileInputQueueOutcome(worker, epoch, outcome))
            worker.complete()
            // Another enqueue/edit/remove notification runs before the completion notification.
            assertTrue(runtime.mustQueueInput())
            assertNull(runtime.installQueuedInputWorker(dispatch, selection, Uuid.random(), next))
            assertTrue(runtime.reconcileInputQueueOutcome(worker, epoch, outcome))
            val successful = outcome == InputQueueRequestOutcome.COMPLETED || outcome == InputQueueRequestOutcome.AWAITING_USER
            if (successful) {
                val nextTurn = Uuid.random()
                assertNotNull(runtime.installQueuedInputWorker(dispatch, selection, nextTurn, next))
                runtime.settleQueuedInputSubmission(dispatch, false)
                next.complete()
                runtime.releaseTurnWorker(nextTurn, next)
            } else {
                assertEquals(if (outcome == InputQueueRequestOutcome.CANCELLED) InputQueuePauseReason.USER_STOP
                    else InputQueuePauseReason.REQUEST_FAILED, runtime.inputQueue.value.pauseReason)
                assertNull(runtime.installQueuedInputWorker(dispatch, selection, Uuid.random(), next))
                next.complete()
            }
            runtime.clearInputQueueAfterStop()
        }
    }

    @Test fun `queued request keeps the outcome fence after its user message leaves the queue`() = runTest {
        val runtime = runtime()
        enqueue(runtime, "first")
        enqueue(runtime, "second")
        val dispatch = requireNotNull(runtime.nextQueuedInputDispatch())
        val turn = Uuid.random()
        val worker = Job()
        assertNotNull(runtime.installQueuedInputWorker(dispatch, selection, turn, worker, requireQueueOutcome = true))
        runtime.settleQueuedInputSubmission(dispatch, true)
        runtime.releaseTurnWorker(turn, worker)
        worker.complete()
        val next = Job()
        val nextDispatch = requireNotNull(runtime.nextQueuedInputDispatch())
        assertNull(runtime.installQueuedInputWorker(nextDispatch, selection, Uuid.random(), next))
        assertTrue(runtime.reconcileInputQueueOutcome(worker, dispatch.epoch, InputQueueRequestOutcome.FAILED))
        assertEquals(InputQueuePauseReason.REQUEST_FAILED, runtime.inputQueue.value.pauseReason)
        assertEquals("second", (runtime.inputQueue.value.items.single().parts.single() as UIMessagePart.Text).text)
        next.complete()
        runtime.clearInputQueueAfterStop()
    }

    @Test fun `old completion cannot release a replacement workers outcome fence`() = runTest {
        val runtime = runtime()
        val oldTurn = Uuid.random()
        val old = Job()
        runtime.installTurnWorker(oldTurn, old, requireQueueOutcome = true)
        val turn = Uuid.random()
        val replacement = Job()
        runtime.installTurnWorker(turn, replacement, requireQueueOutcome = true)
        enqueue(runtime, "next")
        val epoch = runtime.inputQueue.value.epoch
        runtime.releaseTurnWorker(oldTurn, old)
        old.complete()
        runtime.releaseTurnWorker(turn, replacement)
        replacement.complete()
        assertFalse(runtime.reconcileInputQueueOutcome(old, epoch, InputQueueRequestOutcome.FAILED))
        assertNull(runtime.inputQueue.value.pauseReason)
        val next = Job()
        assertNull(runtime.installQueuedInputWorker(requireNotNull(runtime.nextQueuedInputDispatch()), selection, Uuid.random(), next))
        assertTrue(runtime.reconcileInputQueueOutcome(replacement, epoch, InputQueueRequestOutcome.FAILED))
        assertEquals(InputQueuePauseReason.REQUEST_FAILED, runtime.inputQueue.value.pauseReason)
        next.complete()
        runtime.clearInputQueueAfterStop()
    }

    @Test fun `confirmed clear revokes queued editors without stopping the active reply`() = runTest {
        val runtime = runtime()
        val turn = Uuid.random()
        val worker = Job()
        runtime.installTurnWorker(turn, worker)
        val resources = mockk<ArtifactSubmission>(relaxed = true)
        val item = enqueue(runtime, "A", resources)
        val editor = runtime.beginQueuedInputEdit(item.id, item.version)
        var editorClosed = false
        runtime.registerQueuedInputEditCleanup(editor.id, editor.version, requireNotNull(editor.editToken)) { editorClosed = true }
        enqueue(runtime, "B")
        runtime.clearQueuedInputs(runtime.inputQueue.value.items.associate { it.id to it.version })
        assertTrue(editorClosed)
        assertTrue(runtime.inputQueue.value.items.isEmpty())
        assertFalse(worker.isCancelled)
        assertSame(worker, runtime.currentWorker())
        verify(exactly = 1) { resources.close() }
        worker.complete()
        runtime.releaseTurnWorker(turn, worker)
        assertFalse(runtime.isInUse)
    }

    @Test fun `stale clear cannot withdraw newly queued or edited input`() = runTest {
        val runtime = runtime()
        val resources = mockk<ArtifactSubmission>(relaxed = true)
        val first = enqueue(runtime, "A", resources)
        val confirmed = mapOf(first.id to first.version)
        val second = enqueue(runtime, "B")
        assertThrows(IllegalStateException::class.java) { runtime.clearQueuedInputs(confirmed) }
        assertEquals(2, runtime.inputQueue.value.items.size)
        runtime.removeQueuedInput(second.id, second.version)
        runtime.beginQueuedInputEdit(first.id, first.version)
        assertThrows(IllegalStateException::class.java) { runtime.clearQueuedInputs(confirmed) }
        verify(exactly = 0) { resources.close() }
        runtime.clearInputQueueAfterStop()
    }

    @Test fun `clear rejects a dispatch already admitted with unchanged item version`() = runTest {
        val runtime = runtime()
        val item = enqueue(runtime, "A")
        val observed = mapOf(item.id to item.version)
        val dispatch = requireNotNull(runtime.nextQueuedInputDispatch())
        val turn = Uuid.random()
        val worker = Job()
        assertNotNull(runtime.installQueuedInputWorker(dispatch, selection, turn, worker))
        assertThrows(IllegalStateException::class.java) { runtime.clearQueuedInputs(observed) }
        assertFalse(worker.isCancelled)
        runtime.settleQueuedInputSubmission(dispatch, false)
        worker.complete()
        runtime.releaseTurnWorker(turn, worker)
        runtime.clearInputQueueAfterStop()
    }

    @Test fun `editing head blocks dispatch and stale edits cannot replace newer ownership`() = runTest {
        val runtime = runtime()
        val original = mockk<ArtifactSubmission>(relaxed = true)
        val replacement = mockk<ArtifactSubmission>(relaxed = true)
        val first = enqueue(runtime, "A", original)
        val second = enqueue(runtime, "B")
        val editing = runtime.beginQueuedInputEdit(first.id, first.version)
        assertNull(runtime.nextQueuedInputDispatch())
        assertThrows(IllegalStateException::class.java) {
            runtime.resumeInputQueue(selection, runtime.durable.header.assistantId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            runtime.replaceQueuedInputContents(first.id, first.version, requireNotNull(editing.editToken), emptyList(), replacement)
        }
        verify(exactly = 0) { original.close() }
        verify(exactly = 0) { replacement.close() }
        runtime.replaceQueuedInputContents(editing.id, editing.version, requireNotNull(editing.editToken), listOf(UIMessagePart.Text("edited")), replacement)
        verify(exactly = 1) { original.close() }
        val head = runtime.inputQueue.value.items.first()
        assertEquals(first.userMessageId, head.userMessageId)
        assertEquals(first.id, runtime.nextQueuedInputDispatch()?.itemId)
        runtime.removeQueuedInput(head.id, head.version)
        verify(exactly = 1) { replacement.close() }
        assertEquals(second.id, runtime.nextQueuedInputDispatch()?.itemId)
        runtime.clearInputQueueAfterStop()
        assertFalse(runtime.isInUse)
    }

    @Test fun `stop invalidates observed dispatch and newly enqueued input cannot resume`() = runTest {
        val runtime = runtime()
        enqueue(runtime, "A")
        val observed = requireNotNull(runtime.nextQueuedInputDispatch())
        runtime.pauseInputQueue(InputQueuePauseReason.USER_STOP)
        enqueue(runtime, "B")
        val worker = Job()
        assertNull(runtime.installQueuedInputWorker(observed, selection, Uuid.random(), worker))
        assertNull(runtime.nextQueuedInputDispatch())
        assertFalse(worker.isCancelled)
        runtime.resumeInputQueue(selection, runtime.durable.header.assistantId)
        assertNotEquals(observed, runtime.nextQueuedInputDispatch())
        worker.complete()
        runtime.clearInputQueueAfterStop()
    }

    @Test fun `late editor cleanup cannot cancel a newer edit or clear a realm pause`() = runTest {
        val runtime = runtime()
        val original = mockk<ArtifactSubmission>(relaxed = true)
        val queued = enqueue(runtime, "A", original)
        val first = runtime.beginQueuedInputEdit(queued.id, queued.version)
        val token = requireNotNull(first.editToken)
        assertFalse(runtime.cancelQueuedInputEdit(first.id, first.version, Uuid.random()))
        runtime.pauseInputQueue(InputQueuePauseReason.REALM_CHANGED)
        assertTrue(runtime.cancelQueuedInputEdit(first.id, first.version, token))
        assertFalse(runtime.cancelQueuedInputEdit(first.id, first.version, token))
        val waiting = runtime.inputQueue.value.items.single()
        val second = runtime.beginQueuedInputEdit(waiting.id, waiting.version)
        assertFalse(runtime.cancelQueuedInputEdit(first.id, first.version, token))
        assertEquals(second, runtime.inputQueue.value.items.single())
        assertEquals(InputQueuePauseReason.REALM_CHANGED, runtime.inputQueue.value.pauseReason)
        verify(exactly = 0) { original.close() }
        runtime.removeQueuedInput(second.id, second.version)
        assertFalse(runtime.cancelQueuedInputEdit(second.id, second.version, requireNotNull(second.editToken)))
        verify(exactly = 1) { original.close() }
    }

    @Test fun `failed attachment transfer preserves editing item and old ownership`() = runTest {
        val runtime = runtime()
        val original = mockk<ArtifactSubmission>(relaxed = true)
        val replacement = mockk<ArtifactSubmission>(relaxed = true)
        val queued = enqueue(runtime, "A", original)
        val editing = runtime.beginQueuedInputEdit(queued.id, queued.version)
        io.mockk.every { original.transferRetainedCreationTo(replacement) } throws IllegalStateException("closed")
        assertThrows(IllegalStateException::class.java) {
            runtime.replaceQueuedInputContents(editing.id, editing.version, requireNotNull(editing.editToken),
                listOf(UIMessagePart.Text("new")), replacement)
        }
        assertEquals(editing, runtime.inputQueue.value.items.single())
        verify(exactly = 0) { original.close() }
        verify(exactly = 0) { replacement.close() }
        runtime.clearInputQueueAfterStop()
    }

    @Test fun `editor resources are revoked synchronously on every terminal edit path`() = runTest {
        for (terminal in listOf("cancel", "replace", "remove", "clear")) {
            val runtime = runtime()
            val queued = enqueue(runtime, "A")
            val editing = runtime.beginQueuedInputEdit(queued.id, queued.version)
            val token = requireNotNull(editing.editToken)
            var closed = 0
            runtime.registerQueuedInputEditCleanup(editing.id, editing.version, token) { closed++ }
            when (terminal) {
                "cancel" -> runtime.cancelQueuedInputEdit(editing.id, editing.version, token)
                "replace" -> runtime.replaceQueuedInputContents(editing.id, editing.version, token,
                    listOf(UIMessagePart.Text("B")), null)
                "remove" -> runtime.removeQueuedInput(editing.id, editing.version)
                "clear" -> runtime.clearInputQueueAfterStop()
            }
            assertEquals(terminal, 1, closed)
            runtime.cancelQueuedInputEdit(editing.id, editing.version, token)
            runtime.clearInputQueueAfterStop()
            assertEquals(terminal, 1, closed)
        }
    }

    @Test fun `worker retirement before finally completion is not idle`() = runTest {
        val runtime = runtime()
        val oldTurn = Uuid.random()
        val oldWorker = Job()
        runtime.installTurnWorker(oldTurn, oldWorker)
        enqueue(runtime, "next")
        val dispatch = requireNotNull(runtime.nextQueuedInputDispatch())
        val next = Job()
        assertNull(runtime.installQueuedInputWorker(dispatch, selection, Uuid.random(), next))
        assertFalse(oldWorker.isCancelled)
        runtime.releaseTurnWorker(oldTurn, oldWorker)
        assertNull(runtime.currentWorker())
        assertNull(runtime.installQueuedInputWorker(dispatch, selection, Uuid.random(), next))
        oldWorker.complete()
        val turn = Uuid.random()
        assertNotNull(runtime.installQueuedInputWorker(dispatch, selection, turn, next))
        runtime.settleQueuedInputSubmission(dispatch, false)
        next.complete()
        runtime.releaseTurnWorker(turn, next)
        runtime.clearInputQueueAfterStop()
    }

    @Test fun `uncommitted failure preserves item and attachments while committed receipt survives stop`() = runTest {
        val runtime = runtime()
        val resources = mockk<ArtifactSubmission>(relaxed = true)
        val input = enqueue(runtime, "retained", resources)
        val dispatch = requireNotNull(runtime.nextQueuedInputDispatch())
        val worker = Job()
        val turn = Uuid.random()
        runtime.installQueuedInputWorker(dispatch, selection, turn, worker)
        runtime.settleQueuedInputSubmission(dispatch, false)
        assertEquals(input.userMessageId, runtime.inputQueue.value.items.single().userMessageId)
        assertEquals(InputQueuePauseReason.REQUEST_FAILED, runtime.inputQueue.value.pauseReason)
        verify(exactly = 0) { resources.close() }
        worker.complete()
        runtime.releaseTurnWorker(turn, worker)
        runtime.resumeInputQueue(selection, runtime.durable.header.assistantId)
        val retry = requireNotNull(runtime.nextQueuedInputDispatch())
        val retryWorker = Job()
        val retryTurn = Uuid.random()
        runtime.installQueuedInputWorker(retry, selection, retryTurn, retryWorker)
        runtime.pauseInputQueue(InputQueuePauseReason.USER_STOP)
        runtime.settleQueuedInputSubmission(retry, true)
        assertTrue(runtime.inputQueue.value.items.isEmpty())
        assertEquals(InputQueuePauseReason.USER_STOP, runtime.inputQueue.value.pauseReason)
        // The committed request retains the publication responsibility, not queue withdrawal.
        verify(exactly = 0) { resources.close() }
        retryWorker.complete()
        runtime.releaseTurnWorker(retryTurn, retryWorker)
        assertFalse(runtime.isInUse)
    }

    @Test fun `queue survives page lease closing and cleanup rejects pending resources`() = runTest {
        val runtime = runtime()
        val page = runtime.acquireLease()
        val resources = mockk<ArtifactSubmission>(relaxed = true)
        enqueue(runtime, "A", resources)
        page.close()
        assertTrue(runtime.isInUse)
        assertThrows(IllegalStateException::class.java) { runtime.cleanup() }
        verify(exactly = 0) { resources.close() }
        runtime.clearInputQueueAfterStop()
        verify(exactly = 1) { resources.close() }
        assertFalse(runtime.isInUse)
        runtime.cleanup()
    }

    @Test fun `realm round trip needs explicit resume with new revision`() = runTest {
        val runtime = runtime()
        enqueue(runtime, "A")
        val old = requireNotNull(runtime.nextQueuedInputDispatch())
        val returned = RealmSelection(RealmAccess.Personal, selection.revision + 2)
        val worker = Job()
        assertNull(runtime.installQueuedInputWorker(old, returned, Uuid.random(), worker))
        runtime.pauseInputQueue(InputQueuePauseReason.REALM_CHANGED)
        runtime.resumeInputQueue(returned, runtime.durable.header.assistantId)
        val turn = Uuid.random()
        val dispatch = requireNotNull(runtime.nextQueuedInputDispatch())
        assertNotNull(runtime.installQueuedInputWorker(dispatch, returned, turn, worker))
        runtime.settleQueuedInputSubmission(dispatch, false)
        worker.complete()
        runtime.releaseTurnWorker(turn, worker)
        runtime.clearInputQueueAfterStop()
    }
}
