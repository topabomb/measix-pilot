package net.weero.measix.pilot.service.subassistant

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.enterprise.RealmAccess
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class SubAssistantRunGateTest {
    @Test fun `cancelled waiting owner rejects answers before its finally cleanup runs`() = runTest {
        val gate = SubAssistantRunGate()
        val root = Uuid.random()
        val registered = CompletableDeferred<Unit>()
        var cleaned = false
        val waiter = launch {
            val answer = gate.registerPendingInteraction(root, RealmAccess.Personal, "run", "ask", currentCoroutineContext()[Job]!!)
            registered.complete(Unit)
            try { answer.await() }
            finally { cleaned = true; gate.unregisterPendingInteraction("run", answer) }
        }
        registered.await()
        waiter.cancel()
        assertFalse(cleaned)
        assertFalse(gate.completeAnswer(root, RealmAccess.Personal, "run", "ask", "late"))
        waiter.join()
        assertTrue(cleaned)
    }

    @Test fun `removed pending cannot be answered and late cleanup cannot cancel its replacement`() = runTest {
        val gate = SubAssistantRunGate()
        val root = Uuid.random()
        val first = gate.registerPendingInteraction(root, RealmAccess.Personal, "run", "first", Job())
        gate.unregisterPendingInteraction("run", first)
        assertTrue(first.isCancelled)
        assertFalse(gate.completeAnswer(root, RealmAccess.Personal, "run", "first", "late"))
        val next = gate.registerPendingInteraction(root, RealmAccess.Personal, "run", "second", Job())
        gate.unregisterPendingInteraction("run", first)
        assertFalse(next.isCompleted)
        assertTrue(gate.completeAnswer(root, RealmAccess.Personal, "run", "second", "current"))
        assertEquals("current", next.await())
        gate.unregisterPendingInteraction("run", next)
    }

    @Test fun `recovery cancels all pending waiters and rejects late answers`() = runTest {
        val gate = SubAssistantRunGate()
        val root = Uuid.random()
        val answers = (1..3).associate { i -> i.toString() to gate.registerPendingInteraction(root, RealmAccess.Personal, i.toString(), "ask", Job()) }
        val waiters = answers.map { (run, answer) ->
            launch(Dispatchers.Unconfined) {
                try { answer.await() }
                finally { gate.unregisterPendingInteraction(run, answer) }
            }
        }
        gate.cancelPendingInteractions()
        waiters.joinAll()
        answers.forEach { (run, answer) ->
            assertTrue(answer.isCancelled)
            assertFalse(gate.completeAnswer(root, RealmAccess.Personal, run, "ask", "late"))
        }
    }

    @Test fun `concurrent responses have exactly one accepted answer`() = runTest {
        val gate = SubAssistantRunGate()
        val root = Uuid.random()
        val answer = gate.registerPendingInteraction(root, RealmAccess.Personal, "run", "ask", Job())
        val start = CompletableDeferred<Unit>()
        val responses = (1..8).map { index -> async(Dispatchers.Default) {
            start.await()
            index.toString().let { it to gate.completeAnswer(root, RealmAccess.Personal, "run", "ask", it) }
        } }
        start.complete(Unit)
        val accepted = responses.awaitAll().filter { it.second }
        assertEquals(1, accepted.size)
        assertEquals(accepted.single().first, answer.await())
        gate.unregisterPendingInteraction("run", answer)
    }

    @Test
    fun `same master and target is busy until lease cleanup then can run again`() = runTest {
        val gate = SubAssistantRunGate()
        val key = SubAssistantRunKey(Uuid.random(), ConfigurationReference.random())
        val masterJob = Job()
        val callerId = ConfigurationReference.random()
        val first = gate.acquireLease(key, "run-1", callerId, masterJob)

        assertNotNull(first)
        assertTrue(gate.isBusy(key))
        assertNull(gate.acquireLease(key, "run-2", callerId, masterJob))

        masterJob.cancel()
        first!!.job.join()
        assertTrue(first.job.isCancelled)

        first.close()
        assertFalse(gate.isBusy(key))
        val retry = gate.acquireLease(key, "run-3", callerId, Job())
        assertNotNull(retry)
        retry!!.close()
    }

    @Test
    fun `different masters may call the same target concurrently`() {
        val gate = SubAssistantRunGate()
        val targetId = ConfigurationReference.random()
        val firstKey = SubAssistantRunKey(Uuid.random(), targetId)
        val secondKey = SubAssistantRunKey(Uuid.random(), targetId)

        val first = gate.acquireLease(firstKey, "run-1", ConfigurationReference.random(), Job())
        val second = gate.acquireLease(secondKey, "run-2", ConfigurationReference.random(), Job())

        assertNotNull(first)
        assertNotNull(second)
        first!!.close()
        second!!.close()
    }

    @Test
    fun `deleting caller cancels its lease and waits for structured cleanup`() = runTest {
        val gate = SubAssistantRunGate()
        val callerId = ConfigurationReference.random()
        val key = SubAssistantRunKey(Uuid.random(), ConfigurationReference.random())
        val lease = gate.acquireLease(key, "run-1", callerId, Job())!!
        val cancellation = CompletableDeferred<Throwable?>()
        lease.job.invokeOnCompletion { cancellation.complete(it) }

        val deleteWaiter = launch { gate.cancelRunsForAssistant(callerId) }
        runCurrent()

        assertTrue(lease.job.isCancelled)
        assertEquals("target_access_revoked", cancellation.await()?.message)
        assertTrue(deleteWaiter.isActive)

        lease.close()
        runCurrent()
        assertTrue(deleteWaiter.isCompleted)
        assertFalse(gate.isBusy(key))
    }

    @Test
    fun `deleting target cancels only leases that use that target`() = runTest {
        val gate = SubAssistantRunGate()
        val targetId = ConfigurationReference.random()
        val targetKey = SubAssistantRunKey(Uuid.random(), targetId)
        val unrelatedKey = SubAssistantRunKey(Uuid.random(), ConfigurationReference.random())
        val targetLease = gate.acquireLease(targetKey, "run-target", ConfigurationReference.random(), Job())!!
        val unrelatedLease = gate.acquireLease(unrelatedKey, "run-other", ConfigurationReference.random(), Job())!!

        val deleteWaiter = launch { gate.cancelRunsForAssistant(targetId) }
        runCurrent()

        assertTrue(targetLease.job.isCancelled)
        assertTrue(unrelatedLease.job.isActive)

        targetLease.close()
        runCurrent()
        assertTrue(deleteWaiter.isCompleted)
        unrelatedLease.close()
    }

    @Test
    fun `startup cancellation waits for lease owner cleanup`() = runTest {
        val gate = SubAssistantRunGate()
        val key = SubAssistantRunKey(Uuid.random(), ConfigurationReference.random())
        val lease = gate.acquireLease(key, "run", ConfigurationReference.random(), Job())!!

        val recovery = launch { gate.cancelAllRuns("app_restarted") }
        runCurrent()

        assertTrue(lease.job.isCancelled)
        assertTrue(recovery.isActive)
        assertTrue(gate.isBusy(key))

        lease.close()
        runCurrent()
        assertTrue(recovery.isCompleted)
        assertFalse(gate.isBusy(key))
    }
}
