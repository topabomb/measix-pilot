package net.weero.measix.pilot.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolInteractionRequirement
import me.rerere.ai.core.ToolMetadataDelivery
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.core.ToolErrorProtocol
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.files.LocalToolPath
import net.weero.measix.pilot.data.files.ToolArtifactRewriter
import net.weero.measix.pilot.service.webdav.WebDavTarget
import net.weero.measix.pilot.service.webdav.WebDavTransferException
import net.weero.measix.pilot.service.webdav.WebDavTransferService

internal data class WebDavTransferArguments(val filePath: String?, val remotePath: String, val overwrite: Boolean)

internal fun parseWebDavArguments(input: JsonElement, upload: Boolean): WebDavTransferArguments {
    val args = input as? JsonObject ?: throw IllegalArgumentException("Arguments must be an object")
    val allowed = if (upload) setOf("file_path", "remote_path", "overwrite") else setOf("remote_path")
    require(args.keys.all { it in allowed }) { "Unexpected file transfer argument" }
    fun text(key: String): String {
        val value = args[key] as? JsonPrimitive
        require(value != null && value.isString) { "$key must be a string" }
        return value.content
    }
    val file = if (upload) text("file_path").also {
        require(LocalToolPath.parseUploadToolPath(it) != null) { "file_path must be an exact disclosed /upload/<file> path" }
    } else null
    val remote = text("remote_path")
    require(remote.isNotEmpty() && remote.none { it == '\\' || it.code < 32 || it.code == 127 } &&
        remote.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
        "remote_path must be a file path relative to the WebDAV root"
    }
    val overwrite = if (!args.containsKey("overwrite")) false else {
        val value = args["overwrite"] as? JsonPrimitive
        require(value != null && !value.isString) { "overwrite must be boolean" }
        requireNotNull(value.booleanOrNull) { "overwrite must be boolean" }
    }
    return WebDavTransferArguments(file, remote, overwrite)
}

internal suspend fun createWebDavTools(access: RealmAccess, configuration: ResolvedConfiguration,
    transfers: WebDavTransferService): List<Tool> {
    val target = if (access == RealmAccess.Personal) {
        configuration.personalWebDavConnections.singleOrNull { it.id == configuration.currentPersonalWebDavConnectionId }?.let(WebDavTarget::Personal)
    } else if (configuration.enterpriseConfiguration?.webdav.orEmpty().none { it.enabled }) null
    else transfers.currentTarget(access, configuration)
    if (target == null) return emptyList()
    return listOf(true, false).map { upload ->
        Tool(
            name = if (upload) "webdav_upload" else "webdav_download",
            description = (if (upload) "Upload one managed attachment of any file type to the current WebDAV. Use its exact disclosed /upload/<file> path. Creates missing remote parent directories. Does not overwrite unless requested."
                else "Download one file of any type from the current WebDAV as a new managed attachment. Returns its /upload/<file> path; each download creates a distinct file.") +
                " No local Workspace is required. Current WebDAV display name: ${JsonPrimitive(target.name)}.",
            parameters = {
                InputSchema.Obj(properties = buildJsonObject {
                    if (upload) {
                        put("file_path", buildJsonObject { put("type", "string"); put("description", "Exact disclosed /upload/<file> path of a managed attachment") })
                        put("overwrite", buildJsonObject { put("type", "boolean"); put("description", "Replace an existing remote file; defaults to false") })
                    }
                    put("remote_path", buildJsonObject { put("type", "string"); put("description", "File path relative to the current WebDAV root") })
                }, required = if (upload) listOf("file_path", "remote_path") else listOf("remote_path"))
            },
            validateArguments = { input ->
                try { parseWebDavArguments(input, upload); null }
                catch (error: IllegalArgumentException) { invalidToolArguments(error.message ?: "Invalid file transfer arguments") }
            },
            interactionRequirement = { ToolInteractionRequirement.None },
            successfulOutputPolicy = { ToolOutputPolicy.PRESERVE },
            execute = { error("WebDAV transfer requires ToolExecutionContext") },
            contextualExecute = { input ->
                val args = parseWebDavArguments(input, upload)
                try {
                    if (upload) {
                        val result = transfers.upload(access, target, requireNotNull(args.filePath), args.remotePath, args.overwrite)
                        listOf(UIMessagePart.Text(buildJsonObject {
                            put("status", "completed"); put("remote_path", args.remotePath)
                            put("bytes", result.bytes); put("overwritten", result.overwritten)
                        }.toString()))
                    } else {
                        val owned = transfers.download(access, target, args.remotePath) { registerUnpublishedResource(transfers.resource(it)) }
                        reportMetadata(buildJsonObject {
                            put(ToolArtifactRewriter.ARTIFACT_KEY, Json.encodeToJsonElement(LocalArtifactRef.serializer(), owned.localRef))
                        }, ToolMetadataDelivery.DEFERRED)
                        val entity = owned.entity
                        val attachment = if (entity.mimeType.startsWith("image/")) UIMessagePart.Image(url = owned.uri.toString())
                            else UIMessagePart.Document(url = owned.uri.toString(), fileName = entity.displayName, mime = entity.mimeType)
                        listOf(UIMessagePart.Text(buildJsonObject {
                            put("status", "completed"); put("remote_path", args.remotePath)
                            put("file", buildJsonObject {
                                put("path", requireNotNull(owned.localRef.toolPath())); put("name", entity.displayName)
                                put("mime_type", entity.mimeType); put("bytes", entity.sizeBytes)
                            })
                        }.toString()), AttachmentRefs.ensureAttachmentRef(attachment))
                    }
                } catch (error: WebDavTransferException) {
                    throw ToolExecutionFailure(listOf(UIMessagePart.Text(ToolErrorProtocol.envelope(
                        if (error.unknown) "unknown" else "failed", error.reason, error.message).toString())), error.reason,
                        error.takeIf { it.cause != null || it.suppressed.isNotEmpty() })
                } catch (error: EnterpriseConfigurationException) {
                    throw ToolExecutionFailure(listOf(UIMessagePart.Text(ToolErrorProtocol.envelope(
                        "failed", "tool_not_permitted", "WebDAV access was revoked: ${error.reason}").toString())),
                        "tool_not_permitted", error.takeIf { it.cause != null || it.suppressed.isNotEmpty() })
                }
            },
        )
    }
}
