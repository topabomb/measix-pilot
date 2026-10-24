@file:Suppress("UNNECESSARY_SAFE_CALL")
package me.rerere.ai.provider.providers

import me.rerere.ai.provider.forCredentials
import me.rerere.common.configuration.ConfigurationReference
import me.rerere.ai.provider.ProviderResponseException
import me.rerere.ai.util.ProviderTerminalStatus
import me.rerere.ai.util.HttpException
import me.rerere.ai.ui.ProviderToolCallSlot
import me.rerere.ai.ui.ProviderContentBlockSlot
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ProviderUsageSnapshot
import me.rerere.ai.core.sumTokenCountsOrNull
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestImageSupport
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.ClaudeReasoningMetadata
import me.rerere.ai.ui.ClaudeResponseMetadata
import me.rerere.ai.ui.ClaudeReplayState
import me.rerere.ai.ui.ClaudeContentBlockMetadata
import me.rerere.ai.ui.ClaudeTextMetadata
import me.rerere.ai.ui.ServerToolSource
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ProviderContinuation
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.ClaudeResponseBlock
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.RequestBodyOwnership
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.configureSessionHeader
import me.rerere.ai.util.encodeNativeImage
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.stringSafe
import me.rerere.ai.provider.authenticate
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.readResponse
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "ClaudeProvider"
private const val ANTHROPIC_VERSION = "2023-06-01"
private const val CLAUDE_MIN_MANUAL_THINKING_BUDGET = 1_024

/**
 * Builds the model-compatible Anthropic thinking fields.
 *
 * Claude 4.6+ uses adaptive thinking and `output_config.effort`. Older reasoning models use the
 * manual `enabled + budget_tokens` form. AUTO omits thinking for those older models because they
 * have no adaptive mode; inventing a fixed budget would not preserve AUTO semantics.
 */
internal fun buildClaudeThinkingFields(
    modelId: String,
    level: ReasoningLevel,
    maxTokens: Int,
): JsonObject = buildJsonObject {
    if (level == ReasoningLevel.OFF) {
        require(!ModelRegistry.CLAUDE_OPUS_5_5.match(modelId)) { "unsupported_reasoning_mode: Claude Opus 5.5 requires adaptive thinking" }
        put("thinking", buildJsonObject {
            put("type", if (ModelRegistry.CLAUDE_SONNET_5_5.match(modelId)) "between_tools" else "disabled")
        })
        return@buildJsonObject
    }

    if (ModelRegistry.CLAUDE_ADAPTIVE_THINKING.match(modelId)) {
        put("thinking", buildJsonObject {
            put("type", "adaptive")
            put("display", "summarized")
        })
        if (level != ReasoningLevel.AUTO) {
            val effort = when {
                level == ReasoningLevel.MAX -> "max"
                level == ReasoningLevel.XHIGH && !ModelRegistry.CLAUDE_XHIGH_EFFORT.match(modelId) -> "max"
                else -> level.effort
            }
            put("output_config", buildJsonObject { put("effort", effort) })
        }
        return@buildJsonObject
    }

    if (level == ReasoningLevel.AUTO) return@buildJsonObject

    require(maxTokens > CLAUDE_MIN_MANUAL_THINKING_BUDGET) {
        "Claude manual thinking requires maxTokens greater than $CLAUDE_MIN_MANUAL_THINKING_BUDGET"
    }
    put("thinking", buildJsonObject {
        put("type", "enabled")
        put(
            "budget_tokens",
            level.budgetTokens
                .coerceAtLeast(CLAUDE_MIN_MANUAL_THINKING_BUDGET)
                .coerceAtMost(maxTokens - 1)
        )
        put("display", "summarized")
    })
    if (ModelRegistry.CLAUDE_MANUAL_THINKING_WITH_EFFORT.match(modelId)) {
        val effort = if (level == ReasoningLevel.XHIGH || level == ReasoningLevel.MAX) {
            ReasoningLevel.HIGH.effort
        } else {
            level.effort
        }
        put("output_config", buildJsonObject { put("effort", effort) })
    }
}

