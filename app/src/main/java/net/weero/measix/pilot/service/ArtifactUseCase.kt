package net.weero.measix.pilot.service

import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs

import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.ai.attachments.ImageMime
import net.weero.measix.pilot.data.files.ArtifactDeleteImpact
import net.weero.measix.pilot.data.files.ArtifactDeleteResult
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.files.requireDiscarded
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore

enum class ArtifactUiOrigin {
    USER,
    GENERATED,
    SYSTEM,
}

data class ArtifactDeleteImpactUiModel(
    val referencedByHistory: Boolean,
    val assistantBackgroundCount: Int,
    val assistantAvatarCount: Int,
    val assistantPresetCount: Int,
)

/** Draft-owned import descriptor; callers never have to rediscover metadata from a managed file URI. */
data class ArtifactDraftItem(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
)

data class ArtifactInputPreview(val displayName: String, val image: ImageSource?)

sealed interface ArtifactDeleteOutcome {
    data object Deleted : ArtifactDeleteOutcome
    data object CleanupPending : ArtifactDeleteOutcome
    data object InProgress : ArtifactDeleteOutcome
    data object AlreadyDeleted : ArtifactDeleteOutcome
    data class Failed(val reason: String) : ArtifactDeleteOutcome
}

/** UI/Application 的 artifact 端口；调用方看不到 DAO、payload 或删除状态机。 */
class ArtifactUseCase internal constructor(
    private val store: ArtifactStore,
    private val recoveryGate: ApplicationRecoveryGate,
    private val sessions: EnterpriseSessionController,
) {
    internal fun openDraftScope(view: ConversationViewLease): ArtifactDraftScope {
        view.requireOpen()
        return openDraftScope(view.commandTarget)
    }

    internal fun openDraftScope(target: ConversationCommandTarget): ArtifactDraftScope {
        target.requireOpen()
        return ArtifactDraftScope(store, recoveryGate, sessions, target)
    }

    /** 验证并创建 Settings 图像 artifact，由同一挂起所有者提交 durable root。 */
    suspend fun importSettingsImage(uri: Uri, transform: (Settings, Uri) -> Settings): Uri {
        recoveryGate.awaitReady()
        val owned = store.createConfigurationImage(ConfigurationScope.Personal, uri)
        var ownershipTransferred = false
        return try {
            withContext(NonCancellable) {
                store.updateSettingsReferences { settings -> transform(settings, owned.uri) }
                // The transform must actually root the new artifact. A no-op/missing owner is a
                // failed import, not a published orphan.
                store.publishUnpublished(owned)
                ownershipTransferred = true
                owned.uri
            }
        } catch (error: Throwable) {
            if (!ownershipTransferred) {
                withContext(NonCancellable) {
                    try {
                        store.discardUnpublished(owned).requireDiscarded("settings artifact import rollback")
                    } catch (cleanup: Throwable) {
                        error.addSuppressed(cleanup)
                    }
                }
            }
            throw error
        }
    }

    fun displayName(uri: Uri): String? = store.displayName(uri)

    fun mimeType(uri: Uri): String? = store.mimeType(uri)

    fun isManagedUploadUrl(url: String): Boolean = store.isUploadUri(Uri.parse(url))

    suspend fun updateSettingsReferences(transform: (Settings) -> Settings): Settings {
        recoveryGate.awaitReady()
        return store.updateSettingsReferences(transform)
    }

    /** Runs non-authoritative storage maintenance without turning a committed UI command into failure. */
    suspend fun maintainStorage(protectionWindowMillis: Long = 0) {
        recoveryGate.awaitReady()
        try {
            store.collectGarbage(protectionWindowMillis)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "artifact storage maintenance failed", error)
        }
    }

    private companion object {
        const val TAG = "ArtifactUseCase"
    }
}

internal fun ArtifactDeleteImpact.toUiModel() = ArtifactDeleteImpactUiModel(
    referencedByHistory = referencedByHistory,
    assistantBackgroundCount = assistantBackgroundCount,
    assistantAvatarCount = assistantAvatarCount,
    assistantPresetCount = assistantPresetCount,
)

internal fun ArtifactDeleteResult.toOutcome(): ArtifactDeleteOutcome = when (this) {
    is ArtifactDeleteResult.Completed -> ArtifactDeleteOutcome.Deleted
    is ArtifactDeleteResult.CleanupPending -> ArtifactDeleteOutcome.CleanupPending
    is ArtifactDeleteResult.Rejected -> when (reason) {
        ArtifactDeleteResult.RejectionReason.IN_PROGRESS -> ArtifactDeleteOutcome.InProgress
        ArtifactDeleteResult.RejectionReason.ALREADY_DELETED -> ArtifactDeleteOutcome.AlreadyDeleted
    }
    is ArtifactDeleteResult.Failed -> ArtifactDeleteOutcome.Failed(reason)
}

