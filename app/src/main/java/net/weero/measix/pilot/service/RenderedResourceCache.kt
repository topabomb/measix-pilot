package net.weero.measix.pilot.service

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Transient bytes belong to one preview attempt, never to a persistent file owner. */
internal class RenderedResourceCache(
    private val maxBytes: Long = 32L * 1024 * 1024,
    private val maxResources: Int = 128,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    init { require(maxBytes > 0 && maxResources > 0) }
    private val lifetime = Job()
    private val lock = Any()
    private data class Entry<T>(val value: T, val expires: Long)
    private val entries = LinkedHashMap<String, Entry<Pair<String, ByteArray>>>(16, .75f, true)
    private class Request(val mutex: Mutex = Mutex(), var readers: Int = 0)
    private val requests = mutableMapOf<String, Request>()
    private val failures = LinkedHashMap<String, Entry<Exception>>(16, .75f, true)
    private val admission = Semaphore(maxResources)
    private var retained = 0L

    suspend fun read(key: String, verify: suspend () -> Unit, maxReadBytes: Long = MAX_RENDERED_RESOURCE_BYTES,
        load: suspend () -> Pair<String, ByteArray>): Pair<String, ByteArray> = coroutineScope {
        val caller = coroutineContext
        val registration = lifetime.invokeOnCompletion { caller.cancel() }
        try {
            caller.ensureActive()
            verify()
            require(maxReadBytes in 1..MAX_RENDERED_RESOURCE_BYTES)
            // Admission is reusable: long galleries wait for capacity instead of exhausting a lifetime quota.
            admission.withPermit {
                val request = synchronized(lock) {
                    lifetime.ensureActive()
                    requests.getOrPut(key) { Request() }.also { it.readers++ }
                }
                try {
                    request.mutex.withLock {
                        verify()
                        synchronized(lock) { failures[key]?.takeIf { it.expires > now() }?.value }?.let { throw it }
                        val cached = synchronized(lock) {
                            val entry = entries[key]
                            if (entry != null && entry.expires <= now()) {
                                entries.remove(key); retained -= entry.value.second.size
                                null
                            } else entry?.value
                        }
                        val result = cached ?: permits.withPermit {
                            caller.ensureActive()
                            verify()
                            val value = try { load() }
                            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (error: Exception) {
                                synchronized(lock) {
                                    lifetime.ensureActive()
                                    failures[key] = Entry(error, now() + 5_000)
                                    while (failures.size > maxResources) failures.remove(failures.keys.first())
                                }
                                throw error
                            }
                            caller.ensureActive()
                            verify()
                            synchronized(lock) {
                                lifetime.ensureActive()
                                check(value.second.size <= maxReadBytes) { "rendered_resource_size_limit: $maxReadBytes" }
                                if (value.second.size <= maxBytes) {
                                    while (entries.isNotEmpty() && (retained + value.second.size > maxBytes || entries.size >= maxResources)) {
                                        val oldest = entries.entries.iterator()
                                        retained -= oldest.next().value.value.second.size
                                        oldest.remove()
                                    }
                                    entries[key] = Entry(value, now() + 30_000)
                                    failures.remove(key)
                                    retained += value.second.size
                                }
                            }
                            value
                        }
                        check(result.second.size <= maxReadBytes) { "rendered_resource_size_limit: $maxReadBytes" }
                        verify()
                        caller.ensureActive()
                        result
                    }
                } finally {
                    synchronized(lock) {
                        if (--request.readers == 0 && requests[key] === request) requests.remove(key)
                    }
                }
            }
        } finally { registration.dispose() }
    }

    fun close() {
        lifetime.cancel()
        synchronized(lock) { entries.clear(); failures.clear(); requests.clear(); retained = 0 }
    }

    companion object {
        // Four reads of at most 8 MiB bound admitted payloads, not lifetime gallery traffic.
        private val permits = Semaphore(4)
    }
}