class ClaudeProvider(private val client: OkHttpClient, context: Context? = null) : Provider<ProviderSetting.Claude> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    override fun requestMediaCapabilities(
        providerSetting: ProviderSetting.Claude,
        model: Model,
    ): RequestMediaCapabilities {
        val structured = if (Modality.IMAGE in model.inputModalities) {
            RequestImageSupport.STRUCTURED
        } else {
            RequestImageSupport.NONE
        }
        return RequestMediaCapabilities(
            userImages = structured,
            assistantImages = RequestImageSupport.NONE,
            toolOutputImages = structured,
        )
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Claude): List<Model> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models")
                .headers(providerSetting.customHeaders.toHeaders())
                .header("x-api-key", keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString()))
                .header("anthropic-version", ANTHROPIC_VERSION)
                .get()
                .build()

            val bodyStr = client.newCall(request).readResponse { response ->
                if (!response.isSuccessful) {
                    error("Failed to get models: ${response.code} ${response.body?.string()}")
                }
                response.body?.string() ?: ""
            }
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            val data = bodyJson["data"]?.jsonArray ?: return@withContext emptyList()

            data.mapNotNull { modelJson ->
                val modelObj = modelJson.jsonObject
                val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val displayName = modelObj["display_name"]?.jsonPrimitive?.contentOrNull ?: id

                Model(
                    modelId = id,
                    displayName = displayName,
                )
            }
        }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> {
        error("Claude provider does not support image generation")
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Claude,
        messages: List<ModelRequestMessage>,
        params: TextGenerationParams
    ): MessageChunk = withContext(Dispatchers.IO) {
        val prepared = buildMessageRequest(providerSetting, messages, params)
        val requestBody = prepared.body
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/messages")
            .headers((providerSetting.customHeaders + params.customHeaders).toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .authenticate(params.credentials, "x-api-key", userKeys = providerSetting.apiKey, providerId = providerSetting.id.toString(), roulette = keyRoulette)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .configureReferHeaders(providerSetting.baseUrl)
            .configureSessionHeader(params.openCodeSessionId ?: params.providerSessionId)
            .build()

        val (bodyStr, responseEndpoint) = client.forCredentials(params.credentials).newCall(request).readResponse { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to get response: ${response.code} ${response.body?.string()}")
            }
            (response.body?.string() ?: "") to claudeUrlIdentity(response.request.url)
        }
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        // 从 JsonObject 中提取必要的信息
        val id = bodyJson["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: ""
        val content = bodyJson["content"]?.jsonArray ?: JsonArray(emptyList())
        val stopReason = bodyJson["stop_reason"]?.jsonPrimitive?.contentOrNull
        val completionError = claudeStopReasonError(stopReason)
        val usage = parseTokenUsage(bodyJson)

        val responseId = Uuid.random().toString()
        val parsed = parseMessage(content, ServerToolSource(ServerToolProtocol.CLAUDE_MESSAGES, responseEndpoint), completionError == null)
        val responsePositions = content.indices.filter { content[it].jsonObject["type"]?.jsonPrimitive?.content in
            setOf("text", "thinking", "redacted_thinking", "tool_use", "server_tool_use", "web_search_tool_result") }
        require(parsed.parts.size == responsePositions.size) { "Malformed Claude response block" }
        var reasoningIndex = 0
        val reasoningPositions = content.indices.filter { content[it].jsonObject["type"]?.jsonPrimitive?.content in setOf("thinking", "redacted_thinking") }
        val completed = parsed.copy(providerMetadata = if (completionError == null) {
            ClaudeResponseMetadata(ClaudeReplayState(retiredDigests = prepared.retiredDigests)).toMetadata()
        } else null, parts = parsed.parts.mapIndexed { index, original ->
            val part = original.withClaudeBlock(responseId, responsePositions[index])
            if (part !is UIMessagePart.Reasoning || completionError != null) part else {
                val evidence = claudeReplayEvidence(requestBody, responseEndpoint, content.map { it.jsonObject }, reasoningPositions[reasoningIndex++])
                    .copy(model = model)
                part.copy(metadata = JsonObject(part.metadata.orEmpty() + ClaudeReasoningMetadata(claudeReplay = evidence).toMetadata()))
            }
        })
        val chunk = MessageChunk(
            id = id,
            model = model,
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = null,
                    message = completed,
                    finishReason = stopReason,
                    contentBlockSlots = responsePositions.map { ProviderContentBlockSlot(responseId, it) },
                    continuation = if (completionError == null && stopReason == "pause_turn") ProviderContinuation.CONTINUE_ASSISTANT else null,
                )
            ),
            usage = usage
        )
        completionError?.let { throw ProviderResponseException(chunk, it) }
        chunk
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Claude,
        messages: List<ModelRequestMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = callbackFlow {
        val prepared = buildMessageRequest(providerSetting, messages, params, stream = true)
        val requestBody = prepared.body
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/messages")
            .headers((providerSetting.customHeaders + params.customHeaders).toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .authenticate(params.credentials, "x-api-key", userKeys = providerSetting.apiKey, providerId = providerSetting.id.toString(), roulette = keyRoulette)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .configureSessionHeader(params.openCodeSessionId ?: params.providerSessionId)
            .build()

        val streamState = ClaudeStreamState(requestBody, claudeEndpointIdentity(providerSetting.baseUrl, params.credentials), prepared.retiredDigests)
        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                streamState.responseEndpoint = claudeUrlIdentity(response.request.url)
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                if (data == "[DONE]") {
                    return
                }

                try {
                    val dataJson = json.parseToJsonElement(data).jsonObject
                    val eventType = dataJson["type"]?.jsonPrimitive?.contentOrNull ?: type
                    val chunk = parseStreamEvent(dataJson, streamState)
                    trySend(chunk).onFailure { Log.w(TAG, "onEvent: chunk dropped") }
                    when (eventType) {
                        "message_stop" -> close(streamState.completionError())
                        "error" -> close(dataJson["error"]?.parseErrorDetail()
                            ?: HttpException("Claude stream error without detail"))
                    }
                } catch (error: Throwable) {
                    close(error)
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                var exception = t

                Log.e(TAG, "onFailure: ${t?.javaClass?.name} (http=${response?.code})")

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        exception = bodyElement.parseErrorDetail()
                    }
                } catch (_: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse error response")
                } finally {
                    close(exception ?: streamState.completionError() ?: HttpException("Claude stream transport failed"))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close(streamState.completionError())
            }
        }

        ensureActive()
        val eventSource = EventSources.createFactory(client.forCredentials(params.credentials))
            .newEventSource(request, listener)

        awaitClose {
            Log.d(TAG, "Closing eventSource")
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    private fun buildMessageRequest(
        providerSetting: ProviderSetting.Claude,
        messages: List<ModelRequestMessage>,
        params: TextGenerationParams,
        stream: Boolean = false
    ): ClaudePreparedRequest {
        val maxTokens = params.maxTokens ?: 64_000
        val thinkingFields = if (params.model.abilities.contains(ModelAbility.REASONING) || ModelRegistry.CLAUDE_5_5.match(params.model.modelId)) {
            buildClaudeThinkingFields(params.model.modelId, params.reasoningLevel, maxTokens)
        } else {
            JsonObject(emptyMap())
        }
        val thinkingType = thinkingFields["thinking"]
            ?.jsonObject
            ?.get("type")
            ?.jsonPrimitive
            ?.content
        val thinkingEnabled = thinkingType == "adaptive" || thinkingType == "enabled"
        return buildJsonObject {
            put("model", params.model.modelId)
            put(
                "messages",
                buildMessages(
                    stripClaudeServerState(
                        if (ModelRegistry.CLAUDE_5_5.match(params.model.modelId)) messages
                        else stripClaudeThinkingFromOtherModels(messages, params.model.id),
                        claudeEndpointIdentity(providerSetting.baseUrl, params.credentials),
                    ),
                    providerSetting.promptCaching,
                    providerSetting.promptCacheTtl,
                    params.mediaCapabilities,
                )
            )
            put("max_tokens", maxTokens)

            // 顶层 cache_control: 让 Anthropic 自动管理缓存断点
            if (providerSetting.promptCaching) {
                put("cache_control", cacheControlEphemeral(providerSetting.promptCacheTtl))
            }

            if (params.temperature != null && (!thinkingEnabled || ModelRegistry.CLAUDE_5_5.match(params.model.modelId))) put(
                "temperature",
                params.temperature
            )
            if (params.topP != null) put("top_p", params.topP)

            put("stream", stream)

            // system prompt
            val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
            val systemTextParts = systemMessage?.parts?.filterIsInstance<UIMessagePart.Text>().orEmpty()
            if (systemTextParts.isNotEmpty()) {
                put("system", buildJsonArray {
                    systemTextParts.forEachIndexed { index, part ->
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", part.text)
                            if (providerSetting.promptCaching && index == systemTextParts.lastIndex) {
                                put("cache_control", cacheControlEphemeral(providerSetting.promptCacheTtl))
                            }
                        })
                    }
                })
            }

            thinkingFields.forEach { (key, value) -> put(key, value) }

            // 处理工具
            val clientTools = if (params.model.abilities.contains(ModelAbility.TOOL)) params.tools else emptyList()
            val search = me.rerere.ai.provider.BuiltInTools.Search in params.model.tools
            if (clientTools.isNotEmpty() || search) {
                putJsonArray("tools") {
                    if (search) add(buildJsonObject {
                        put("type", "web_search_20250305")
                        put("name", "web_search")
                    })
                    clientTools.forEachIndexed { index, tool ->
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            tool.parameters?.let { put("input_schema", it) }
                            if (providerSetting.promptCaching && index == clientTools.lastIndex) {
                                put("cache_control", cacheControlEphemeral(providerSetting.promptCacheTtl))
                            }
                        })
                    }
                }
            }
        }.mergeCustomBody(
            params.customBody,
            CLAUDE_MESSAGES_OWNERSHIP,
        ).let(::validateClaudeRequestParameters).let { body ->
            val retired = messages.flatMap { it.metadataAs<ClaudeResponseMetadata>()?.claudeReplayState
                ?.takeIf { state -> state.version == 1 }?.retiredDigests.orEmpty() }.toSet()
            filterClaudeThinking(body, claudeEndpointIdentity(providerSetting.baseUrl, params.credentials), retired)
        }
    }

    private fun cacheControlEphemeral(promptCacheTtl: ClaudePromptCacheTtl) = buildJsonObject {
        put("type", "ephemeral")
        promptCacheTtl.apiValue?.let { put("ttl", it) }
    }

    private fun buildMessages(
        messages: List<ModelRequestMessage>,
        promptCaching: Boolean,
        promptCacheTtl: ClaudePromptCacheTtl,
        mediaCapabilities: RequestMediaCapabilities,
    ) = buildJsonArray {
        messages
            .filter { it.isValidToUpload() && it.role != MessageRole.SYSTEM }
            .forEach { message ->
                if (message.role == MessageRole.ASSISTANT) {
                    addAssistantMessage(message, mediaCapabilities)
                } else {
                    addUserMessage(message, mediaCapabilities)
                }
            }
    }.let { messagesArray ->
        if (!promptCaching) return@let messagesArray
        insertMessagesCacheControl(messagesArray, promptCacheTtl)
    }

    /**
     * 在倒数第二条非 tool_result 的 user message 的最后一个 content block 上插入 cache_control
     */
    private fun insertMessagesCacheControl(
        messages: JsonArray,
        promptCacheTtl: ClaudePromptCacheTtl
    ): JsonArray {
        // 找出所有非 tool_result 的 user message 的索引
        val realUserIndices = messages.mapIndexedNotNull { index, msg ->
            val obj = msg.jsonObject
            if (obj["role"]?.jsonPrimitive?.contentOrNull == "user") {
                val content = obj["content"]?.jsonArray
                val isToolResult = content?.any {
                    it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "tool_result"
                } == true
                if (!isToolResult) index else null
            } else null
        }

        // 取倒数第二条
        val targetIndex = if (realUserIndices.size >= 2) {
            realUserIndices[realUserIndices.size - 2]
        } else return messages

        // 在目标 message 的最后一个 content block 上添加 cache_control
        return JsonArray(messages.mapIndexed { index, msg ->
            if (index == targetIndex) {
                val obj = msg.jsonObject
                val content = obj["content"]?.jsonArray ?: return@mapIndexed msg
                val newContent = JsonArray(content.mapIndexed { contentIndex, block ->
                    if (contentIndex == content.lastIndex) {
                        JsonObject(
                            block.jsonObject + mapOf("cache_control" to cacheControlEphemeral(promptCacheTtl))
                        )
                    } else block
                })
                JsonObject(obj + mapOf("content" to newContent))
            } else msg
        })
    }

    private fun JsonArrayBuilder.addAssistantMessage(
        message: ModelRequestMessage,
        mediaCapabilities: RequestMediaCapabilities,
    ) {
        val segments = mutableListOf<MutableList<UIMessagePart>>()
        message.parts.forEach { part ->
            val group = part.metadataAs<ClaudeContentBlockMetadata>()?.claudeBlock?.responseId
            val previous = segments.lastOrNull()?.lastOrNull()?.metadataAs<ClaudeContentBlockMetadata>()?.claudeBlock?.responseId
            if (segments.isEmpty() || group != previous) segments += mutableListOf<UIMessagePart>()
            segments.last() += part
        }
        segments.forEach { parts ->
            if (parts.first().metadataAs<ClaudeContentBlockMetadata>() != null) {
                val tools = parts.filterIsInstance<UIMessagePart.Tool>().filter { it.hasReplayResult }
                val content = parts.mapNotNull { part ->
                    if (part is UIMessagePart.Tool) part.takeIf { it.hasReplayResult }?.toToolUseBlock()
                    else part.toContentBlock(mediaCapabilities.assistantImages)
                }
                if (content.isNotEmpty()) add(buildJsonObject {
                    put("role", "assistant"); put("content", JsonArray(content))
                })
                if (tools.isNotEmpty()) add(buildJsonObject {
                    put("role", "user"); put("content", JsonArray(tools.map { it.toToolResultBlock(mediaCapabilities) }))
                })
            } else addLegacyAssistantParts(parts, mediaCapabilities)
        }
    }

    private fun JsonArrayBuilder.addLegacyAssistantParts(parts: List<UIMessagePart>, mediaCapabilities: RequestMediaCapabilities) {
        val groups = groupPartsByToolBoundary(parts)
        val contentBuffer = mutableListOf<JsonObject>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.mapNotNull {
                        it.toContentBlock(mediaCapabilities.assistantImages)
                    }.forEach { contentBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 添加 tool_use 到内容缓冲
                    group.tools.forEach { contentBuffer.add(it.toToolUseBlock()) }

                    // 输出 assistant 消息
                    add(buildJsonObject {
                        put("role", "assistant")
                        putJsonArray("content") { contentBuffer.forEach { add(it) } }
                    })
                    contentBuffer.clear()

                    // 紧跟 tool_result
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            group.tools.forEach { add(it.toToolResultBlock(mediaCapabilities)) }
                        }
                    })
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty()) {
            add(buildJsonObject {
                put("role", "assistant")
                putJsonArray("content") { contentBuffer.forEach { add(it) } }
            })
        }
    }

    private fun JsonArrayBuilder.addUserMessage(
        message: ModelRequestMessage,
        mediaCapabilities: RequestMediaCapabilities,
    ) {
        add(buildJsonObject {
            put("role", message.role.name.lowercase())
            putJsonArray("content") {
                message.parts.mapNotNull {
                    it.toContentBlock(mediaCapabilities.userImages)
                }.forEach { add(it) }
            }
        })
    }

    private fun UIMessagePart.toContentBlock(imageSupport: RequestImageSupport): JsonObject? = when (this) {
        is UIMessagePart.Text -> buildJsonObject {
            put("type", "text")
            put("text", text)
            metadataAs<ClaudeTextMetadata>()?.claudeCitations?.let { put("citations", it) }
        }

        is UIMessagePart.ServerTool -> block.takeIf { complete && source.protocol == ServerToolProtocol.CLAUDE_MESSAGES }

        is UIMessagePart.Image -> {
            check(imageSupport == RequestImageSupport.STRUCTURED) {
                "Claude received a native image after request projection"
            }
            val encoded = encodeNativeImage(withPrefix = false)
            buildJsonObject {
                put("type", "image")
                put("source", buildJsonObject {
                    put("type", "base64")
                    put("media_type", encoded.mimeType)
                    put("data", encoded.base64)
                })
            }
        }

        is UIMessagePart.Reasoning -> {
            val reasoningMetadata = metadataAs<ClaudeReasoningMetadata>()
            reasoningMetadata?.redactedData?.let { redactedData ->
                buildJsonObject {
                    put("type", "redacted_thinking")
                    put("data", redactedData)
                    reasoningMetadata.claudeReplay?.let { put(CLAUDE_REPLAY_ANNOTATION, json.encodeToJsonElement(it)) }
                }
            } ?: buildJsonObject {
                put("type", "thinking")
                put("thinking", reasoning)
                reasoningMetadata?.signature?.let { put("signature", it) }
                reasoningMetadata?.claudeReplay?.let { put(CLAUDE_REPLAY_ANNOTATION, json.encodeToJsonElement(it)) }
            }
        }

        else -> null
    }

    private fun UIMessagePart.Tool.toToolUseBlock() = buildJsonObject {
        put("type", "tool_use")
        put("id", providerCallId)
        put("name", toolName)
        put("input", inputAsJson())
    }

    private fun UIMessagePart.Tool.toToolResultBlock(
        mediaCapabilities: RequestMediaCapabilities,
    ) = buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", providerCallId)
        putJsonArray("content") {
            output.mapNotNull { it.toContentBlock(mediaCapabilities.toolOutputImages) }.forEach { add(it) }
        }
    }

    internal fun parseStreamEvent(data: JsonObject, state: ClaudeStreamState): MessageChunk {
        val type = data["type"]?.jsonPrimitive?.contentOrNull
        val reason = data["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull
        state.observe(type, reason)
        state.record(data)
        val successful = type == "message_stop" && state.completionError() == null
        val index = data["index"]?.jsonPrimitive?.intOrNull
        val updates: List<Pair<Int, UIMessagePart>> = when {
            successful -> state.proofUpdates() + state.completedContentUpdates()
            index != null && state.isServerBlock(index) -> {
                // Partial JSON belongs to the server invocation, never to the local tool index.
                if (type == "content_block_delta") emptyList()
                else listOf(index to state.serverPart(index, complete = false))
            }
            else -> parseMessage(buildJsonArray {
                data["content_block"]?.jsonObject?.let { add(it) }
                data["delta"]?.jsonObject?.let { add(it) }
            }, state.sourceOrNull(), false).parts.map { requireNotNull(index) to it }
        }
        val parts = updates.map { (position, part) -> part.withClaudeBlock(state.responseId, position) }
        val delta = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = parts,
            annotations = claudeCitations(parts),
            providerMetadata = if (successful) state.responseMetadata() else null,
        )
        return MessageChunk(
            id = "", model = "", usage = parseTokenUsage(data),
            choices = listOf(UIMessageChoice(
                index = 0, delta = delta, message = null, finishReason = reason,
                toolCallSlots = updates.filter { it.second is UIMessagePart.Tool }.map { ProviderToolCallSlot.Index(it.first) },
                contentBlockSlots = updates.map { ProviderContentBlockSlot(state.responseId, it.first) },
                continuation = if (successful && state.requiresContinuation()) ProviderContinuation.CONTINUE_ASSISTANT else null,
            )),
        )
    }

    private fun parseMessage(content: JsonArray): UIMessage = parseMessage(content, null, false)

    private fun parseMessage(content: JsonArray, source: ServerToolSource?, complete: Boolean): UIMessage {
        val parts = mutableListOf<UIMessagePart>()

        content.forEach { contentBlock ->
            val block = contentBlock.jsonObject
            val type = block["type"]?.jsonPrimitive?.contentOrNull

            when (type) {
                "text", "text_delta" -> {
                    val text = block["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (text.isNotEmpty() || type == "text") {
                        parts.add(UIMessagePart.Text(text, (block["citations"] as? JsonArray)?.let {
                            ClaudeTextMetadata(it, source).toMetadata()
                        }))
                    }
                }

                "thinking", "thinking_delta", "signature_delta" -> {
                    val thinking = block["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                    val signature = block["signature"]?.jsonPrimitive?.contentOrNull
                    if (thinking.isNotEmpty() || signature != null || type == "thinking") {
                        parts.add(
                            UIMessagePart.Reasoning(
                                reasoning = thinking,
                                createdAt = Clock.System.now(),
                                finishedAt = null,
                                metadata = signature?.let { ClaudeReasoningMetadata(signature = it).toMetadata() },
                            )
                        )
                    }
                }

                "redacted_thinking" -> {
                    val data = block["data"]?.jsonPrimitiveOrNull?.contentOrNull
                    if (data != null) {
                        parts.add(
                            UIMessagePart.Reasoning(
                                reasoning = "",
                                createdAt = Clock.System.now(),
                                finishedAt = null,
                                metadata = ClaudeReasoningMetadata(redactedData = data).toMetadata(),
                            )
                        )
                    }
                }

                "server_tool_use", "web_search_tool_result" -> parts.add(UIMessagePart.ServerTool(
                    source = requireNotNull(source) { "Claude server block has no response source" },
                    block = block,
                    complete = complete,
                ))

                "tool_use" -> {
                    val id = block["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val name = block["name"]?.jsonPrimitive?.contentOrNull ?: ""
                    val input = block["input"]?.jsonObject ?: JsonObject(emptyMap())
                    parts.add(
                        UIMessagePart.Tool(
                            localCallId = Uuid.NIL,
                            stepId = Uuid.NIL,
                            providerCallId = id,
                            toolName = name,
                            input = if (input.isEmpty()) "" else json.encodeToString(input),
                            output = emptyList()
                        )
                    )
                }

                "input_json_delta" -> {
                    val input = block["partial_json"]?.jsonPrimitive?.contentOrNull
                    parts.add(
                        UIMessagePart.Tool(
                            localCallId = Uuid.NIL,
                            stepId = Uuid.NIL,
                            providerCallId = "",
                            toolName = "",
                            input = input ?: "",
                            output = emptyList()
                        )
                    )
                }
            }
        }

        return UIMessage(
            role = MessageRole.ASSISTANT,
            parts = parts,
            annotations = claudeCitations(parts),
        )
    }

    internal fun parseTokenUsage(bodyJson: JsonObject?): ProviderUsageSnapshot? {
        if (bodyJson == null) return null

        // 回退到标准 usage 字段
        val usageJson = bodyJson["usage"]?.jsonObject
            ?: bodyJson["message"]?.jsonObject?.get("usage")?.jsonObject
            ?: return null
        val uncachedInputTokens = usageJson["input_tokens"]?.jsonPrimitiveOrNull?.longOrNull
        val cacheReadInputTokens = usageJson["cache_read_input_tokens"]?.jsonPrimitiveOrNull?.longOrNull
        val cacheWriteInputTokens = usageJson["cache_creation_input_tokens"]?.jsonPrimitiveOrNull?.longOrNull
        val outputTokens = usageJson["output_tokens"]?.jsonPrimitiveOrNull?.longOrNull
        val inputTokens = uncachedInputTokens?.let {
            sumTokenCountsOrNull(it, cacheReadInputTokens ?: 0L, cacheWriteInputTokens ?: 0L)
        }
        val isStreamingUsageEvent = bodyJson["type"]?.jsonPrimitiveOrNull?.contentOrNull in
            setOf("message_start", "message_delta")
        return ProviderUsageSnapshot(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheReadInputTokens = cacheReadInputTokens,
            cacheWriteInputTokens = cacheWriteInputTokens,
            totalTokens = if (isStreamingUsageEvent) {
                null
            } else {
                sumTokenCountsOrNull(inputTokens, outputTokens)
            },
            canDeriveTotalFromInputAndOutput = true,
        )
    }
}

/**
 * Anthropic thinking signatures are bound to the model that produced them. When the selected model
 * changes, keep the visible answer and tool history but strip thinking/redacted blocks from messages
 * that are known to have been produced by another configured model. Legacy messages without modelId
 * remain untouched for persistence compatibility.
 */
internal fun stripClaudeThinkingFromOtherModels(
    messages: List<ModelRequestMessage>,
    activeModelId: ConfigurationReference,
): List<ModelRequestMessage> = messages.map { message ->
    if (message.role == MessageRole.ASSISTANT &&
        message.modelId != null &&
        message.modelId != activeModelId
    ) {
        message.copy(parts = message.parts.filterNot { it is UIMessagePart.Reasoning })
    } else {
        message
    }
}

/**
 * Builder-owned structural keys for Anthropic Messages requests.
 *
 * These fields are exclusively owned by [ClaudeProvider.buildMessageRequest]; a [CustomBody]
 * entry attempting to override any of them is rejected before HTTP with a typed
 * [me.rerere.ai.util.CustomBodyReservedKeyException].
 */
internal val CLAUDE_MESSAGES_OWNERSHIP = RequestBodyOwnership(
    protocol = "anthropic-messages",
    reservedKeys = setOf(
        "model",
        "messages",
        "system",
        "tools",
        "stream",
    ),
)

private fun claudeCitations(parts: List<UIMessagePart>): List<UIMessageAnnotation> = parts
    .flatMap { it.metadataAs<ClaudeTextMetadata>()?.claudeCitations.orEmpty() }
    .mapNotNull { element ->
        val citation = element as? JsonObject ?: return@mapNotNull null
        val url = citation["url"]?.jsonPrimitiveOrNull?.contentOrNull ?: return@mapNotNull null
        UIMessageAnnotation.UrlCitation(citation["title"]?.jsonPrimitiveOrNull?.contentOrNull ?: url, url)
    }.distinct()

private fun stripClaudeServerState(messages: List<ModelRequestMessage>, endpoint: String): List<ModelRequestMessage> =
    messages.map { message -> message.copy(parts = message.parts.mapNotNull { part ->
        when (part) {
            is UIMessagePart.ServerTool -> part.takeIf {
                it.complete && it.source == ServerToolSource(ServerToolProtocol.CLAUDE_MESSAGES, endpoint)
            }
            is UIMessagePart.Text -> {
                val source = part.metadataAs<ClaudeTextMetadata>()?.claudeCitationSource
                if (source != null && source != ServerToolSource(ServerToolProtocol.CLAUDE_MESSAGES, endpoint)) {
                    part.copy(metadata = JsonObject(part.metadata.orEmpty() - setOf("claudeCitations", "claudeCitationSource")))
                } else part
            }
            else -> part
        }
    }) }

private fun UIMessagePart.withClaudeBlock(responseId: String, index: Int): UIMessagePart {
    val metadata = JsonObject(metadata.orEmpty() + ClaudeContentBlockMetadata(ClaudeResponseBlock(responseId, index)).toMetadata())
    return when (this) {
        is UIMessagePart.Text -> copy(metadata = metadata)
        is UIMessagePart.Reasoning -> copy(metadata = metadata)
        is UIMessagePart.Tool -> copy(metadata = metadata)
        is UIMessagePart.ServerTool -> copy(metadata = metadata)
        else -> error("Unsupported Claude response block")
    }
}

internal class ClaudeStreamState(
    private val request: JsonObject? = null,
    private val endpoint: String? = null,
    private val retiredDigests: Set<String> = emptySet(),
) {
    var responseEndpoint: String? = endpoint
    private var responseModel: String? = null
    val responseId = Uuid.random().toString()
    private val blocks = sortedMapOf<Int, JsonObject>()
    private val inputs = mutableMapOf<Int, StringBuilder>()
    private val closedBlocks = mutableSetOf<Int>()
    @Volatile private var stopped = false
    @Volatile private var stopReason: String? = null

    fun observe(type: String?, reason: String?) {
        if (reason != null) stopReason = reason
        if (type == "message_stop") stopped = true
    }

    fun record(event: JsonObject) {
        (event["message"] as? JsonObject)?.get("model")?.jsonPrimitive?.contentOrNull?.let { responseModel = it }
        event["model"]?.jsonPrimitive?.contentOrNull?.let { responseModel = it }
        val index = event["index"]?.jsonPrimitive?.intOrNull ?: return
        when (event["type"]?.jsonPrimitive?.contentOrNull) {
            "content_block_start" -> {
                require(index !in blocks) { "Claude response reused a content block index" }
                blocks[index] = requireNotNull(event["content_block"] as? JsonObject)
            }
            "content_block_delta" -> {
                val delta = event["delta"] as? JsonObject ?: return
                val before = blocks[index] ?: return
                require(index !in closedBlocks) { "Claude delta after content block stop" }
                val field = when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                    "text_delta" -> "text"
                    "thinking_delta" -> "thinking"
                    "signature_delta" -> "signature"
                    "input_json_delta" -> {
                        inputs.getOrPut(index) { StringBuilder() }.append(delta["partial_json"]?.jsonPrimitive?.content.orEmpty())
                        return
                    }
                    "citations_delta" -> {
                        val citation = requireNotNull(delta["citation"] as? JsonObject) { "Claude citation delta has no citation" }
                        val previous = (before["citations"] as? JsonArray).orEmpty()
                        blocks[index] = JsonObject(before + ("citations" to JsonArray(previous + citation)))
                        return
                    }
                    else -> return
                }
                blocks[index] = JsonObject(before + (field to JsonPrimitive(
                    before[field]?.jsonPrimitive?.content.orEmpty() + delta[field]?.jsonPrimitive?.content.orEmpty())))
            }
            "content_block_stop" -> {
                closedBlocks.add(index)
                inputs.remove(index)?.let { input ->
                    val block = requireNotNull(blocks[index])
                    blocks[index] = JsonObject(block + ("input" to json.parseToJsonElement(input.toString()).jsonObject))
                }
            }
        }
    }

    fun proofUpdates(): List<Pair<Int, UIMessagePart.Reasoning>> {
        val request = request ?: return emptyList()
        val endpoint = requireNotNull(responseEndpoint)
        require(inputs.isEmpty()) { "Claude response ended before tool input block completed" }
        require(blocks.keys.toList() == (0 until blocks.size).toList()) { "Claude response has incomplete content block sequence" }
        val response = blocks.values.toList()
        return response.mapIndexedNotNull { index, block ->
            if (block["type"]?.jsonPrimitive?.content !in setOf("thinking", "redacted_thinking")) null else {
                val evidence = claudeReplayEvidence(request, endpoint, response, index).copy(model = responseModel.orEmpty())
                index to UIMessagePart.Reasoning("", createdAt = Clock.System.now(), finishedAt = Clock.System.now(),
                    metadata = ClaudeReasoningMetadata(claudeReplay = evidence).toMetadata())
            }
        }
    }

    fun responseMetadata(): JsonObject? = request?.let {
        ClaudeResponseMetadata(ClaudeReplayState(retiredDigests = retiredDigests)).toMetadata()
    }

    fun sourceOrNull(): ServerToolSource? = responseEndpoint?.let { ServerToolSource(ServerToolProtocol.CLAUDE_MESSAGES, it) }

    fun isServerBlock(index: Int): Boolean = blocks[index]?.get("type")?.jsonPrimitive?.contentOrNull in
        setOf("server_tool_use", "web_search_tool_result")

    fun serverPart(index: Int, complete: Boolean) = UIMessagePart.ServerTool(
        source = requireNotNull(sourceOrNull()) { "Claude server block has no response source" },
        block = requireNotNull(blocks[index]),
        complete = complete,
    )

    fun requiresContinuation(): Boolean = stopReason == "pause_turn"

    fun completedContentUpdates(): List<Pair<Int, UIMessagePart>> = blocks.mapNotNull { (index, block) ->
        when {
            isServerBlock(index) -> {
                require(index in closedBlocks && index !in inputs) { "Claude server block did not complete" }
                index to serverPart(index, complete = true)
            }
            block["type"]?.jsonPrimitive?.contentOrNull == "text" && block["citations"] is JsonArray ->
                index to UIMessagePart.Text("", ClaudeTextMetadata(block["citations"] as JsonArray, sourceOrNull()).toMetadata())
            else -> null
        }
    }

    fun completionError(): HttpException? = if (!stopped) {
        HttpException(
            "Claude stream closed before message_stop",
            terminalStatus = ProviderTerminalStatus.INCOMPLETE,
        )
    } else claudeStopReasonError(stopReason)
}

internal fun claudeStopReasonError(reason: String?): HttpException? = when (reason) {
    "end_turn", "stop_sequence", "tool_use", "pause_turn" -> null
    else -> HttpException(
        message = "Claude generation ended with stop_reason=${reason ?: "missing"}",
        terminalStatus = if (reason == null || reason == "max_tokens" || reason == "pause_turn") {
            ProviderTerminalStatus.INCOMPLETE
        } else ProviderTerminalStatus.FAILED,
    )
}
