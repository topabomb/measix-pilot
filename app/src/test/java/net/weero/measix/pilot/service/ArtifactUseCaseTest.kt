package net.weero.measix.pilot.service

import net.weero.measix.pilot.data.configuration.ConfigurationScope

import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.ArtifactState
import net.weero.measix.pilot.data.files.ArtifactDeleteResult
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.imggen.TINY_PNG
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtifactUseCaseTest {
    @Test
    fun `oversized annotation is rejected before creation and leaves the original input untouched`() = runTest {
        val store = mockk<ArtifactStore>()
        val draft = ArtifactUseCase(store, ApplicationRecoveryGate().apply { ready() }, annotationSessions())
            .openDraftScope(ConversationViewLease(kotlin.uuid.Uuid.random(),
                net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, 0L) {})
        val oversized = ByteArray(GeneratedMediaStore.MAX_IMAGE_BYTES + 1)
        val failure = runCatching { draft.acceptAnnotatedImage(oversized) { error("must not replace original") } }.exceptionOrNull()
        org.junit.Assert.assertTrue(failure?.message?.startsWith("image_annotation_png_too_large:") == true)
        coVerify(exactly = 0) { store.createFromBytes(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { store.discardUnpublished(any()) }
        draft.close()
    }
    @Test
    fun `accepted annotation belongs to the original draft without source metadata`() = runTest {
        val store = mockk<ArtifactStore>(relaxed = true)
        val artifact = ownedInput(71, "annotation.png")
        val sessions = annotationSessions()
        val draft = ArtifactUseCase(store, ApplicationRecoveryGate().apply { ready() }, sessions)
            .openDraftScope(ConversationViewLease(kotlin.uuid.Uuid.random(),
                net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, 0L) {})
        coEvery { store.createFromBytes(any(), any(), any(), any(), any(), ArtifactOrigin.USER) } returns artifact
        var result: UIMessagePart.Image? = null
        org.junit.Assert.assertTrue(draft.acceptAnnotatedImage(TINY_PNG) { result = it; true })
        assertEquals(UIMessagePart.Image(artifact.uri.toString()), result)
        coVerify(exactly = 0) { store.discardUnpublished(any()) }
        verify(exactly = 0) { store.abandonUnpublished(any()) }
        draft.close()
        verify(exactly = 1) { store.abandonUnpublished(artifact) }
    }

    @Test
    fun `late annotation and revoked selection discard only the newly created image`() = runTest {
        for (revoked in listOf(false, true)) {
            val store = mockk<ArtifactStore>(relaxed = true)
            val artifact = ownedInput(72, "late.png")
            val sessions = annotationSessions()
            val draft = ArtifactUseCase(store, ApplicationRecoveryGate().apply { ready() }, sessions)
                .openDraftScope(ConversationViewLease(kotlin.uuid.Uuid.random(),
                    net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, 0L) {})
            coEvery { store.createFromBytes(any(), any(), any(), any(), any(), ArtifactOrigin.USER) } returns artifact
            coEvery { store.discardUnpublished(artifact) } returns ArtifactDeleteResult.Completed(artifact.entity.id)
            if (revoked) every { sessions.requirePublishedSelection(any()) } throws IllegalStateException("selection expired")
            var called = false
            val result = runCatching { draft.acceptAnnotatedImage(TINY_PNG) { called = true; false } }
            if (revoked) {
                assertEquals("selection expired", result.exceptionOrNull()?.message)
                org.junit.Assert.assertFalse(called)
            } else org.junit.Assert.assertFalse(result.getOrThrow())
            coVerify(exactly = 1) { store.discardUnpublished(artifact) }
            coVerify(exactly = 1) { store.discardUnpublished(any()) }
            draft.close()
            verify(exactly = 0) { store.abandonUnpublished(any()) }
        }
    }

    @Test
    fun `annotation cancellation preserves original diagnostic when rollback fails`() = runTest {
        val store = mockk<ArtifactStore>(relaxed = true)
        val artifact = ownedInput(73, "cancelled.png")
        val draft = ArtifactUseCase(store, ApplicationRecoveryGate().apply { ready() }, annotationSessions())
            .openDraftScope(ConversationViewLease(kotlin.uuid.Uuid.random(),
                net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, 0L) {})
        coEvery { store.createFromBytes(any(), any(), any(), any(), any(), ArtifactOrigin.USER) } returns artifact
        val cancelled = kotlinx.coroutines.CancellationException("editor closed")
        val cleanup = java.io.IOException("cannot delete annotation")
        coEvery { store.discardUnpublished(artifact) } throws cleanup
        val observed = runCatching { draft.acceptAnnotatedImage(TINY_PNG) { throw cancelled } }.exceptionOrNull()
        org.junit.Assert.assertSame(cancelled, observed)
        org.junit.Assert.assertTrue(observed!!.suppressed.any { it.message == cleanup.message })
        verify(exactly = 1) { store.abandonUnpublished(artifact) }
        draft.close()
        verify(exactly = 1) { store.abandonUnpublished(artifact) }
    }

    private fun annotationSessions() = mockk<net.weero.measix.pilot.data.enterprise.EnterpriseSessionController>(relaxed = true).also { sessions ->
        coEvery { sessions.withSelectedRealmSelection<Any?>(any(), any()) } coAnswers { secondArg<suspend () -> Any?>()() }
    }

    @Test
    fun `closing editor during annotation creation rejects handoff and releases the new artifact`() = runTest {
        val store = mockk<ArtifactStore>(relaxed = true)
        val artifact = ownedInput(74, "closed.png")
        val draft = ArtifactUseCase(store, ApplicationRecoveryGate().apply { ready() }, annotationSessions())
            .openDraftScope(ConversationViewLease(kotlin.uuid.Uuid.random(),
                net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, 0L) {})
        coEvery { store.createFromBytes(any(), any(), any(), any(), any(), ArtifactOrigin.USER) } coAnswers {
            draft.close()
            artifact
        }
        coEvery { store.discardUnpublished(artifact) } returns ArtifactDeleteResult.Completed(artifact.entity.id)
        val observed = runCatching { draft.acceptAnnotatedImage(TINY_PNG) { error("must not reach UI") } }.exceptionOrNull()
        assertEquals("artifact draft scope is closed", observed?.message)
        coVerify(exactly = 1) { store.discardUnpublished(artifact) }
        verify(exactly = 0) { store.abandonUnpublished(any()) }
    }

    @Test
    fun `replacement receives retained creation tokens before old submission closes`() = runTest {
        val store = mockk<ArtifactStore>(relaxed = true)
        val kept = ownedInput(21, "kept.png")
        val removed = ownedInput(22, "removed.png")
        val added = ownedInput(23, "added.png")
        var oldReleased = 0
        var replacementReleased = 0
        val old = inputSubmission(store, listOf(kept, removed), setOf(kept.uri.toString(), removed.uri.toString())) { oldReleased++ }
        val replacement = inputSubmission(store, listOf(added), setOf(kept.uri.toString(), added.uri.toString())) { replacementReleased++ }

        old.transferRetainedCreationTo(replacement)
        old.close()
        verify(exactly = 1) { store.abandonUnpublished(removed) }
        verify(exactly = 0) { store.abandonUnpublished(kept) }
        verify(exactly = 0) { store.abandonUnpublished(added) }
        assertEquals(setOf(kept, added), replacement.returnOwnership().toSet())
        replacement.close()
        assertEquals(1, oldReleased)
        assertEquals(1, replacementReleased)
        verify(exactly = 0) { store.abandonUnpublished(kept) }
    }

    @Test
    fun `closed target and foreign realm reject transfer without consuming original ownership`() = runTest {
        for (foreign in listOf(false, true)) {
            val store = mockk<ArtifactStore>(relaxed = true)
            val kept = ownedInput(24, "retained.png")
            val uris = setOf(kept.uri.toString())
            val old = inputSubmission(store, listOf(kept), uris) {}
            val targetAccess = if (foreign) net.weero.measix.pilot.data.enterprise.RealmAccess.Enterprise(
                ConfigurationScope.Enterprise(me.rerere.common.configuration.EnterpriseAuthority("other"), "user"), "session")
                else net.weero.measix.pilot.data.enterprise.RealmAccess.Personal
            val replacement = ArtifactSubmission(store, emptyList(), net.weero.measix.pilot.data.files.ArtifactRetentionLease {},
                targetAccess, uris, ApplicationRecoveryGate().apply { ready() }, mockk())
            if (!foreign) replacement.close()

            assertEquals(if (foreign) "artifact_submission_scope_mismatch" else "artifact_submission_closed",
                runCatching { old.transferRetainedCreationTo(replacement) }.exceptionOrNull()?.message)
            assertEquals(listOf(kept), old.returnOwnership())
            old.close()
            replacement.close()
            verify(exactly = 0) { store.abandonUnpublished(kept) }
        }
    }

    @Test
    fun `publication excludes transfer and delays concurrent close release until store returns`() = runTest {
        val store = mockk<ArtifactStore>(relaxed = true)
        val artifact = ownedInput(25, "publishing.png")
        val uri = artifact.uri.toString()
        var released = 0
        val old = inputSubmission(store, listOf(artifact), setOf(uri)) { released++ }
        val replacement = inputSubmission(store, emptyList(), setOf(uri)) {}
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { store.publishAllUnpublished(listOf(artifact)) } coAnswers {
            entered.complete(Unit)
            finish.await()
        }
        val publishing = async {
            old.publishCommittedReferences(listOf(UIMessagePart.Image(uri)))
        }
        entered.await()
        try {
            assertEquals("artifact_submission_publishing", runCatching {
                old.transferRetainedCreationTo(replacement)
            }.exceptionOrNull()?.message)
            assertEquals("artifact_submission_publishing", runCatching { old.returnOwnership() }.exceptionOrNull()?.message)
            old.close()
            assertEquals(0, released)
            verify(exactly = 0) { store.abandonUnpublished(artifact) }
        } finally { finish.complete(Unit) }
        publishing.await()
        assertEquals(1, released)
        verify(exactly = 1) { store.abandonUnpublished(artifact) }
        replacement.close()
    }

    @Test
    fun `failed publication preserves ownership for a later replacement`() = runTest {
        val store = mockk<ArtifactStore>(relaxed = true)
        val artifact = ownedInput(26, "retry.png")
        val uri = artifact.uri.toString()
        val original = inputSubmission(store, listOf(artifact), setOf(uri)) {}
        val replacement = inputSubmission(store, emptyList(), setOf(uri)) {}
        val failure = java.io.IOException("publication failed")
        coEvery { store.publishAllUnpublished(listOf(artifact)) } throws failure

        val observed = runCatching {
            original.publishCommittedReferences(listOf(UIMessagePart.Image(uri)))
        }.exceptionOrNull()
        assertEquals(failure.javaClass, observed?.javaClass)
        assertEquals(failure.message, observed?.message)
        original.transferRetainedCreationTo(replacement)
        original.close()
        verify(exactly = 0) { store.abandonUnpublished(artifact) }
        replacement.close()
        verify(exactly = 1) { store.abandonUnpublished(artifact) }
    }

    private fun ownedInput(id: Long, name: String): OwnedArtifact {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "file:///files/upload/$name"
        val entity = ArtifactEntity(id = id, folder = "upload", relativePath = "upload/$name",
            displayName = name, mimeType = "image/png", sizeBytes = 1, createdAt = 1, updatedAt = 1,
            state = ArtifactState.ACTIVE.name, origin = ArtifactOrigin.USER.name)
        return OwnedArtifact(entity, uri, LocalArtifactRef(relativePath = entity.relativePath, mimeType = entity.mimeType))
    }

    private fun inputSubmission(store: ArtifactStore, owned: List<OwnedArtifact>, uris: Set<String>, release: () -> Unit) =
        ArtifactSubmission(store, owned, net.weero.measix.pilot.data.files.ArtifactRetentionLease(release),
            net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, uris, ApplicationRecoveryGate().apply { ready() }, mockk())

    @Test
    fun `editor closing between return decision and reinsertion releases the returned creation pin`() = runTest {
        val store = mockk<ArtifactStore>()
        val uri = mockk<Uri>()
        val entity = ArtifactEntity(id = 8, folder = "upload", relativePath = "upload/returned.png",
            displayName = "returned.png", mimeType = "image/png", sizeBytes = 1, createdAt = 1, updatedAt = 1,
            state = ArtifactState.ACTIVE.name, origin = ArtifactOrigin.USER.name)
        val artifact = OwnedArtifact(entity, uri, LocalArtifactRef(relativePath = entity.relativePath, mimeType = entity.mimeType))
        val draft = ArtifactUseCase(store, ApplicationRecoveryGate().apply { ready() }, mockk()).openDraftScope(
            ConversationViewLease(kotlin.uuid.Uuid.random(), net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, 0L) {})
        var retentionClosed = false
        val submission = ArtifactSubmission(store, listOf(artifact), net.weero.measix.pilot.data.files.ArtifactRetentionLease {
            retentionClosed = true
        }, net.weero.measix.pilot.data.enterprise.RealmAccess.Personal,
            setOf("file:///files/upload/returned.png"), ApplicationRecoveryGate().apply { ready() }, mockk())
        every { store.abandonUnpublished(artifact) } just Runs
        // URI evaluation occurs after the open/closed decision, while returnToDraft still holds its mutex.
        every { uri.toString() } answers { draft.close(); "file:///files/upload/returned.png" }

        draft.returnToDraft(submission)
        submission.close()
        draft.close()

        org.junit.Assert.assertTrue(retentionClosed)
        verify(exactly = 1) { store.abandonUnpublished(artifact) }
        coVerify(exactly = 0) { store.discardUnpublished(any()) }
    }

    @Test
    fun `draft cannot be submitted by another view even with the same principal and conversation`() = runTest {
        val store = mockk<ArtifactStore>()
        val id = kotlin.uuid.Uuid.random()
        val view = ConversationViewLease(id, net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, 1L, closeAction = {})
        val replacement = ConversationViewLease(id, net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, 2L, closeAction = {})
        val draft = ArtifactUseCase(store, ApplicationRecoveryGate().apply { ready() }, mockk()).openDraftScope(view)
        org.junit.Assert.assertTrue(runCatching { draft.claimSubmission(replacement.commandTarget, emptyList()) }
            .exceptionOrNull() is IllegalStateException)
        coVerify(exactly = 0) { store.retainInputUris(any(), any()) }
        view.close()
        org.junit.Assert.assertTrue(runCatching { draft.createTextDocument("closed view") }
            .exceptionOrNull() is IllegalStateException)
        coVerify(exactly = 0) { store.createText(any(), any(), any(), any(), any(), any()) }
        draft.close()
    }

    @Test
    fun `closing a draft releases its pin without starting a database deletion`() = runTest {
        val store = mockk<ArtifactStore>()
        val source = mockk<Uri>()
        val ownedUri = mockk<Uri>()
        every { source.toString() } returns "content://source/image"
        every { ownedUri.toString() } returns "file:///files/upload/image.png"
        val entity = ArtifactEntity(
            id = 8,
            folder = "upload",
            relativePath = "upload/image.png",
            displayName = "image.png",
            mimeType = "image/png",
            sizeBytes = 1,
            createdAt = 1,
            updatedAt = 1,
            state = ArtifactState.ACTIVE.name,
            origin = ArtifactOrigin.USER.name,
        )
        val owned = OwnedArtifact(
            entity = entity,
            uri = ownedUri,
            localRef = LocalArtifactRef(relativePath = entity.relativePath, mimeType = entity.mimeType),
        )
        coEvery { store.createFromUri(ConfigurationScope.Personal, source) } returns owned
        every { store.abandonUnpublished(owned) } just Runs
        val scope = ArtifactUseCase(
            store,
            ApplicationRecoveryGate().apply { ready() },
            mockk(),
        ).openDraftScope(net.weero.measix.pilot.service.ConversationViewLease(
            kotlin.uuid.Uuid.random(), net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, 0L, closeAction = {}))

        val imported = scope.importUrisOrThrow(listOf(source)).single()
        assertEquals(ownedUri, imported.uri)
        assertEquals("image.png", imported.displayName)
        assertEquals("image/png", imported.mimeType)
        scope.close()

        verify(exactly = 1) { store.abandonUnpublished(owned) }
        coVerify(exactly = 0) { store.discardUnpublished(any()) }
    }

    @Test
    fun `durable publish receipt remains valid after the editor scope closes`() = runTest {
        val store = mockk<ArtifactStore>()
        val source = mockk<Uri>()
        val ownedUri = mockk<Uri>()
        every { source.toString() } returns "content://source/image"
        every { ownedUri.toString() } returns "file:///files/upload/image.png"
        val entity = ArtifactEntity(
            id = 9,
            folder = "upload",
            relativePath = "upload/image.png",
            displayName = "image.png",
            mimeType = "image/png",
            sizeBytes = 1,
            createdAt = 1,
            updatedAt = 1,
            state = ArtifactState.ACTIVE.name,
            origin = ArtifactOrigin.USER.name,
        )
        val owned = OwnedArtifact(
            entity = entity,
            uri = ownedUri,
            localRef = LocalArtifactRef(relativePath = entity.relativePath, mimeType = entity.mimeType),
        )
        coEvery { store.createFromUri(ConfigurationScope.Personal, source) } returns owned
        every { store.abandonUnpublished(owned) } just Runs
        val scope = ArtifactUseCase(
            store,
            ApplicationRecoveryGate().apply { ready() },
            mockk(),
        ).openDraftScope(net.weero.measix.pilot.service.ConversationViewLease(
            kotlin.uuid.Uuid.random(), net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, 0L, closeAction = {}))

        scope.importUrisOrThrow(listOf(source))
        scope.close()
        scope.publishCommittedReferences(listOf(UIMessagePart.Image(url = ownedUri.toString())))

        coVerify(exactly = 0) { store.publishAllUnpublished(any()) }
        verify(exactly = 1) { store.abandonUnpublished(owned) }
    }

    @Test
    fun `settings import discards a new artifact when the transform does not root it`() = runTest {
        val store = mockk<ArtifactStore>()
        val source = mockk<Uri>()
        val ownedUri = mockk<Uri>()
        every { ownedUri.toString() } returns "file:///files/upload/avatar.png"
        val entity = ArtifactEntity(
            id = 7,
            folder = "upload",
            relativePath = "upload/avatar.png",
            displayName = "avatar.png",
            mimeType = "image/png",
            sizeBytes = 1,
            createdAt = 1,
            updatedAt = 1,
            state = ArtifactState.ACTIVE.name,
            origin = ArtifactOrigin.USER.name,
        )
        val owned = OwnedArtifact(
            entity = entity,
            uri = ownedUri,
            localRef = LocalArtifactRef(relativePath = entity.relativePath, mimeType = entity.mimeType),
        )
        val settings = Settings.dummy()
        coEvery { store.createConfigurationImage(any(), any()) } coAnswers { callOriginal() }
        val payload = kotlin.io.path.createTempFile(suffix = ".png").toFile().apply { writeBytes(TINY_PNG) }
        coEvery {
            store.createFromUri(ConfigurationScope.Personal, source, maxBytes = GeneratedMediaStore.MAX_IMAGE_BYTES.toLong())
        } returns owned
        every { store.file(entity) } returns payload
        coEvery { store.updateSettingsReferences(any()) } answers {
            firstArg<(Settings) -> Settings>().invoke(settings)
        }
        coEvery { store.publishUnpublished(owned) } throws IllegalStateException("artifact has no durable root")
        coEvery { store.discardUnpublished(owned) } returns ArtifactDeleteResult.Completed(entity.id)
        val useCase = ArtifactUseCase(
            store,
            ApplicationRecoveryGate().apply { ready() },
            mockk(),
        )

        var failure: IllegalStateException? = null
        try {
            useCase.importSettingsImage(source) { current, _ -> current }
        } catch (error: IllegalStateException) {
            failure = error
        }

        assertNotNull(failure)
        coVerify(exactly = 1) { store.discardUnpublished(owned) }
        payload.delete()
    }

    @Test
    fun `invalid settings image is discarded before a Settings root can be written`() = runTest {
        val store = mockk<ArtifactStore>()
        val source = mockk<Uri>()
        val ownedUri = mockk<Uri>()
        every { source.toString() } returns "content://source/not-image"
        every { ownedUri.toString() } returns "file:///files/upload/not-image.png"
        val entity = ArtifactEntity(
            id = 12,
            folder = "upload",
            relativePath = "upload/not-image.png",
            displayName = "not-image.png",
            mimeType = "image/png",
            sizeBytes = 12,
            createdAt = 1,
            updatedAt = 1,
            state = ArtifactState.ACTIVE.name,
            origin = ArtifactOrigin.USER.name,
        )
        val owned = OwnedArtifact(
            entity = entity,
            uri = ownedUri,
            localRef = LocalArtifactRef(relativePath = entity.relativePath, mimeType = entity.mimeType),
        )
        coEvery { store.createConfigurationImage(any(), any()) } coAnswers { callOriginal() }
        val payload = kotlin.io.path.createTempFile(suffix = ".png").toFile().apply {
            writeText("not an image")
        }
        coEvery {
            store.createFromUri(ConfigurationScope.Personal, source, maxBytes = GeneratedMediaStore.MAX_IMAGE_BYTES.toLong())
        } returns owned
        every { store.file(entity) } returns payload
        coEvery { store.discardUnpublished(owned) } returns ArtifactDeleteResult.Completed(entity.id)
        val useCase = ArtifactUseCase(store, ApplicationRecoveryGate().apply { ready() }, mockk())

        val failure = runCatching {
            useCase.importSettingsImage(source) { current, _ -> current }
        }.exceptionOrNull()

        assertNotNull(failure)
        coVerify(exactly = 0) { store.updateSettingsReferences(any()) }
        coVerify(exactly = 0) { store.publishUnpublished(any()) }
        coVerify(exactly = 1) { store.discardUnpublished(owned) }
        payload.delete()
    }
}
