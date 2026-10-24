@file:Suppress("UNNECESSARY_SAFE_CALL")
package me.rerere.ai.provider.providers

import android.content.Context
import android.util.Log
import me.rerere.common.http.PrivateRequest
import me.rerere.common.http.isPrivate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RequestCredentials
import me.rerere.ai.provider.RequestMediaCapabilities
import me.rerere.ai.provider.authenticate
import me.rerere.ai.provider.forCredentials
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.imageEndpoint
import me.rerere.ai.provider.providers.openai.imageRequestSize
import me.rerere.ai.provider.providers.openai.resolveOpenAIEndpointVendor
import me.rerere.ai.provider.providers.openai.OpenAIEndpointVendor
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.provider.providers.openai.openAIRequestMediaCapabilities
import me.rerere.ai.provider.images.ImageGenerationResponseParse
import me.rerere.ai.provider.images.ParsedImageGenerationItem
import me.rerere.ai.provider.images.requireUsableImages
import me.rerere.ai.provider.images.parseImageGenerationResponseBody
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.core.ModelRequestMessage
import me.rerere.ai.util.CustomBodyReservedKeyException
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.RequestBodyOwnership
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.formatProviderHttpError
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.readResponse
import me.rerere.common.http.getByKey
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "OpenAIProvider"

