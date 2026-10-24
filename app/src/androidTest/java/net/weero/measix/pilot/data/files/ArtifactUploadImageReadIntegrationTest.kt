package net.weero.measix.pilot.data.files

import me.rerere.ai.util.encodeNativeImage
import net.weero.measix.pilot.data.configuration.ConfigurationScope

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolveResult
import net.weero.measix.pilot.data.ai.attachments.AttachmentResolver
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.RoomDatabaseTransactionRunner
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.ArtifactState
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room and Android file access; no conversation snapshot, workspace or Provider network. */
@RunWith(AndroidJUnit4::class)
class ArtifactUploadImageReadIntegrationTest {
    private lateinit var root: File
    private lateinit var database: AppDatabase
    private lateinit var appScope: AppScope
    private lateinit var store: ArtifactStore
    private val png: ByteArray = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).let { bitmap ->
        try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        root = Files.createTempDirectory(application.cacheDir.toPath(), "upload-read-test-").toFile()
        val payloadContext = object : ContextWrapper(application) {
            override fun getFilesDir(): File = root
        }
        database = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
        appScope = AppScope()
        store = ArtifactStore(
            payloadStore = ArtifactPayloadStore(payloadContext),
            artifactDAO = database.artifactDao(),
            artifactReferenceDAO = database.artifactReferenceDao(),
            systemMetaDAO = database.systemMetaDao(),
            conversationDAO = database.conversationDao(),
            messageNodeDAO = database.messageNodeDao(),
            contextDAO = database.conversationModelContextDao(),
            settingsCoordinator = ArtifactSettingsCoordinator(SettingsStore(application, appScope)),
            transactionRunner = RoomDatabaseTransactionRunner(database),
        )
    }

    @After
    fun tearDown() {
        appScope.cancel()
        database.close()
        check(root.deleteRecursively())
    }

    @Test
    fun queueEditTransfersUnpublishedImageTokenAndRevokesOldPreviewWithoutCopyingPayload() = runBlocking {
        val sessions = net.weero.measix.pilot.data.enterprise.EnterpriseSessionController(
            net.weero.measix.pilot.data.enterprise.EnterpriseAppliedStore(File(root, "queue-edit-session")), net.weero.measix.pilot.data.enterprise.EnterpriseDataResetStore(java.io.File(System.getProperty("java.io.tmpdir"), "enterprise-reset-${java.util.UUID.randomUUID()}")))
        sessions.recover()
        val access = net.weero.measix.pilot.data.enterprise.RealmAccess.Personal
        val view = net.weero.measix.pilot.service.ConversationViewLease(kotlin.uuid.Uuid.random(), access,
            sessions.selectionRevision.value, closeAction = {})
        val useCase = net.weero.measix.pilot.service.ArtifactUseCase(store,
            net.weero.measix.pilot.service.ApplicationRecoveryGate().apply { ready() }, sessions)
        val draft = useCase.openDraftScope(view)
        val edit = useCase.openDraftScope(view)
        val input = File(root, "queue-edit-input.png").apply { writeBytes(png) }
        val imported = draft.importUrisOrThrow(listOf(android.net.Uri.fromFile(input))).single()
        val part = me.rerere.ai.ui.UIMessagePart.Image(imported.uri.toString())
        val original = draft.claimSubmission(view.commandTarget, listOf(part))
        val oldPreview = requireNotNull(original.describeInputs(listOf(part)) {}[part.url]?.image)
        val replacement = edit.claimSubmission(view.commandTarget, listOf(part))
        draft.close()
        edit.close()
        view.close()

        original.transferRetainedCreationTo(replacement)
        assertEquals("artifact_submission_ownership_transferred", runCatching { oldPreview.readBytes() }.exceptionOrNull()?.message)
        original.close()
        val preview = requireNotNull(replacement.describeInputs(listOf(part)) {}[part.url]?.image)
        assertArrayEquals(png, preview.readBytes())
        val artifact = store.list(ConfigurationScope.Personal).single()
        assertTrue(store.deleteUserRequested(ConfigurationScope.Personal, artifact.id) is ArtifactDeleteResult.Rejected)
        replacement.close()
        assertTrue(store.deleteUserRequested(ConfigurationScope.Personal, artifact.id) is ArtifactDeleteResult.Completed)
    }

    @Test
    fun acceptedSubmissionPreviewSurvivesEditorCloseButNotItemRevocationOrRelease() = runBlocking {
        withTimeout(10_000) {
            val sessions = net.weero.measix.pilot.data.enterprise.EnterpriseSessionController(
                net.weero.measix.pilot.data.enterprise.EnterpriseAppliedStore(File(root, "preview-session")), net.weero.measix.pilot.data.enterprise.EnterpriseDataResetStore(java.io.File(System.getProperty("java.io.tmpdir"), "enterprise-reset-${java.util.UUID.randomUUID()}")))
            sessions.recover()
            val access = net.weero.measix.pilot.data.enterprise.RealmAccess.Personal
            val view = net.weero.measix.pilot.service.ConversationViewLease(kotlin.uuid.Uuid.random(), access,
                sessions.selectionRevision.value, closeAction = {})
            val draft = net.weero.measix.pilot.service.ArtifactUseCase(store,
                net.weero.measix.pilot.service.ApplicationRecoveryGate().apply { ready() }, sessions).openDraftScope(view)
            val input = File(root, "input.png").apply { writeBytes(png) }
            val imported = draft.importUrisOrThrow(listOf(android.net.Uri.fromFile(input))).single()
            val part = me.rerere.ai.ui.UIMessagePart.Image(imported.uri.toString())
            val submission = draft.claimSubmission(view.commandTarget, listOf(part))
            draft.close()
            view.close()
            var itemAvailable = true
            // The item verifier may take Session itself; holding it around this callback deadlocks.
            val previews = submission.describeInputs(listOf(part)) {
                sessions.withSelectedRealmAccess(access) { check(itemAvailable) { "queue_item_replaced" } }
            }
            val image = requireNotNull(previews[part.url]?.image)
            assertArrayEquals(png, image.readBytes())
            val foreign = me.rerere.ai.ui.UIMessagePart.Image("file:///unretained.png")
            assertEquals("artifact_submission_input_mismatch", runCatching {
                submission.describeInputs(listOf(foreign)) {}
            }.exceptionOrNull()?.message)
            itemAvailable = false
            assertEquals("queue_item_replaced", runCatching { image.readBytes() }.exceptionOrNull()?.message)
            itemAvailable = true
            submission.close()
            assertEquals("artifact_submission_closed", runCatching { image.requireAccess() }.exceptionOrNull()?.message)
            assertTrue(store.deleteUserRequested(ConfigurationScope.Personal,
                store.list(ConfigurationScope.Personal).single().id) is ArtifactDeleteResult.Completed)
        }
    }

    @Test
    fun submissionPublishedPreviewRechecksItemAfterReadAndPreservesCancellation() = runBlocking {
        val entity = register("published-preview.png")
        val sessions = net.weero.measix.pilot.data.enterprise.EnterpriseSessionController(
            net.weero.measix.pilot.data.enterprise.EnterpriseAppliedStore(File(root, "published-preview-session")), net.weero.measix.pilot.data.enterprise.EnterpriseDataResetStore(java.io.File(System.getProperty("java.io.tmpdir"), "enterprise-reset-${java.util.UUID.randomUUID()}")))
        sessions.recover()
        val view = net.weero.measix.pilot.service.ConversationViewLease(kotlin.uuid.Uuid.random(),
            net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, sessions.selectionRevision.value, closeAction = {})
        val draft = net.weero.measix.pilot.service.ArtifactUseCase(store,
            net.weero.measix.pilot.service.ApplicationRecoveryGate().apply { ready() }, sessions).openDraftScope(view)
        val part = me.rerere.ai.ui.UIMessagePart.Image(android.net.Uri.fromFile(File(root, entity.relativePath)).toString())
        val first = draft.claimSubmission(view.commandTarget, listOf(part))
        val second = draft.claimSubmission(view.commandTarget, listOf(part))
        draft.close()
        view.close()
        var checks = 0
        var failAt = Int.MAX_VALUE
        val source = requireNotNull(first.describeInputs(listOf(part)) {
            checks++
            check(checks != failAt) { "queue_item_replaced_after_read" }
        }[part.url]?.image)
        val other = requireNotNull(second.describeInputs(listOf(part)) {}[part.url]?.image)
        assertFalse(source.cacheKey == other.cacheKey)
        assertArrayEquals(png, source.readBytes())
        failAt = checks + 2
        assertEquals("queue_item_replaced_after_read", runCatching { source.readBytes() }.exceptionOrNull()?.message)
        val cancelled = kotlinx.coroutines.CancellationException("preview cancelled")
        val cancellation = runCatching { first.describeInputs(listOf(part)) { throw cancelled } }.exceptionOrNull()
        assertTrue(cancellation === cancelled)
        first.close()
        assertArrayEquals(png, other.readBytes())
        second.close()
    }

    @Test
    fun replacingARequestWithTheSameInputKeepsAnIndependentRetentionPin() = runBlocking {
        val draft = net.weero.measix.pilot.service.ArtifactUseCase(store,
            net.weero.measix.pilot.service.ApplicationRecoveryGate().apply { ready() },
            net.weero.measix.pilot.data.enterprise.EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.EnterpriseAppliedStore(File(root, "draft-session")), net.weero.measix.pilot.data.enterprise.EnterpriseDataResetStore(java.io.File(System.getProperty("java.io.tmpdir"), "enterprise-reset-${java.util.UUID.randomUUID()}")))).openDraftScope(net.weero.measix.pilot.service.ConversationViewLease(
            kotlin.uuid.Uuid.random(), net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, 0L, closeAction = {}))
        val document = draft.createTextDocument("shared input")
        val first = draft.claimSubmission(draft.target, listOf(document))
        val second = draft.claimSubmission(draft.target, listOf(document))
        draft.close()
        val artifact = store.list(ConfigurationScope.Personal).single()
        assertTrue(store.deleteUserRequested(ConfigurationScope.Personal, artifact.id) is ArtifactDeleteResult.Rejected)
        first.close()
        assertTrue(store.deleteUserRequested(ConfigurationScope.Personal, artifact.id) is ArtifactDeleteResult.Rejected)
        second.close()
        assertTrue(store.deleteUserRequested(ConfigurationScope.Personal, artifact.id) is ArtifactDeleteResult.Completed)
    }

    @Test
    fun unacceptedSubmissionReturnsToEditorOrReleasesWhenEditorAlreadyClosed() = runBlocking {
        for (closed in listOf(false, true)) {
            val draft = net.weero.measix.pilot.service.ArtifactUseCase(store,
                net.weero.measix.pilot.service.ApplicationRecoveryGate().apply { ready() },
            net.weero.measix.pilot.data.enterprise.EnterpriseSessionController(net.weero.measix.pilot.data.enterprise.EnterpriseAppliedStore(File(root, "draft-session")), net.weero.measix.pilot.data.enterprise.EnterpriseDataResetStore(java.io.File(System.getProperty("java.io.tmpdir"), "enterprise-reset-${java.util.UUID.randomUUID()}")))).openDraftScope(net.weero.measix.pilot.service.ConversationViewLease(
            kotlin.uuid.Uuid.random(), net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, 0L, closeAction = {}))
            val document = draft.createTextDocument("unaccepted input")
            val submission = draft.claimSubmission(draft.target, listOf(document))
            val artifact = store.list(ConfigurationScope.Personal).single()
            if (closed) draft.close()
            draft.returnToDraft(submission)
            if (!closed) {
                assertTrue(store.deleteUserRequested(ConfigurationScope.Personal, artifact.id) is ArtifactDeleteResult.Rejected)
                draft.close()
            }
            assertTrue(store.deleteUserRequested(ConfigurationScope.Personal, artifact.id) is ArtifactDeleteResult.Completed)
        }
    }

    @Test
    fun oldLongPathWorksWithoutConversationAndInspectionOwnsSnapshotAfterDeletion() = runBlocking {
        val entity = register("809278de-6677-4bc1-9249-d94c85b0930c.png")
        val path = "/${entity.relativePath}"
        val resolver = AttachmentResolver(store)

        val result = resolver.readImages(ConfigurationScope.Personal, listOf(path, path)) as AttachmentResolveResult.Success

        assertEquals(2, result.parts.size)
        assertEquals(result.parts[0].url, result.parts[1].url)
        assertFalse(database.artifactReferenceDao().existsByArtifactId(entity.id))
        assertEquals(listOf(entity.relativePath), store.list(ConfigurationScope.Personal).map { it.relativePath })
        assertTrue(store.deleteUserRequested(ConfigurationScope.Personal, entity.id) is ArtifactDeleteResult.Completed)
        result.parts.forEach { part ->
            assertTrue(part.url.startsWith("data:image/webp;base64,"))
            val bytes = java.util.Base64.getDecoder().decode(part.url.substringAfter("base64,"))
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
            assertEquals(1, bitmap.width)
            assertEquals(1, bitmap.height)
            bitmap.recycle()
        }
        assertTrue(store.list(ConfigurationScope.Personal).isEmpty())
        assertTrue(File(root, "upload").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun readScopeBlocksDeleteAndCancellationReleasesPin() = runBlocking {
        withTimeout(10_000) {
            val entity = register("active.png")
            val entered = CompletableDeferred<Unit>()
            val reader = async(Dispatchers.Default) {
                AttachmentResolver(store).withImages(ConfigurationScope.Personal, listOf("/upload/active.png")) { result ->
                    val parts = (result as AttachmentResolveResult.Success).parts
                    assertEquals(store.file(entity).toURI().path, android.net.Uri.parse(parts.single().url).path)
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            try {
                entered.await()
                assertEquals(
                    ArtifactDeleteResult.Rejected(entity.id, ArtifactDeleteResult.RejectionReason.IN_PROGRESS),
                    store.deleteUserRequested(ConfigurationScope.Personal, entity.id),
                )
                assertTrue(store.file(entity).isFile)
            } finally {
                reader.cancelAndJoin()
            }
            assertTrue(store.deleteUserRequested(ConfigurationScope.Personal, entity.id) is ArtifactDeleteResult.Completed)
            assertFalse(store.file(entity).exists())
            assertEquals(
                AttachmentResolveResult.Failure("attachment_not_found"),
                AttachmentResolver(store).readImages(ConfigurationScope.Personal, listOf("/upload/active.png")),
            )
        }
    }

    @Test
    fun failedBatchReleasesPinsAndUnpublishedFileRemainsProducerOwned() = runBlocking {
        val first = register("valid.png")
        val second = register("invalid.png", bytes = byteArrayOf(7, 8, 9))
        val resolver = AttachmentResolver(store)
        assertEquals(
            AttachmentResolveResult.Failure("unsupported_attachment_type"),
            resolver.readImages(ConfigurationScope.Personal, listOf("/upload/valid.png", "/upload/invalid.png")),
        )
        assertTrue(store.deleteUserRequested(ConfigurationScope.Personal, first.id) is ArtifactDeleteResult.Completed)
        assertTrue(store.deleteUserRequested(ConfigurationScope.Personal, second.id) is ArtifactDeleteResult.Completed)
        val owned = store.createFromBytes(ConfigurationScope.Personal, png, "unpublished.png", "image/png", origin = ArtifactOrigin.USER)
        assertEquals(
            AttachmentResolveResult.Failure("attachment_not_found"),
            resolver.readImages(ConfigurationScope.Personal, listOf(owned.localRef.toolPath()!!)),
        )
        assertTrue(store.discardUnpublished(owned) is ArtifactDeleteResult.Completed)
    }

    @Test
    fun registeredSymlinkCannotEscapeUploadRoot() = runBlocking {
        val outside = File(root, "outside.png").apply { writeBytes(png) }
        val link = File(root, "upload/link.png")
        link.parentFile!!.mkdirs()
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        insertRow("link.png", png.size.toLong())
        val result = store.withUploadImages(ConfigurationScope.Personal, listOf("/upload/link.png")) { it }
        assertEquals(ArtifactImageReadResult.Failure(ArtifactImageReadResult.Reason.NOT_FOUND), result)
        assertArrayEquals(png, outside.readBytes())
        assertTrue(link.delete())
    }

    @Test
    fun requestReadAuthorizesOriginalScopeAndProtectsActualProviderEncoding() = runBlocking {
        val personal = register("personal-provider.png")
        val enterprise = ConfigurationScope.Enterprise(
            me.rerere.common.configuration.EnterpriseAuthority("deployment"), "user",
        )
        val resource = register("enterprise-provider.png", scope = enterprise)
        val image = me.rerere.ai.ui.UIMessagePart.Image(
            url = "file://${File(root, resource.relativePath).absolutePath}",
        )
        val messages = listOf(me.rerere.ai.ui.UIMessage(
            role = me.rerere.ai.core.MessageRole.USER, parts = listOf(image),
        ))
        try {
            store.retainForRequest(ConfigurationScope.Personal, messages).close()
            throw AssertionError("cross-scope request accepted")
        } catch (_: ArtifactProjectionException) { }
        store.retainForRequest(enterprise, messages).use { reads ->
            reads.requireAuthorizedFiles(messages)
            assertTrue(store.deleteUserRequested(enterprise, resource.id) is ArtifactDeleteResult.Rejected)
            assertTrue(image.encodeNativeImage().base64.startsWith("data:image/"))
            assertEquals(null, reads.resolveUri("file://${File(root, personal.relativePath).absolutePath}"))
        }
        assertTrue(store.deleteUserRequested(enterprise, resource.id) is ArtifactDeleteResult.Completed)
        assertTrue(File(root, personal.relativePath).isFile)
    }

    private suspend fun register(name: String, bytes: ByteArray = png, scope: ConfigurationScope = ConfigurationScope.Personal): ArtifactEntity {
        File(root, "upload/$name").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        return insertRow(name, bytes.size.toLong(), scope)
    }

    private suspend fun insertRow(name: String, sizeBytes: Long, scope: ConfigurationScope = ConfigurationScope.Personal): ArtifactEntity {
        val entity = ArtifactEntity(
            scope = scope,
            folder = FileFolders.UPLOAD, relativePath = "upload/$name", displayName = name,
            mimeType = "image/png", sizeBytes = sizeBytes, createdAt = 1L, updatedAt = 1L,
            state = ArtifactState.ACTIVE.name, origin = ArtifactOrigin.USER.name,
        )
        return entity.copy(id = database.artifactDao().insert(entity))
    }
}
