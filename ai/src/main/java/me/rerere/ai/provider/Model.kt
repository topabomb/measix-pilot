package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.common.configuration.ConfigurationReference

@Serializable
data class Model(
    val modelId: String = "",
    val displayName: String = "",
    val id: ConfigurationReference = ConfigurationReference.random(),
    val type: ModelType = ModelType.CHAT,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
    val tools: Set<BuiltInTools> = emptySet(),
    val providerOverwrite: ProviderSetting? = null,
    // Missing legacy values retain the image model previously fixed by ResponseAPI.
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val responsesImageModel: String = "gpt-image-2",
    /** User-declared input capacity; absent means unknown, not a Provider limit guarantee. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val contextLength: Int? = null,
) {
    init { require(contextLength == null || contextLength > 0) { "model_context_length_must_be_positive" } }
}

val RESPONSE_IMAGE_MODEL_IDS = listOf("gpt-image-2", "gpt-image-2.5-sunburst", "gpt-image-2.5-flare")

@Serializable
enum class ModelType {
    CHAT,
    IMAGE,
    EMBEDDING,
}

@Serializable
enum class Modality {
    TEXT,
    IMAGE,
}

@Serializable
enum class ModelAbility {
    TOOL,
    REASONING,
}

// 模型(提供商)提供的内置工具选项
@Serializable
sealed class BuiltInTools {
    // https://ai.google.dev/gemini-api/docs/google-search?hl=zh-cn
    @Serializable
    @SerialName("search")
    data object Search : BuiltInTools()

    // https://ai.google.dev/gemini-api/docs/url-context?hl=zh-cn
    @Serializable
    @SerialName("url_context")
    data object UrlContext : BuiltInTools()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : BuiltInTools()
}