class OpenAIProvider(
    private val client: OkHttpClient,
    context: Context? = null
) : Provider<ProviderSetting.OpenAI> {

    override fun requestMediaCapabilities(
        providerSetting: ProviderSetting.OpenAI,
        model: Model,
    ): RequestMediaCapabilities = openAIRequestMediaCapabilities(providerSetting, model)

    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    private val chatCompletionsAPI = ChatCompletionsAPI(client = client, keyRoulette = keyRoulette)
    private val responseAPI = ResponseAPI(client = client, keyRoulette = keyRoulette)
    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> =
        withContext(Dispatchers.IO) {
            val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            val request = Request.Builder()
                .url("${providerSetting.baseUrl}/models")
                .headers(providerSetting.customHeaders.toHeaders())
                .header("Authorization", "Bearer $key")
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

                Model(
                    modelId = id,
                    displayName = id,
                )
            }
        }

    override suspend fun getBalance(providerSetting: ProviderSetting.OpenAI): String = withContext(Dispatchers.IO) {
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val url = if (providerSetting.balanceOption.apiPath.startsWith("http")) {
            providerSetting.balanceOption.apiPath
        } else {
            "${providerSetting.baseUrl}${providerSetting.balanceOption.apiPath}"
        }
        val request = Request.Builder()
            .url(url)
            .headers(providerSetting.customHeaders.toHeaders())
            .header("Authorization", "Bearer $key")
            .get()
            .build()
        val bodyStr = client.newCall(request).readResponse { response ->
            if (!response.isSuccessful) {
                error("Failed to get balance: ${response.code} ${response.body?.string()}")
            }
            response.body.string()
        }
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val value = bodyJson.getByKey(providerSetting.balanceOption.resultPath)
        val digitalValue = value.toFloatOrNull()
        if(digitalValue != null) {
            "%.2f".format(digitalValue)
        } else {
            value
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<ModelRequestMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = if (providerSetting.useResponseApi) {
        responseAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<ModelRequestMessage>,
        params: TextGenerationParams
    ): MessageChunk = if (providerSetting.useResponseApi) {
        responseAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting.OpenAI,
        params: EmbeddingGenerationParams
    ): EmbeddingGenerationResult = withContext(Dispatchers.IO) {
        require(params.input.isNotEmpty()) { "Embedding input cannot be empty" }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                if (params.input.size == 1) {
                    put("input", params.input.first())
                } else {
                    putJsonArray("input") {
                        params.input.forEach { add(JsonPrimitive(it)) }
                    }
                }
                params.dimensions?.let { put("dimensions", it) }
            }.mergeCustomBody(
                params.customBody,
                OPENAI_EMBEDDING_OWNERSHIP,
            )
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/embeddings")
            .headers((providerSetting.customHeaders + params.customHeaders).toHeaders())
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val bodyStr = client.newCall(request).readResponse { response ->
            if (!response.isSuccessful) {
                error("Failed to generate embedding: ${response.code} ${response.body?.string()}")
            }
            response.body?.string() ?: ""
        }
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val data = bodyJson["data"]?.jsonArray ?: error("No data in response")
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: params.model.modelId

        val embeddings = data.map { embeddingJson ->
            val embeddingArray = embeddingJson.jsonObject["embedding"]?.jsonArray
                ?: error("No embedding in response")
            embeddingArray.map { it.jsonPrimitive.content.toFloat() }
        }

        EmbeddingGenerationResult(
            model = model,
            embeddings = embeddings
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }
        check(params.credentials !is RequestCredentials.Routed) {
            "routed_image_requires_managed_provider"
        }

        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                put("prompt", params.prompt)
                put("n", params.numOfImages)

                imageRequestSize(providerSetting, params.size)?.let { put("size", it) }
            }
                .mergeCustomBody(params.customBody, if (resolveOpenAIEndpointVendor(providerSetting.baseUrl.toHttpUrl().host) == OpenAIEndpointVendor.OPENROUTER)
                    OPENROUTER_IMAGE_OWNERSHIP else OPENAI_IMAGE_GENERATION_OWNERSHIP)
        )

        val request = Request.Builder()
            .url(imageEndpoint(providerSetting, edit = false))
            .headers((providerSetting.customHeaders + params.customHeaders).toHeaders())
            .authenticate(params.credentials, "Authorization", "Bearer ", providerSetting.apiKey, providerSetting.id.toString(), keyRoulette)
            .header("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val items = withContext(Dispatchers.IO) {
            val parsed = client.forCredentials(params.credentials).newCall(request).readResponse { response ->
                if (!response.isSuccessful) {
                    throw formatProviderHttpError(response.code, response.body.string())
                }
                parseImageGenerationResponseBody(response.body.string())
            }
            parseImageResponse(
                parsed = parsed,
                privateRequest = request.isPrivate,
            )
        }

        items.forEach { emit(it) }
    }.flowOn(Dispatchers.IO)

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }
        check(params.credentials !is RequestCredentials.Routed) {
            "routed_image_edit_unsupported"
        }
        require(params.images.isNotEmpty()) {
            "At least one image is required"
        }

        val caller = currentCoroutineContext()
        val openRouter = resolveOpenAIEndpointVendor(providerSetting.baseUrl.toHttpUrl().host) == OpenAIEndpointVendor.OPENROUTER
        val requestBody = if (openRouter) {
            json.encodeToString(buildJsonObject {
                put("model", params.model.modelId)
                put("prompt", params.prompt)
                put("n", params.numOfImages)
                imageRequestSize(providerSetting, params.size)?.let { put("size", it) }
                putJsonArray("input_references") {
                    params.images.forEach { path ->
                        caller.ensureActive()
                        val file = validatedEditImage(path)
                        val dataUrl = "data:${file.imageMediaType()};base64,${Base64.encode(file.readBytes())}"
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", dataUrl) })
                        })
                    }
                }
            }.mergeCustomBody(params.customBody, OPENROUTER_IMAGE_OWNERSHIP))
                .toRequestBody("application/json".toMediaType())
        } else {
            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", params.model.modelId)
                .addFormDataPart("prompt", params.prompt)
                .addFormDataPart("n", params.numOfImages.toString())
            imageRequestSize(providerSetting, params.size)?.let { bodyBuilder.addFormDataPart("size", it) }

            val imageFieldName = if (params.images.size == 1) "image" else "image[]"
            params.images.forEach { path ->
                val imageFile = validatedEditImage(path)
                bodyBuilder.addFormDataPart(
                    imageFieldName,
                    imageFile.name,
                    imageFile.asRequestBody(imageFile.imageMediaType().toMediaType())
                )
            }

            params.customBody.let { bodies ->
                val conflicts = bodies.map { it.key }
                    .filter { it.isNotBlank() && it in OPENAI_IMAGE_EDIT_OWNERSHIP.reservedKeys }
                    .distinct()
                if (conflicts.isNotEmpty()) {
                    throw CustomBodyReservedKeyException(
                        protocol = OPENAI_IMAGE_EDIT_OWNERSHIP.protocol,
                        conflictingKeys = conflicts,
                    )
                }
                bodies.forEach { customBody ->
                    val value = when (val element = customBody.value) {
                        is JsonPrimitive -> element.contentOrNull ?: element.toString()
                        else -> element.toString()
                    }
                    bodyBuilder.addFormDataPart(customBody.key, value)
                }
            }
            bodyBuilder.build()
        }
        currentCoroutineContext().ensureActive()

        val request = Request.Builder()
            .url(imageEndpoint(providerSetting, edit = true))
            .headers((providerSetting.customHeaders + params.customHeaders).toHeaders())
            .authenticate(params.credentials, "Authorization", "Bearer ", providerSetting.apiKey, providerSetting.id.toString(), keyRoulette)
            .post(requestBody)
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val items = withContext(Dispatchers.IO) {
            val bodyStr = client.forCredentials(params.credentials).newCall(request).readResponse { response ->
                if (!response.isSuccessful) {
                    throw formatProviderHttpError(response.code, response.body?.string())
                }
                response.body.string()
            }
            parseImageResponse(
                parsed = parseImageGenerationResponseBody(bodyStr),
                privateRequest = request.isPrivate,
            )
        }

        items.forEach { emit(it) }
    }.flowOn(Dispatchers.IO)

    private suspend fun parseImageResponse(
        parsed: ImageGenerationResponseParse,
        privateRequest: Boolean,
    ): List<ImageGenerationItem> {
        parsed.requireUsableImages()
        return parsed.items.map { item ->
            when (item) {
                is ParsedImageGenerationItem.Base64Json -> ImageGenerationItem(
                    data = item.data,
                    mimeType = item.mimeType,
                )
                is ParsedImageGenerationItem.RemoteUrl -> downloadImageAsBase64(item.url, privateRequest)
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun downloadImageAsBase64(url: String, privateRequest: Boolean): ImageGenerationItem {
        val request = Request.Builder()
            .url(url)
            .apply { if (privateRequest) tag(PrivateRequest::class.java, PrivateRequest) }
            .get()
            .build()

        return client.newCall(request).readResponse { response ->
            if (!response.isSuccessful) {
                throw formatProviderHttpError(response.code, response.body.string())
            }
            val body = response.body
            ImageGenerationItem(
                data = Base64.encode(body.bytes()),
                mimeType = body.contentType()?.toString() ?: "image/png",
            )
        }
    }

    private fun validatedEditImage(path: String): File = File(path).also {
        require(it.isFile) { "Image file does not exist: $path" }
        require(it.extension.lowercase() in SUPPORTED_EDIT_IMAGE_EXTENSIONS) {
            "Unsupported image file type for OpenAI edit: ${it.extension}"
        }
    }

    private fun File.imageMediaType(): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    companion object {
        private val SUPPORTED_EDIT_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}

/**
 * Builder-owned structural keys for OpenAI Embedding requests.
 */
internal val OPENAI_EMBEDDING_OWNERSHIP = RequestBodyOwnership(
    protocol = "openai-embeddings",
    reservedKeys = setOf(
        "model",
        "input",
    ),
)

/**
 * Builder-owned structural keys for OpenAI image generation requests.
 */
internal val OPENAI_IMAGE_GENERATION_OWNERSHIP = RequestBodyOwnership(
    protocol = "openai-images-generations",
    reservedKeys = setOf(
        "model",
        "prompt",
        "n",
    ),
)

/**
 * Builder-owned structural keys for OpenAI image edit requests.
 *
 * The edit endpoint shares `model`, `prompt` and `n` with the generation endpoint, plus
 * `image`/`image[]` and `size` which are also exclusively managed by the builder.
 */
internal val OPENAI_IMAGE_EDIT_OWNERSHIP = RequestBodyOwnership(
    protocol = "openai-images-edits",
    reservedKeys = setOf(
        "model",
        "prompt",
        "n",
        "image",
        "image[]",
        "size",
    ),
)

/** Reference images are owned by the application's authorized edit inputs. */
internal val OPENROUTER_IMAGE_OWNERSHIP = RequestBodyOwnership(
    protocol = "openrouter-images",
    reservedKeys = setOf("model", "prompt", "n", "size", "input_references"),
)
