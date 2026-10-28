package me.rerere.search

import me.rerere.common.configuration.ConfigurationReference
import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class SearchHttpException(val provider: String, val statusCode: Int) :
    IllegalStateException("$provider returned HTTP $statusCode")

class SearchNoResultsException : IllegalStateException("Search returned no results")

internal const val SEARCH_QUERY_DESCRIPTION =
    "Concise search keywords, not a full sentence. Use the language of the best sources on the topic: " +
        "usually English for technical, scientific and international topics, and the local language for regional topics. " +
        "Keep proper nouns, product names and error messages verbatim."

interface SearchService<T : SearchServiceOptions> {
    fun parameters(options: T): JsonObject?

    fun scrapingParameters(options: T): JsonObject?

    suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: T
    ): Result<SearchResult>

    suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: T
    ): Result<ScrapedResult>

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T : SearchServiceOptions> getService(options: T): SearchService<T> {
            return when (options) {
                is SearchServiceOptions.TavilyOptions -> TavilySearchService
                is SearchServiceOptions.BingLocalOptions -> BingSearchService
                is SearchServiceOptions.SearXNGOptions -> SearXNGService
            } as SearchService<T>
        }

        @Volatile
        internal var httpClient: OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        @Volatile
        internal var keyRoulette: KeyRoulette = KeyRoulette.default()

        fun init(client: OkHttpClient, context: Context? = null) {
            httpClient = client
            keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()
        }

        internal val json by lazy {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
    }
}

@Serializable
data class SearchCommonOptions(
    val resultSize: Int = 10
)

@Serializable
data class SearchResult(
    val answer: String? = null,
    val items: List<SearchResultItem>,
    val images: List<String> = emptyList(),
) {
    @Serializable
    data class SearchResultItem(
        val title: String,
        val url: String,
        val text: String,
    )
}

@Serializable
data class ScrapedResult(
    val urls: List<ScrapedResultUrl>,
)

@Serializable
data class ScrapedResultUrl(
    val url: String,
    val content: String,
    val metadata: ScrapedResultMetadata? = null,
)

@Serializable
data class ScrapedResultMetadata(
    val title: String? = null,
    val description: String? = null,
    val language: String? = null,
)

@Serializable
sealed class SearchServiceOptions {
    abstract val id: ConfigurationReference

    open val displayName: String
        get() = providerType.displayName

    val providerType: SearchProviderType
        get() = when (this) {
            is BingLocalOptions -> SearchProviderType.BING
            is TavilyOptions -> SearchProviderType.TAVILY
            is SearXNGOptions -> SearchProviderType.SEARXNG
        }

    companion object {
        val DEFAULT = BingLocalOptions()
    }

    @Serializable
    @SerialName("bing_local")
    data class BingLocalOptions(
        override val id: ConfigurationReference = ConfigurationReference.random()
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("tavily")
    data class TavilyOptions(
        override val id: ConfigurationReference = ConfigurationReference.random(),
        val apiKey: String = "",
        val depth: String = "advanced",
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
        val includeAnswer: Boolean = true,
    ) : SearchServiceOptions()

    @Serializable
    @SerialName("searxng")
    data class SearXNGOptions(
        override val id: ConfigurationReference = ConfigurationReference.random(),
        val url: String = "",
        val engines: String = "",
        val language: String = "",
        val username: String = "",
        val password: String = "",
    ) : SearchServiceOptions()
}

enum class SearchProviderType(val displayName: String) {
    BING("Bing"),
    TAVILY("Tavily"),
    SEARXNG("SearXNG");

    fun createOptions(): SearchServiceOptions = when (this) {
        BING -> SearchServiceOptions.BingLocalOptions()
        TAVILY -> SearchServiceOptions.TavilyOptions()
        SEARXNG -> SearchServiceOptions.SearXNGOptions()
    }
}