/** 单个输入编辑器拥有的 draft lease；创建、发布、丢弃和关闭在同一 Mutex 下串行。 */
class ArtifactDraftScope internal constructor(
    private val store: ArtifactStore,
    private val recoveryGate: ApplicationRecoveryGate,
    private val sessions: EnterpriseSessionController,
    internal val target: ConversationCommandTarget,
) : AutoCloseable {
    internal val scope: ConfigurationScope get() = target.selection.access.scope
    private val closeRequested = AtomicBoolean(false)
    private val mutex = Mutex()
    private val owned = linkedMapOf<String, OwnedArtifact>()
    private val imageReadIdentity = kotlin.uuid.Uuid.random()
    private val _inputRevision = MutableStateFlow(0L)
    val inputRevision = _inputRevision.asStateFlow()

    private fun inputsChanged() { _inputRevision.update { it + 1 } }

    suspend fun importUrisOrThrow(uris: List<Uri>): List<ArtifactDraftItem> = withOwnershipLock {
        target.requireOpen()
        if (uris.isEmpty()) return@withOwnershipLock emptyList()
        val created = mutableListOf<OwnedArtifact>()
        try {
            uris.forEach { uri ->
                val artifact = try {
                    store.createFromUri(scope, uri)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    throw ArtifactImportException(uri.toString(), error)
                }
                created += artifact
            }
            created.forEach { artifact -> owned[artifact.uri.toString()] = artifact }
            if (created.isNotEmpty()) inputsChanged()
            created.map { artifact ->
                ArtifactDraftItem(
                    uri = artifact.uri,
                    displayName = artifact.entity.displayName,
                    mimeType = artifact.entity.mimeType,
                )
            }
        } catch (error: Throwable) {
            rollback(created, "artifact import rollback", error)
            throw error
        }
    }

    suspend fun describeInputs(parts: List<UIMessagePart>): Map<String, ArtifactInputPreview> {
        recoveryGate.awaitReady()
        return sessions.withSelectedRealmSelection(target.selection) { withOwnershipLock {
            target.requireOpen()
            val names = store.describeInput(scope, parts.collectArtifactUris().map(Uri::parse))
            val images = parts.filterIsInstance<UIMessagePart.Image>().mapTo(hashSetOf()) { it.url }
            names.mapValues { (uri, name) ->
                val image = if (uri in images) {
                    val artifact = owned[uri]
                    if (artifact != null) inputImageSource(uri, artifact.entity.id, artifact)
                    else AttachmentRefs.parseFileUrl(uri)?.let { file ->
                        store.resolveImagePreviewForFile(scope, file)?.let { inputImageSource(uri, it.artifactId, null) }
                    }
                } else null
                ArtifactInputPreview(name, image)
            }.also { ensureOpen(); target.requireOpen() }
        } }
    }

    private fun inputImageSource(uri: String, artifactId: Long, artifact: OwnedArtifact?): ImageSource = ImageSource(
        cacheIdentity = "draft:$imageReadIdentity:$artifactId:${artifact?.ownershipToken}",
        origin = ImageOrigin.UPLOAD,
        displayName = artifact?.entity?.displayName,
        modifiedAtMillis = artifact?.entity?.updatedAt,
        verifyAccess = { withImageAccess(uri, artifact) {
            if (artifact == null) store.requireImageAccess(scope, artifactId)
            else store.requireOwnedImageAccess(scope, artifact)
        } },
        readPayload = { withImageAccess(uri, artifact) {
            if (artifact == null) store.readImage(scope, artifactId)
            else store.readOwnedImage(scope, artifact)
        } },
    )

    private suspend fun <T> withImageAccess(uri: String, artifact: OwnedArtifact?, action: suspend () -> T): T {
        recoveryGate.awaitReady()
        return sessions.withSelectedRealmSelection(target.selection) { withOwnershipLock {
            target.requireOpen()
            if (artifact != null) check(owned[uri] === artifact) { "artifact_draft_image_released" }
            action().also { ensureOpen(); target.requireOpen() }
        } }.also { sessions.withSelectedRealmSelection(target.selection) { withOwnershipLock {
            target.requireOpen()
            if (artifact != null) check(owned[uri] === artifact) { "artifact_draft_image_released" }
        } } }
    }

    suspend fun createTextDocument(text: String): UIMessagePart.Document = withOwnershipLock {
        target.requireOpen()
        val artifact = store.createText(scope, text)
        owned[artifact.uri.toString()] = artifact
        inputsChanged()
        UIMessagePart.Document(
            url = artifact.uri.toString(),
            fileName = artifact.entity.displayName,
            mime = artifact.entity.mimeType,
        )
    }

    /** Accepts a flattened edit into the original editor; source attachments are never deleted here. */
    internal suspend fun acceptAnnotatedImage(png: ByteArray, accept: (UIMessagePart.Image) -> Boolean): Boolean =
        sessions.withSelectedRealmSelection(target.selection) { withOwnershipLock {
            target.requireOpen()
            require(png.size <= GeneratedMediaStore.MAX_IMAGE_BYTES) {
                "image_annotation_png_too_large: ${png.size} bytes exceeds ${GeneratedMediaStore.MAX_IMAGE_BYTES}; original image retained"
            }
            require(GeneratedMediaStore.detectImageMime(png) == "image/png") { "image_annotation_invalid_png" }
            val artifact = store.createFromBytes(scope, png, "annotation.png", "image/png",
                origin = net.weero.measix.pilot.data.db.entity.ArtifactOrigin.USER)
            var accepted = false
            var failure: Throwable? = null
            try {
                currentCoroutineContext().ensureActive()
                ensureOpen()
                target.requireOpen()
                sessions.requirePublishedSelection(target.selection)
                // A new image intentionally carries none of the source's MCP preservation metadata.
                val image = UIMessagePart.Image(artifact.uri.toString())
                if (accept(image)) {
                    owned[image.url] = artifact
                    accepted = true
                    inputsChanged()
                }
                accepted
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                if (!accepted) withContext(NonCancellable) {
                    try {
                        store.discardUnpublished(artifact).requireDiscarded("image annotation rollback")
                    } catch (cleanup: Throwable) {
                        // A failed deletion remains recoverable by the store, without an orphaned creation pin.
                        store.abandonUnpublished(artifact)
                        if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                    }
                }
            }
        } }

    internal fun requireTarget(requestTarget: ConversationCommandTarget) {
        target.requireOpen()
        check(target === requestTarget) { "artifact_draft_owner_mismatch" }
    }

    internal suspend fun claimSubmission(requestTarget: ConversationCommandTarget, parts: List<UIMessagePart>): ArtifactSubmission = withOwnershipLock {
        requireTarget(requestTarget)
        val uris = parts.collectArtifactUris()
        val retention = store.retainInputUris(scope, uris)
        val claimed = uris.mapNotNull(owned::remove)
        if (claimed.isNotEmpty()) inputsChanged()
        ArtifactSubmission(store, claimed, retention, requestTarget.selection.access, uris, recoveryGate, sessions)
    }

    /** Returns an uncommitted request's creation ownership before its failure is exposed to the editor. */
    internal suspend fun returnToDraft(submission: ArtifactSubmission) = withContext(NonCancellable) {
        try {
            mutex.withLock {
                val artifacts = submission.returnOwnership()
                if (closeRequested.get()) artifacts.forEach(store::abandonUnpublished)
                else artifacts.forEach { artifact ->
                    check(owned.put(artifact.uri.toString(), artifact) == null) { "artifact draft ownership duplicated" }
                }
                if (artifacts.isNotEmpty()) inputsChanged()
            }
        } finally {
            // close() may have observed this mutex locked after the last in-lock state check.
            releaseClosedPins()
        }
    }

    suspend fun discard(uri: Uri) = withOwnershipLock {
        val key = uri.toString()
        val artifact = owned[key] ?: return@withOwnershipLock
        val result = store.discardUnpublished(artifact)
        if (result.isDiscardTerminal()) { owned.remove(key); inputsChanged() }
    }

    /**
     * Publishes editor-owned artifacts only after the message/reference transaction has committed.
     * Batch validation happens before any creation pin is consumed.
     */
    suspend fun publishCommittedReferences(parts: List<UIMessagePart>) = withContext(NonCancellable) {
        try {
            mutex.withLock {
                val committed = parts.collectArtifactUris().mapNotNull(owned::get)
                if (committed.isNotEmpty()) store.publishAllUnpublished(committed)
                committed.forEach { owned.remove(it.uri.toString()) }
                if (committed.isNotEmpty()) inputsChanged()
            }
        } finally {
            releaseClosedPins()
        }
    }

    override fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        inputsChanged()
        releaseClosedPins()
    }

    private fun releaseClosedPins() {
        if (closeRequested.get() && mutex.tryLock()) {
            try {
                releasePinsLocked()
            } finally {
                mutex.unlock()
            }
        }
    }

    private fun ensureOpen() = check(!closeRequested.get()) { "artifact draft scope is closed" }

    private suspend inline fun <T> withOwnershipLock(crossinline action: suspend () -> T): T =
        try {
            mutex.withLock {
                ensureOpen()
                recoveryGate.awaitReady()
                action()
            }
        } finally {
            releaseClosedPins()
        }

    /**
     * ViewModel disposal cannot suspend. Closing therefore releases only the in-process creation
     * pin; it never starts an unowned database deletion. Unrooted ACTIVE artifacts become ordinary
     * GC candidates, while an operation already holding [mutex] performs this handoff on exit.
     */
    private fun releasePinsLocked() {
        owned.values.forEach(store::abandonUnpublished)
        owned.clear()
    }

    private suspend fun rollback(created: List<OwnedArtifact>, reason: String, cause: Throwable) =
        withContext(NonCancellable) {
            created.asReversed().forEach { artifact ->
                try {
                    store.discardUnpublished(artifact).requireDiscarded(reason)
                } catch (cleanup: Throwable) {
                    cause.addSuppressed(cleanup)
                }
            }
        }
}

