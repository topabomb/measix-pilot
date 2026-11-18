package net.weero.measix.pilot.service.files

import java.io.IOException
import kotlin.uuid.Uuid
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.weero.measix.pilot.data.files.MediaThumbnailProtocol
import net.weero.measix.pilot.data.files.MediaThumbnailReadException
import net.weero.measix.pilot.data.files.MediaThumbnailUnavailableException
import net.weero.measix.pilot.service.ImageOrigin
import net.weero.measix.pilot.service.ImageSource
import net.weero.measix.pilot.utils.logDiagnosticFailure

internal data class FileThumbnailKey(val path: String, val sourceVersion: String) {
    companion object {
        fun from(file: BrowserFile): FileThumbnailKey? = file.etag
            ?.takeIf { !file.directory && file.versioned && MediaThumbnailProtocol.supports(file.name) }
            ?.let { FileThumbnailKey(file.path, it) }
    }
}

/** Disposable UI derivatives of one authenticated browser session and one fixed representation size. */
internal class FileBrowserThumbnails(
    parent: CoroutineScope,
    private val verify: suspend () -> Unit,
    private val discover: suspend () -> String?,
    private val fetch: suspend (String, FileThumbnailKey) -> ByteArray,
    bindingChanges: Flow<*>? = null,
    private val currentBinding: () -> Binding? = { null },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    data class Binding(val space: String?, val workspaceRevision: Long, val davRevision: Long?)
    private data class Cached(val id: Uuid, val bytes: ByteArray, val source: ImageSource)
    private enum class Attempt { RETRY_AFTER_BUSY, FAILED, FAILED_WHILE_VISIBLE }
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val lock = Any()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val cache = LinkedHashMap<FileThumbnailKey, Cached>(16, .75f, true)
    private val attempts = LinkedHashMap<FileThumbnailKey, Attempt>()
    private var cacheBytes = 0
    private var wanted = emptySet<FileThumbnailKey>()
    private val fetchedWhileVisible = hashSetOf<FileThumbnailKey>()
    private var capability: Result<String?>? = null
    private var discoveryRetried = false
    private var cooldownUntil: Long? = null
    private var revision = 0L
    private var binding = currentBinding()
    private var active: Work? = null
    private var closed = false
    private val _images = MutableStateFlow<Map<FileThumbnailKey, ImageSource>>(emptyMap())
    val images = _images.asStateFlow()
    private val _failure = MutableStateFlow<Throwable?>(null)
    val failure = _failure.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            bindingChanges?.collect { synchronized(lock) { reconcileBinding() } }
        }
        scope.launch { schedule() }
    }

    fun visible(files: List<BrowserFile>) = synchronized(lock) {
        if (closed) return@synchronized
        wanted = files.mapNotNullTo(linkedSetOf(), FileThumbnailKey::from)
        fetchedWhileVisible.retainAll(wanted)
        attempts.entries.removeAll { (key, attempt) -> attempt == Attempt.FAILED_WHILE_VISIBLE && key !in wanted }
        trimAttempts()
        active?.takeIf { it.key != null && it.key !in wanted }?.response?.cancel()
        wanted.forEach { cache[it] }
        wake()
    }

    /** Entries interest the cache only after the browser accepts this listing. */
    fun directory(directory: BrowserDirectory) = synchronized(lock) {
        if (closed) return@synchronized
        val keys = (cache.keys + attempts.keys + wanted).filter { it.path.substringBeforeLast('/', "") == directory.path }
        if (keys.isEmpty()) return@synchronized
        val paths = keys.mapTo(hashSetOf()) { it.path }
        val current = directory.files.asSequence().filter { it.path in paths }.mapNotNull(FileThumbnailKey::from).toHashSet()
        val dropped = keys.toSet() - current
        if (dropped.isEmpty()) return@synchronized
        wanted = wanted - dropped
        fetchedWhileVisible.removeAll(dropped)
        val previousSize = cache.size
        dropped.forEach { forget(it); attempts.remove(it) }
        active?.takeIf { it.key in dropped }?.response?.cancel()
        if (cache.size != previousSize) publish()
        wake()
    }

    fun retryFailures() = synchronized(lock) {
        if (closed) return@synchronized
        if (capability?.isFailure == true) {
            capability = null
            discoveryRetried = false
        }
        attempts.clear()
        _failure.value = null
        wake()
    }

    fun cachedKey(file: BrowserFile): FileThumbnailKey? = synchronized(lock) {
        reconcileBinding()
        FileThumbnailKey.from(file)?.takeIf { it in cache }
    }

    fun sourceChanged(key: FileThumbnailKey) = synchronized(lock) {
        if (closed) return@synchronized
        wanted = wanted - key
        active?.takeIf { it.key == key }?.response?.cancel()
        val previousSize = cache.size
        forget(key)
        recordAttempt(key, Attempt.FAILED)
        if (cache.size != previousSize) publish()
    }

    fun checkSourceVersion(file: BrowserFile, observedVersion: String?) {
        val key = FileThumbnailKey.from(file) ?: return
        if (observedVersion != key.sourceVersion) sourceChanged(key)
    }

    fun cancel() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        wanted = emptySet()
        clear()
        scope.cancel()
    }

    suspend fun close() {
        cancel()
        scope.coroutineContext.job.join()
    }

    private fun reconcileBinding() {
        if (closed) return
        val current = currentBinding()
        if (current != binding) {
            binding = current
            clear()
            wake()
        }
    }

    private fun clear() {
        revision++
        active?.response?.cancel()
        capability = null
        discoveryRetried = false
        cooldownUntil = null
        cache.clear()
        attempts.clear()
        fetchedWhileVisible.clear()
        cacheBytes = 0
        _failure.value = null
        publish()
    }

    private fun wake() { signal.trySend(Unit) }

    private fun publish() { _images.value = cache.mapValues { it.value.source } }

    private fun forget(key: FileThumbnailKey) { cache.remove(key)?.let { cacheBytes -= it.bytes.size } }

    private fun recordAttempt(key: FileThumbnailKey, attempt: Attempt) {
        attempts.remove(key)
        attempts[key] = attempt
        trimAttempts()
    }

    private fun trimAttempts() {
        // Keep current viewport outcomes even when its demand exceeds the retained history budget.
        val entries = attempts.iterator()
        while (attempts.size > MAX_ITEMS && entries.hasNext()) {
            if (entries.next().key !in wanted) entries.remove()
        }
    }

    private fun remember(key: FileThumbnailKey, bytes: ByteArray) {
        val identity = Uuid.random()
        val binding = revision
        suspend fun requireCached(): Cached {
            verify()
            return synchronized(lock) {
                reconcileBinding()
                val entry = cache[key]
                check(!closed && revision == binding && entry?.id == identity) { "workspace_thumbnail_evicted" }
                requireNotNull(entry)
            }
        }
        val source = ImageSource(
            cacheIdentity = "workspace-thumbnail:$identity", origin = ImageOrigin.NETWORK,
            mimeHint = "image/webp", gallerySaveSupported = false, memoryCacheEnabled = false,
            verifyAccess = { requireCached() },
            readPayload = { requireCached().bytes },
        )
        forget(key)
        cache[key] = Cached(identity, bytes, source)
        cacheBytes += bytes.size
        while (cache.size > MAX_ITEMS || cacheBytes > MAX_BYTES) {
            forget(cache.keys.firstOrNull { it !in wanted } ?: cache.keys.first())
        }
        publish()
    }

    private data class Work(
        val revision: Long, val key: FileThumbnailKey?, val response: Deferred<Result<ReadResult>>,
    )

    private suspend fun schedule() = coroutineScope {
        while (isActive) {
            var wait: Long? = null
            val work = synchronized(lock) {
                reconcileBinding()
                val available = capability?.getOrNull()
                val key = wanted.firstOrNull {
                    it !in cache && it !in fetchedWhileVisible && attempts[it].let { attempt ->
                        attempt == null || attempt == Attempt.RETRY_AFTER_BUSY
                    }
                }
                wait = cooldownUntil?.minus(now())?.takeIf { it > 0 }
                if (closed || key == null || wait != null || (capability != null && available == null)) null
                else {
                    val requested = key.takeIf { available != null }
                    val read = async(start = CoroutineStart.LAZY) {
                        outcome {
                            withTimeoutOrNull(20_000) {
                                verify()
                                val value = if (requested == null) ReadResult(space = discover())
                                else ReadResult(bytes = fetch(requireNotNull(available), requested))
                                verify()
                                value
                            } ?: throw IOException("workspace_thumbnail_timeout")
                        }
                    }
                    Work(revision, requested, read).also { active = it }
                }
            }
            if (work == null) {
                val delay = wait
                if (delay == null) signal.receive() else withTimeoutOrNull(delay) { signal.receive() }
                continue
            }
            try {
                val result = work.response.await()
                synchronized(lock) {
                    reconcileBinding()
                    if (!closed && revision == work.revision) publishResult(work.key, result)
                }
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
            } finally {
                // await can report cancellation while the Call still owns its body or socket.
                withContext(NonCancellable) { work.response.join() }
                synchronized(lock) { active = null }
            }
        }
    }

    private data class ReadResult(val space: String? = null, val bytes: ByteArray? = null)

    private suspend fun outcome(block: suspend () -> ReadResult): Result<ReadResult> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    private fun publishResult(key: FileThumbnailKey?, result: Result<ReadResult>) {
        val failure = result.exceptionOrNull()
        if (failure is MediaThumbnailUnavailableException) {
            clear()
            capability = Result.success(null)
            return
        }
        val busy = (failure as? MediaThumbnailReadException)?.takeIf { it.status == 429 }
        if (busy != null) {
            // The capacity signal belongs to this connection even if this item has just scrolled away.
            cooldownUntil = maxOf(cooldownUntil ?: Long.MIN_VALUE, now() + busy.retryAfterMillis)
        }
        if (key == null) {
            if (busy != null && !discoveryRetried) {
                discoveryRetried = true
                return
            }
            capability = result.map { it.space }
            _failure.value = failure
        } else if (key !in wanted) return
        else if (failure == null) {
            attempts.remove(key)
            fetchedWhileVisible += key
            remember(key, requireNotNull(result.getOrThrow().bytes))
        } else {
            if (busy != null) {
                recordAttempt(key, if (key in attempts) Attempt.FAILED else Attempt.RETRY_AFTER_BUSY)
            } else {
                recordAttempt(key, if (failure is MediaThumbnailReadException) Attempt.FAILED else Attempt.FAILED_WHILE_VISIBLE)
                if (failure !is MediaThumbnailReadException) _failure.value = failure
            }
        }
        if (failure != null && failure !is MediaThumbnailReadException) {
            logDiagnosticFailure("FileBrowserThumbnails", "Unable to read workspace thumbnail", failure)
        }
    }

    private companion object {
        const val MAX_ITEMS = 128
        const val MAX_BYTES = 8 * 1024 * 1024
    }
}
