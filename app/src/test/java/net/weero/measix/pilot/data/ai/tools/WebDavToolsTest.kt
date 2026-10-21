package net.weero.measix.pilot.data.ai.tools

import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.core.*
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.configuration.WebDavConnection
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException
import net.weero.measix.pilot.data.enterprise.EnterpriseWebDavResource
import net.weero.measix.pilot.data.enterprise.PlatformDAVAccessMode
import net.weero.measix.pilot.data.enterprise.PlatformWebDAVDefinitionAuthOwnership
import net.weero.measix.pilot.data.enterprise.PlatformWebDAVDefinitionClientProtocol
import net.weero.measix.pilot.data.enterprise.PlatformWebDAVDefinitionPurpose
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.service.webdav.*
import net.weero.measix.pilot.service.ApplicationRecoveryGate
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@org.robolectric.annotation.Config(sdk = [34])
class WebDavToolsTest {
    private val connection = WebDavConnection(name = "Current", rootUrl = "https://dav.example/")
    private fun configuration(current: WebDavConnection?) = mockk<ResolvedConfiguration> {
        every { personalWebDavConnections } returns listOf(connection)
        every { currentPersonalWebDavConnectionId } returns current?.id
    }

    @Test fun `tools have no workspace dependency and download only requires remote path`() = runTest {
        val tools = createWebDavTools(RealmAccess.Personal, configuration(connection), mockk())
        assertEquals(listOf("webdav_upload", "webdav_download"), tools.map { it.name })
        val upload = tools.first().parseArguments("""{"file_path":"/upload/file.bin","remote_path":"file","overwrite":true}""", Json)
        val download = tools.last().parseArguments("""{"remote_path":"file"}""", Json)
        assertEquals(ToolInteractionRequirement.None, tools.first().interactionRequirement(upload))
        assertEquals(ToolInteractionRequirement.None, tools.last().interactionRequirement(download))
        val schema = requireNotNull(tools.last().parameters()).jsonObject
        assertEquals(listOf("remote_path"), schema.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        val properties = schema.getValue("properties").jsonObject
        assertEquals(setOf("remote_path", "multimodal"), properties.keys)
        assertFalse(properties.getValue("multimodal").jsonObject.getValue("default").jsonPrimitive.boolean)
        assertTrue(createWebDavTools(RealmAccess.Personal, configuration(null), mockk()).isEmpty())
    }

    @Test fun `validation rejects workspace host paths traversal directories and extra download controls`() = runTest {
        val tools = createWebDavTools(RealmAccess.Personal, configuration(connection), mockk())
        listOf("/workspace/file", "file:///upload/file", "/upload/a/b", "/upload/../file", "/upload/").forEach { path ->
            val input = buildJsonObject { put("file_path", path); put("remote_path", "file") }.toString()
            assertThrows(ToolArgumentsException::class.java) { tools.first().parseArguments(input, Json) }
        }
        for (remote in listOf("../file", "/file", "folder/", "a//b", "a\\b")) {
            val input = buildJsonObject { put("remote_path", remote) }.toString()
            assertThrows(ToolArgumentsException::class.java) { tools.last().parseArguments(input, Json) }
        }
        for (input in listOf(
            """{"remote_path":"file","overwrite":true}""",
            """{"remote_path":"file","local_path":"/workspace/file"}""",
            """{"remote_path":12}""",
            """{"remote_path":"file","multimodal":"true"}""",
            """{"remote_path":"file","multimodal":1}""",
            """{"remote_path":"file","multimodal":null}""",
        )) assertThrows(ToolArgumentsException::class.java) { tools.last().parseArguments(input, Json) }
        assertThrows(ToolArgumentsException::class.java) {
            tools.first().parseArguments("""{"file_path":"/upload/file.bin","remote_path":"file","multimodal":true}""", Json)
        }
        assertFalse(parseWebDavArguments(Json.parseToJsonElement("""{"file_path":"/upload/file.bin","remote_path":"中文 100%.zip"}"""), true).overwrite)
    }

    @Test fun `upload retains captured connection and reports actual remote result`() = runTest {
        var selected = connection
        val configuration = mockk<ResolvedConfiguration> {
            every { personalWebDavConnections } answers { listOf(connection, selected).distinct() }
            every { currentPersonalWebDavConnectionId } answers { selected.id }
        }
        val transfers = mockk<WebDavTransferService>()
        coEvery { transfers.upload(any(), any(), any(), any(), any()) } returns WebDavTransferResult(12L, false)
        val tool = createWebDavTools(RealmAccess.Personal, configuration, transfers).first()
        selected = WebDavConnection(name = "New", rootUrl = "https://other.example/")
        val output = tool.contextualExecute!!.invoke(mockk(), Json.parseToJsonElement("""{"file_path":"/upload/file.bin","remote_path":"file"}"""))
        val result = Json.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("completed", result.getValue("status").jsonPrimitive.content)
        assertEquals("12 B", result.getValue("size").jsonPrimitive.content)
        assertFalse(result.getValue("overwritten").jsonPrimitive.boolean)
        assertTrue(tool.description.contains("Current WebDAV display name: \"Current\""))
        assertFalse(tool.description.contains(connection.rootUrl))
        coVerify { transfers.upload(RealmAccess.Personal, WebDavTarget.Personal(connection), "/upload/file.bin", "file", false) }
    }

    @Test fun `downloads retain attachments and ownership while image content requires opt in`() = runTest {
        for ((mime, multimodal) in listOf("image/png", "application/zip").flatMap { mime ->
            listOf(null, false, true).map { mime to it }
        }) {
            val owned = OwnedArtifact(
                ArtifactEntity(id = 1, folder = "upload", relativePath = "upload/abc123.bin", displayName = "source.bin",
                    mimeType = mime, sizeBytes = 12, createdAt = 1, updatedAt = 1),
                android.net.Uri.parse("file:///managed/upload/abc123.bin"),
                LocalArtifactRef(relativePath = "upload/abc123.bin", mimeType = mime),
            )
            val lease = ToolResourceLease(publish = {}, discard = {})
            val transfers = mockk<WebDavTransferService>()
            coEvery { transfers.download(any(), any(), any(), any()) } coAnswers {
                arg<(OwnedArtifact) -> Unit>(3).invoke(owned); owned
            }
            every { transfers.resource(owned) } returns lease
            var registered = false
            val execution = ToolExecutionContext(
                locator = ToolCallLocator(kotlin.uuid.Uuid.random(), kotlin.uuid.Uuid.random(), kotlin.uuid.Uuid.random()),
                providerCallId = "download",
                reportMetadata = { metadata, delivery ->
                    assertTrue(registered)
                    assertEquals(ToolMetadataDelivery.DEFERRED, delivery)
                    assertEquals(owned.localRef, Json.decodeFromJsonElement(LocalArtifactRef.serializer(), metadata.getValue("artifact")))
                },
                registerUnpublishedResource = { resource -> assertSame(lease, resource); registered = true },
                resolveAttachments = { error("Not an inspection tool") }, reportChildRun = { error("Not a child tool") },
            )
            val tool = createWebDavTools(RealmAccess.Personal, configuration(connection), transfers).last()
            val input = buildJsonObject {
                put("remote_path", "source.bin")
                multimodal?.let { put("multimodal", it) }
            }
            val parsed = tool.parseArguments(input.toString(), Json)
            assertEquals(multimodal == true, parseWebDavArguments(parsed, false).multimodal)
            val output = tool.contextualExecute!!.invoke(execution, parsed)
            val imageIncluded = mime.startsWith("image/") && multimodal == true
            assertNotNull(AttachmentRefs.getStableRef(output.last()))
            assertEquals(imageIncluded, output.last() is UIMessagePart.Image)
            assertEquals(!imageIncluded, output.last() is UIMessagePart.Document)
            val file = Json.parseToJsonElement((output.first() as UIMessagePart.Text).text).jsonObject.getValue("file").jsonObject
            assertEquals("/upload/abc123.bin", file.getValue("path").jsonPrimitive.content)
            assertEquals("12 B", file.getValue("size").jsonPrimitive.content)
            assertEquals(mime, file.getValue("mime_type").jsonPrimitive.content)
        }
    }

    @Test fun `readonly upload has actionable refusal before attachment reads or remote writes and preserves exceptional detail`() = runTest {
        val access = RealmAccess.Enterprise(ConfigurationScope.Enterprise(EnterpriseAuthority("deployment"), "user"), "session")
        val id = "dav_12345678-1234-4234-8234-123456789abc"
        val definition = EnterpriseWebDavResource(id, "Read-only DAV", true,
            PlatformWebDAVDefinitionPurpose.WORKSPACE_FILE_EXCHANGE, PlatformWebDAVDefinitionClientProtocol.WEBDAV,
            PlatformWebDAVDefinitionAuthOwnership.ENTERPRISE_MANAGED, "/runtime/v1/resources/$id/spaces/{agentSpaceId}/dav/")
        val target = WebDavTarget.Enterprise(access, mockk(), definition,
            "spc_12345678-1234-4234-8234-123456789abc", 1L, PlatformDAVAccessMode.READ_ONLY)
        val configuration = mockk<ResolvedConfiguration> {
            every { enterpriseConfiguration } returns mockk { every { webdav } returns listOf(definition) }
        }
        for (exceptionalDetail in listOf(false, true)) {
            val refusal = IllegalStateException("webdav_read_only",
                if (exceptionalDetail) java.io.IOException("binding detail retained") else null).apply {
                if (exceptionalDetail) addSuppressed(java.io.IOException("prior cleanup detail retained"))
            }
            val handle = WebDavHandle(access, null, target)
            val dav = mockk<WebDavApplicationService>(relaxed = true) {
                coEvery { captureToolTarget(access, configuration) } returns target
                coEvery { captureExpected(access, target) } returns handle
                coEvery { validate(handle, writing = true) } throws refusal
                every { unknownPaths(handle) } returns emptySet()
            }
            val artifacts = mockk<ArtifactStore>()
            val transfers = WebDavTransferService(dav, artifacts, ApplicationRecoveryGate().apply { ready() })
            val tool = createWebDavTools(access, configuration, transfers).first()
            val source = UIMessagePart.Tool(localCallId = kotlin.uuid.Uuid.random(), stepId = kotlin.uuid.Uuid.random(),
                providerCallId = "upload", toolName = tool.name,
                input = """{"file_path":"/upload/source.bin","remote_path":"source.bin"}""")
            val runtime = ToolCallRuntime(Json)
            val prepared = runtime.prepareBatch(messageId = kotlin.uuid.Uuid.random(), calls = listOf(LocatedToolCall(0, source)),
                toolIndex = freezeToolSet(listOf(tool)).bindingsByName, availability = TurnInteractionCapability.FULL)
            val outcome = runtime.execute((prepared.resolvedCalls.single() as ResolvedToolCall.Executable).call,
                ToolExecutionHooks(resolveAttachments = { ToolAttachmentResolution() }, reportMetadata = { _, _ -> },
                    reportChildRun = {}, registerUnpublishedResource = { error("Refused upload cannot register resources") }))
            val envelope = Json.parseToJsonElement((outcome.output.single() as UIMessagePart.Text).text).jsonObject
            assertEquals("failed", envelope.getValue("status").jsonPrimitive.content)
            assertEquals("webdav_read_only", envelope.getValue("reason").jsonPrimitive.content)
            assertTrue(envelope.getValue("detail").jsonPrimitive.content.contains("Ask your administrator for write access"))
            assertEquals(me.rerere.ai.ui.ToolResultStatus.FAILED, outcome.resultStatus)
            if (exceptionalDetail) {
                val detail = requireNotNull(outcome.clientDiagnostic).detail
                assertTrue(detail.contains("IOException: binding detail retained"))
                assertTrue(detail.contains("Suppressed: IOException: prior cleanup detail retained"))
            } else assertNull(outcome.clientDiagnostic)
            coVerify(exactly = 0) { artifacts.withUploadFile<WebDavTransferResult>(any(), any(), any()) }
            coVerify(exactly = 0) { dav.stat(any(), any()); dav.mkdir(any(), any()); dav.upload(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { dav.close(handle) }
        }
    }

    @Test fun `revoked download preserves cleanup diagnostic without diagnosing ordinary permission refusal`() = runTest {
        for (cleanupFailed in listOf(false, true)) {
            val failure = EnterpriseConfigurationException("enterprise_session_replaced").apply {
                if (cleanupFailed) addSuppressed(java.io.IOException("managed download rollback failed"))
            }
            val transfers = mockk<WebDavTransferService>()
            coEvery { transfers.download(any(), any(), any(), any()) } throws failure
            val tool = createWebDavTools(RealmAccess.Personal, configuration(connection), transfers).last()
            val source = UIMessagePart.Tool(
                localCallId = kotlin.uuid.Uuid.random(), stepId = kotlin.uuid.Uuid.random(),
                providerCallId = "download", toolName = tool.name, input = """{"remote_path":"source.bin"}""",
            )
            val runtime = ToolCallRuntime(Json)
            val prepared = runtime.prepareBatch(
                messageId = kotlin.uuid.Uuid.random(), calls = listOf(LocatedToolCall(0, source)),
                toolIndex = freezeToolSet(listOf(tool)).bindingsByName, availability = TurnInteractionCapability.FULL,
            )
            val outcome = runtime.execute((prepared.resolvedCalls.single() as ResolvedToolCall.Executable).call,
                ToolExecutionHooks(resolveAttachments = { ToolAttachmentResolution() }, reportMetadata = { _, _ -> },
                    reportChildRun = {}, registerUnpublishedResource = { error("Refused download cannot publish") }))
            val envelope = Json.parseToJsonElement((outcome.output.single() as UIMessagePart.Text).text).jsonObject
            assertEquals("tool_not_permitted", envelope.getValue("reason").jsonPrimitive.content)
            assertEquals(me.rerere.ai.ui.ToolResultStatus.FAILED, outcome.resultStatus)
            if (cleanupFailed) {
                assertTrue(requireNotNull(outcome.clientDiagnostic).detail
                    .contains("Suppressed: IOException: managed download rollback failed"))
            } else assertNull(outcome.clientDiagnostic)
        }
    }
}
