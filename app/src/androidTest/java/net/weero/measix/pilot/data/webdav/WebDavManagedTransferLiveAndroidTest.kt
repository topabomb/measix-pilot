package net.weero.measix.pilot.data.webdav

import android.graphics.Bitmap
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.core.*
import me.rerere.ai.ui.*
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.ai.tools.createWebDavTools
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.ConversationEntity
import net.weero.measix.pilot.data.db.entity.MessageNodeEntity
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.files.*
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.*
import net.weero.measix.pilot.service.webdav.*
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/** Opt-in on an already enrolled dedicated device. Does not alter enrollment or configuration. */
@RunWith(AndroidJUnit4::class)
class WebDavManagedTransferLiveAndroidTest {
    @Test fun managedFilesRoundTripWithoutWorkspace(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("webDavManagedTransfersLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = GlobalContext.get()
        koin.get<ApplicationRecoveryCoordinator>()
        withTimeout(30_000) { koin.get<ApplicationRecoveryGate>().awaitReady() }
        val selection = requireNotNull(koin.get<EnterpriseSessionController>().readPresentation().selection)
        val access = selection.access
        check(access is RealmAccess.Enterprise) { "Requires an enrolled enterprise test device" }
        val configuration = koin.get<ConfigurationQueryService>().read(access)
        val dav = koin.get<WebDavApplicationService>()
        val store = koin.get<ArtifactStore>()
        val database = koin.get<AppDatabase>()
        val tools = createWebDavTools(access, configuration, koin.get<WebDavTransferService>()).associateBy { it.name }
        assertEquals(setOf("webdav_upload", "webdav_download"), tools.keys)
        val conversationId = Uuid.random()
        val folder = "android-managed-dav-$conversationId"
        val handle = dav.open(selection)
        val resources = mutableSetOf<ToolResourceLease>()
        val artifactIds = mutableSetOf<Long>()
        val nodes = mutableListOf<MessageNode>()
        var createdRemote = false
        var failure: Throwable? = null
        val evidence = buildJsonObject {
            put("workspaceRequired", false)
            put("realCoreAndDav", true)
            put("modelInvocation", false)
            put("designatedResource", (handle.target as WebDavTarget.Enterprise).id)
        }.toMutableMap()
        suspend fun commitFixture(message: UIMessage, lease: ToolResourceLease) {
            // A scoped test root uses the same Artifact reference transaction and publication contract.
            val node = MessageNode.of(message)
            val delta = store.prepareReferenceDelta(access.scope, listOf(node), emptyList())
            database.withTransaction {
                database.messageNodeDao().insertAll(listOf(MessageNodeEntity(node.id.toString(), conversationId.toString(),
                    nodes.size, JsonInstant.encodeToString(node.messages), 0)))
                store.applyReferenceDeltaInTransaction(delta)
            }
            nodes += node
            lease.publish()
            resources -= lease
        }
        suspend fun invoke(name: String, args: JsonObject): UIMessagePart.Tool {
            val tool = requireNotNull(tools[name])
            val locator = ToolCallLocator(Uuid.random(), Uuid.random(), Uuid.random())
            var metadata: JsonObject? = null
            var resource: ToolResourceLease? = null
            val execution = ToolExecutionContext(locator, "live-${locator.localCallId}",
                reportMetadata = { value, delivery ->
                    assertEquals(ToolMetadataDelivery.DEFERRED, delivery)
                    metadata = value
                }, resolveAttachments = { error("Not needed") }, reportChildRun = { error("Not needed") },
                registerUnpublishedResource = { lease -> check(resource == null); resource = lease; resources += lease },
                approvedByUser = false)
            val output = tool.executeWithContext(execution, args)
            val part = UIMessagePart.Tool(localCallId = locator.localCallId, stepId = locator.stepId,
                providerCallId = execution.providerCallId, toolName = name, input = args.toString(), output = output,
                metadata = metadata, resultStatus = ToolResultStatus.COMPLETED)
            metadata?.let { value ->
                val ref = JsonInstant.decodeFromJsonElement<LocalArtifactRef>(requireNotNull(value[ToolArtifactRewriter.ARTIFACT_KEY]))
                artifactIds += requireNotNull(store.getByRelativePath(ref.relativePath)).id
                assertNull(store.withUploadFile(access.scope, requireNotNull(ref.toolPath())) { _, _ -> true })
                commitFixture(UIMessage(id = locator.assistantMessageId, role = MessageRole.ASSISTANT, parts = listOf(part)),
                    requireNotNull(resource))
            }
            return part
        }
        fun arguments(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { put(it.first, it.second) } }
        fun downloadedPath(part: UIMessagePart.Tool): String = Json.parseToJsonElement(
            (part.output.first() as UIMessagePart.Text).text).jsonObject["file"]!!.jsonObject["path"]!!.jsonPrimitive.content
        try {
            withTimeout(180_000) {
                database.conversationDao().insert(ConversationEntity(conversationId.toString(), ConfigurationReference.random().toString(),
                    "DAV isolated live fixture", System.currentTimeMillis(), System.currentTimeMillis(), "[]", false, scope = access.scope))
                dav.mkdir(handle, folder)
                createdRemote = true
                val png = ByteArrayOutputStream().apply {
                    val bitmap = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
                    try { bitmap.eraseColor(android.graphics.Color.BLUE); assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)) }
                    finally { bitmap.recycle() }
                }.toByteArray()
                val cases = listOf(Triple("任意文件 #%.bin", "application/octet-stream", ByteArray(1024 * 1024 + 17) { (it * 31).toByte() }),
                    Triple("image.png", "image/png", png), Triple("note.txt", "text/plain", "DAV text remains a file reference".toByteArray()))
                for ((name, mime, bytes) in cases) {
                    val source = store.createFromBytes(access.scope, bytes, name, mime, origin = ArtifactOrigin.USER)
                    artifactIds += source.entity.id
                    val sourceLease = store.unpublishedLease(source).also { resources += it }
                    val attachment = AttachmentRefs.ensureAttachmentRef(if (mime.startsWith("image/")) UIMessagePart.Image(source.uri.toString())
                        else UIMessagePart.Document(source.uri.toString(), name, mime))
                    commitFixture(UIMessage(role = MessageRole.USER, parts = listOf(attachment)), sourceLease)
                    val remote = "$folder/$name"
                    invoke("webdav_upload", arguments("file_path" to requireNotNull(source.localRef.toolPath()), "remote_path" to remote))
                    try {
                        invoke("webdav_upload", arguments("file_path" to requireNotNull(source.localRef.toolPath()), "remote_path" to remote))
                        fail("Default upload must refuse overwrite")
                    } catch (expected: ToolExecutionFailure) { assertEquals("webdav_file_exists", expected.message) }
                    val first = invoke("webdav_download", arguments("remote_path" to remote))
                    val second = invoke("webdav_download", buildJsonObject {
                        put("remote_path", remote)
                        put("multimodal", true)
                    })
                    val firstPath = downloadedPath(first)
                    assertNotEquals(firstPath, downloadedPath(second))
                    assertTrue(firstPath.startsWith("/upload/"))
                    assertFalse(first.output.any { it is UIMessagePart.Image })
                    assertTrue(first.output.any { it is UIMessagePart.Document })
                    assertEquals(mime.startsWith("image/"), second.output.any { it is UIMessagePart.Image })
                    assertEquals(!mime.startsWith("image/"), second.output.any { it is UIMessagePart.Document })
                    val actual = store.withUploadFile(access.scope, firstPath) { input, length ->
                        assertEquals(bytes.size.toLong(), length)
                        input.readBytes()
                    }
                    assertArrayEquals(bytes, requireNotNull(actual))
                    invoke("webdav_upload", arguments("file_path" to firstPath, "remote_path" to "$folder/copied-$name"))
                    evidence[name] = JsonPrimitive(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                    store.retainForRequest(access.scope, listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(first)))).use { reads ->
                        val output = koin.get<ToolArtifactRewriter>().materializeToolOutput(reads, first.output, first.metadata)
                        assertEquals(firstPath, downloadedPath(first.copy(output = output)))
                    }
                }
                evidence["distinctDownloads"] = JsonPrimitive(true)
                evidence["commitBeforePublish"] = JsonPrimitive(true)
                evidence["publishedDownloadCanUploadAgain"] = JsonPrimitive(true)
            }
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            withContext(NonCancellable) {
                suspend fun cleanup(block: suspend () -> Unit) {
                    try { block() } catch (error: Throwable) {
                        if (failure == null) failure = error else failure!!.addSuppressed(error)
                    }
                }
                cleanup { database.conversationDao().deleteById(conversationId.toString()) }
                resources.forEach { lease -> cleanup { lease.discard() } }
                artifactIds.forEach { id -> cleanup {
                    val result = store.deleteUserRequested(access.scope, id)
                    check(result is ArtifactDeleteResult.Completed || result is ArtifactDeleteResult.Rejected &&
                        result.reason == ArtifactDeleteResult.RejectionReason.ALREADY_DELETED) { "Local fixture cleanup failed: $result" }
                } }
                if (createdRemote) cleanup { dav.mutate(handle, folder, "DELETE", directory = true) }
                cleanup { dav.close(handle) }
                evidence["cleanedUp"] = JsonPrimitive(failure == null)
                File(context.cacheDir, "webdav-managed-live-result.json").writeText(JsonObject(evidence).toString())
            }
        }
        failure?.let { throw it }
    }
}
