package me.rerere.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.common.http.readResponse
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.net.URLEncoder

object SearXNGService : SearchService<SearchServiceOptions.SearXNGOptions> {
    override fun parameters(options: SearchServiceOptions.SearXNGOptions): JsonObject? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", SEARCH_QUERY_DESCRIPTION)
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.SearXNGOptions): JsonObject? = null

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SearXNGOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(serviceOptions.url.isNotBlank()) {
                "SearXNG URL cannot be empty"
            }

            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")

            // 构建查询URL
            val baseUrl = serviceOptions.url.trimEnd('/')
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/search?q=$encodedQuery&format=json"
                .toHttpUrl()
                .newBuilder()
                .apply {
                    if (serviceOptions.engines.isNotBlank()) {
                        addQueryParameter("engines", serviceOptions.engines)
                    }
                    if (serviceOptions.language.isNotBlank()) {
                        addQueryParameter("language", serviceOptions.language)
                    }
                }
                .build()

            // 发送请求
            val request = Request.Builder()
                .url(url)
                .get()
                .apply {
                    // 添加HTTP Basic Auth支持
                    if (serviceOptions.username.isNotBlank() && serviceOptions.password.isNotBlank()) {
                        header("Authorization", Credentials.basic(serviceOptions.username, serviceOptions.password))
                    }
                }
                .build()

            httpClient.newCall(request).readResponse { response ->
                if (response.isSuccessful) {
                    val bodyRaw = response.body.string()
                    val searchResponse = json.decodeFromString<SearXNGResponse>(bodyRaw)

                    // 转换为标准格式，取前 N 个结果
                    val items = searchResponse.results
                        .take(commonOptions.resultSize)
                        .map { result ->
                            SearchResultItem(
                                title = result.title,
                                url = result.url,
                                text = result.content
                            )
                        }

                    SearchResult(items = items)
                } else {
                    val errorBody = response.body.string()
                    println("SearXNG API error: ${response.code} - $errorBody")
                    throw SearchHttpException("SearXNG", response.code)
                }
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SearXNGOptions
    ): Result<ScrapedResult> {
        return Result.failure(Exception("Scraping is not supported for SearXNG"))
    }


    @Serializable
    data class SearXNGResponse(
        @SerialName("results")
        val results: List<SearXNGResult>,
    )

    @Serializable
    data class SearXNGResult(
        @SerialName("url")
        val url: String,
        @SerialName("title")
        val title: String,
        @SerialName("content")
        val content: String,
    )
}
