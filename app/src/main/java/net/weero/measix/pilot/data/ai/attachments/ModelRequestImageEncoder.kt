package net.weero.measix.pilot.data.ai.attachments

import java.io.File
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.EncodedImage
import me.rerere.ai.util.NativeImagePolicy
import me.rerere.ai.util.RequestImageEncoder
import me.rerere.ai.util.asWebpImage
import me.rerere.ai.util.encodeOriginalNativeImage
import me.rerere.ai.util.encodeStaticNativeImage
import me.rerere.ai.util.nativeImagePolicy
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.files.ArtifactReadLease

/** Caches only derived WebP bytes. Every lookup still requires the original request's file lease. */
class ModelRequestImageEncoder internal constructor(
    private val requireAccess: suspend (RealmAccess) -> Unit = {
        check(it == RealmAccess.Personal) { "model_image_session_owner_required" }
    },
    private val staticEncode: suspend (File) -> ByteArray = ::encodeStaticNativeImage,
    private val capacityBytes: Int = 64 * 1024 * 1024,
) {
    private data class CacheKey(val access: RealmAccess, val sha256: String, val policy: NativeImagePolicy)

    private val encodeMutex = Mutex()
    private val entries = LinkedHashMap<CacheKey, ByteArray>(16, 0.75f, true)
    private var generation = 0L
    private var totalBytes = 0

    internal fun observeInvalidations(scope: CoroutineScope, changes: Flow<Unit>) {
        scope.launch {
            try {
                changes.collect { clear() }
            } finally {
                clear()
            }
        }
    }

    internal fun clear() = synchronized(entries) {
        generation++
        entries.clear()
        totalBytes = 0
    }

    internal fun open(access: RealmAccess, reads: ArtifactReadLease): Request = Request(access, reads)

    internal inner class Request(
        private val access: RealmAccess,
        private val reads: ArtifactReadLease,
    ) : RequestImageEncoder, AutoCloseable {
        private val closed = AtomicBoolean()

        override suspend fun encode(part: UIMessagePart.Image, withPrefix: Boolean): EncodedImage = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            val expectedGeneration = synchronized(entries) { generation }
            requireOpen()
            requireAccess(access)
            val encoded = if (!part.url.startsWith("file:", ignoreCase = true)) {
                RequestImageEncoder.Uncached.encode(part, withPrefix)
            } else {
                val ref = checkNotNull(reads.resolveUri(part.url)) { "model_image_not_retained" }
                val file = checkNotNull(reads.file(ref)) { "model_image_not_retained" }
                val policy = file.nativeImagePolicy(part)
                if (policy.keepOriginal) {
                    file.encodeOriginalNativeImage(policy, withPrefix)
                } else {
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().buffered().use { source ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            context.ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    val key = CacheKey(access, digest.digest().toHexString(), policy)
                    val bytes = cached(key, expectedGeneration) ?: encodeMutex.withLock {
                        context.ensureActive()
                        requireOpen()
                        cached(key, expectedGeneration) ?: staticEncode(file).also {
                            context.ensureActive()
                            requireOpen()
                            remember(key, expectedGeneration, it)
                        }
                    }
                    context.ensureActive()
                    bytes.asWebpImage(withPrefix)
                }
            }
            context.ensureActive()
            requireOpen()
            requireAccess(access)
            context.ensureActive()
            requireOpen()
            encoded
        }

        private fun requireOpen() {
            check(!closed.get()) { "model_image_request_closed" }
        }

        override fun close() {
            closed.set(true)
        }
    }

    private fun cached(key: CacheKey, expectedGeneration: Long): ByteArray? = synchronized(entries) {
        if (generation == expectedGeneration) entries[key] else null
    }

    private fun remember(key: CacheKey, expectedGeneration: Long, bytes: ByteArray) = synchronized(entries) {
        if (generation != expectedGeneration || bytes.size > capacityBytes) return@synchronized
        while (totalBytes > capacityBytes - bytes.size) {
            val iterator = entries.values.iterator()
            totalBytes -= iterator.next().size
            iterator.remove()
        }
        totalBytes += bytes.size
        entries[key] = bytes
    }
}