/** Creation pins transferred from the editor to one accepted input request. */
internal class ArtifactSubmission(
    private val store: ArtifactStore,
    artifacts: List<OwnedArtifact>,
    private val retention: net.weero.measix.pilot.data.files.ArtifactRetentionLease,
    private val access: RealmAccess,
    retainedUris: Set<String>,
    private val recoveryGate: ApplicationRecoveryGate,
    private val sessions: EnterpriseSessionController,
) : AutoCloseable {
    private val ownership = Any()
    private val lockOrder = nextLockOrder.getAndIncrement()
    private val artifacts = artifacts.toMutableList()
    private var closed = false
    private var publishing = false
    private var resourcesReleased = false
    private val retainedUris = retainedUris.toSet()
    private val imageReadIdentity = kotlin.uuid.Uuid.random()

    /** The accepted input owns its previews independently of the editor which transferred it. */
    internal suspend fun describeInputs(
        parts: List<UIMessagePart>,
        verifyAccess: suspend () -> Unit,
    ): Map<String, ArtifactInputPreview> {
        val uris = parts.collectArtifactUris()
        check(retainedUris.containsAll(uris)) { "artifact_submission_input_mismatch" }
        return withPreviewAccess(verifyAccess) {
            val names = store.describeInput(access.scope, uris.map(Uri::parse))
            val images = parts.filterIsInstance<UIMessagePart.Image>().mapTo(hashSetOf()) { it.url }
            names.mapValues { (uri, name) ->
                val image = if (uri in images) {
                    val artifact = synchronized(ownership) {
                        requireOpenLocked()
                        artifacts.firstOrNull { it.uri.toString() == uri }
                    }
                    if (artifact != null) inputImageSource(artifact.entity.id, artifact, verifyAccess)
                    else AttachmentRefs.parseFileUrl(uri)?.let { file ->
                        store.resolveImagePreviewForFile(access.scope, file)?.let {
                            inputImageSource(it.artifactId, null, verifyAccess)
                        }
                    }
                } else null
                ArtifactInputPreview(name, image)
            }
        }
    }

    private fun inputImageSource(
        artifactId: Long,
        artifact: OwnedArtifact?,
        verifyAccess: suspend () -> Unit,
    ): ImageSource = ImageSource(
        cacheIdentity = "submission:$imageReadIdentity:$artifactId:${artifact?.ownershipToken}",
        origin = ImageOrigin.UPLOAD,
        displayName = artifact?.entity?.displayName,
        modifiedAtMillis = artifact?.entity?.updatedAt,
        verifyAccess = { withPreviewAccess(verifyAccess, artifact) {
            if (artifact == null) store.requireImageAccess(access.scope, artifactId)
            else store.requireOwnedImageAccess(access.scope, artifact)
        } },
        readPayload = { withPreviewAccess(verifyAccess, artifact) {
            if (artifact == null) store.readImage(access.scope, artifactId)
            else store.readOwnedImage(access.scope, artifact)
        } },
    )

    /** Queue callbacks may acquire conversation locks; never invoke them under the Session lock. */
    private suspend fun <T> withPreviewAccess(
        verifyAccess: suspend () -> Unit,
        artifact: OwnedArtifact? = null,
        action: suspend () -> T,
    ): T {
        recoveryGate.awaitReady()
        requirePreviewOwnership(artifact)
        verifyAccess()
        val result = sessions.withSelectedRealmAccess(access) {
            requirePreviewOwnership(artifact)
            action().also { requirePreviewOwnership(artifact) }
        }
        verifyAccess()
        sessions.withSelectedRealmAccess(access) {
            requirePreviewOwnership(artifact)
        }
        return result
    }

    private fun requirePreviewOwnership(artifact: OwnedArtifact?) = synchronized(ownership) {
        requireOpenLocked()
        check(artifact == null || artifacts.any { it === artifact }) { "artifact_submission_ownership_transferred" }
    }

    private fun requireOpenLocked() = check(!closed) { "artifact_submission_closed" }

    /** The replacement has already pinned every retained input before the queue accepts this transfer. */
    internal fun transferRetainedCreationTo(replacement: ArtifactSubmission) {
        check(replacement !== this) { "artifact_submission_self_transfer" }
        val first = if (lockOrder < replacement.lockOrder) this else replacement
        val second = if (first === this) replacement else this
        synchronized(first.ownership) { synchronized(second.ownership) {
            requireOpenLocked()
            replacement.requireOpenLocked()
            check(!publishing && !replacement.publishing) { "artifact_submission_publishing" }
            check(access == replacement.access && store === replacement.store) { "artifact_submission_scope_mismatch" }
            val moving = artifacts.filter { it.uri.toString() in replacement.retainedUris }
            check(moving.all { it.entity.scope == access.scope && it.uri.toString() in retainedUris }) {
                "artifact_submission_input_mismatch"
            }
            check(moving.none { old -> replacement.artifacts.any { it.entity.id == old.entity.id } }) {
                "artifact_submission_ownership_duplicated"
            }
            replacement.artifacts.addAll(moving)
            artifacts.removeAll(moving.toSet())
        } }
    }

    internal fun returnOwnership(): List<OwnedArtifact> {
        val returned = synchronized(ownership) {
            requireOpenLocked()
            check(!publishing) { "artifact_submission_publishing" }
            closed = true
            releaseOwnershipLocked()
        }
        retention.close()
        return returned
    }

    suspend fun publishCommittedReferences(parts: List<UIMessagePart>) = withContext(NonCancellable) {
        val references = parts.collectArtifactUris()
        val publishedArtifacts = synchronized(ownership) {
            requireOpenLocked()
            check(!publishing) { "artifact_submission_publishing" }
            artifacts.filter { it.uri.toString() in references }.also { publishing = true }
        }
        var published = false
        try {
            store.publishAllUnpublished(publishedArtifacts)
            published = true
        } finally {
            val released = synchronized(ownership) {
                publishing = false
                if (published) closed = true
                if (closed) releaseOwnershipLocked() else null
            }
            released?.let(::releaseResources)
        }
    }

    override fun close() {
        val released = synchronized(ownership) {
            if (closed) return
            closed = true
            // Publication owns its snapshot until the Store finishes; close revokes reads immediately.
            if (publishing) null else releaseOwnershipLocked()
        }
        released?.let(::releaseResources)
    }

    private fun releaseOwnershipLocked(): List<OwnedArtifact> {
        check(!resourcesReleased) { "artifact_submission_ownership_already_released" }
        resourcesReleased = true
        return artifacts.toList().also { artifacts.clear() }
    }

    private fun releaseResources(released: List<OwnedArtifact>) {
        try { released.forEach(store::abandonUnpublished) }
        finally { retention.close() }
    }

    private companion object {
        val nextLockOrder = AtomicLong(0)
    }
}

class ArtifactImportException(source: String, cause: Throwable) :
    IllegalStateException("Failed to import artifact: $source", cause)

private fun List<UIMessagePart>.collectArtifactUris(): Set<String> = buildSet {
    fun collect(parts: List<UIMessagePart>) {
        parts.forEach { part ->
            when (part) {
                is UIMessagePart.Image -> add(part.url)
                is UIMessagePart.Document -> add(part.url)
                is UIMessagePart.Audio -> add(part.url)
                is UIMessagePart.Video -> add(part.url)
                is UIMessagePart.Tool -> collect(part.output)
                else -> Unit
            }
        }
    }
    collect(this@collectArtifactUris)
}

private fun ArtifactDeleteResult.isDiscardTerminal(): Boolean =
    this is ArtifactDeleteResult.Completed ||
        this is ArtifactDeleteResult.CleanupPending ||
        this is ArtifactDeleteResult.Rejected && reason == ArtifactDeleteResult.RejectionReason.ALREADY_DELETED
