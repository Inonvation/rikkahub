package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.util.RetryableHttpException
import me.rerere.ai.util.retryWithKeyFallback
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import me.rerere.search.SearchService.Companion.keyRoulette
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object TinyfishSearchService : SearchService<SearchServiceOptions.TinyfishOptions> {
    override val name: String = "Tinyfish"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://agent.tinyfish.ai/api-keys")
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.TinyfishOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.TinyfishOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "url to scrape")
                })
            },
            required = listOf("url")
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.TinyfishOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")
            val url = "https://api.search.tinyfish.ai" +
                    "?query=${java.net.URLEncoder.encode(query, "UTF-8")}"

            val result = keyRoulette.retryWithKeyFallback(
                serviceOptions.apiKey,
                serviceOptions.id.toString(),
                serviceOptions.multipleKeys,
            ) { apiKey ->
                val request = Request.Builder()
                    .url(url)
                    .addHeader("X-API-Key", apiKey)
                    .build()

                val response = httpClient.newCall(request).await()
                if (response.isSuccessful) {
                    val responseBody = response.body.string()
                    json.decodeFromString<TinyfishSearchResponse>(responseBody)
                } else {
                    val code = response.code
                    val msg = "Tinyfish search failed with code $code: ${response.message}"
                    response.close()
                    throw RetryableHttpException(code, msg)
                }
            }

            val items = result.results.map { r ->
                SearchResultItem(
                    title = r.title,
                    url = r.url,
                    text = r.snippet
                )
            }

            return@withContext Result.success(
                SearchResult(
                    answer = null,
                    items = items
                )
            )
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.TinyfishOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.content ?: error("url is required")
            val body = buildJsonObject {
                put("urls", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive(url))
                })
                put("format", "markdown")
            }

            val result = keyRoulette.retryWithKeyFallback(
                serviceOptions.apiKey,
                serviceOptions.id.toString(),
                serviceOptions.multipleKeys,
            ) { apiKey ->
                val request = Request.Builder()
                    .url("https://api.fetch.tinyfish.ai")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("X-API-Key", apiKey)
                    .build()

                val response = httpClient.newCall(request).await()
                if (response.isSuccessful) {
                    val responseBody = response.body.string()
                    json.decodeFromString<TinyfishFetchResponse>(responseBody)
                } else {
                    val code = response.code
                    val msg = "Tinyfish fetch failed with code $code: ${response.message}"
                    response.close()
                    throw RetryableHttpException(code, msg)
                }
            }

            return@withContext Result.success(
                ScrapedResult(
                    urls = result.results.map {
                        ScrapedResultUrl(
                            url = it.url,
                            content = it.text ?: "",
                            metadata = ScrapedResultMetadata(
                                title = it.title,
                                description = it.description,
                                language = it.language,
                            )
                        )
                    }
                )
            )
        }
    }

    @Serializable
    data class TinyfishSearchResponse(
        val query: String? = null,
        val results: List<TinyfishSearchResultItem> = emptyList(),
        @SerialName("total_results")
        val totalResults: Int? = null,
        val page: Int? = null,
    )

    @Serializable
    data class TinyfishSearchResultItem(
        val position: Int? = null,
        @SerialName("site_name")
        val siteName: String? = null,
        val title: String,
        val snippet: String = "",
        val url: String,
    )

    @Serializable
    data class TinyfishFetchResponse(
        val results: List<TinyfishFetchResultItem> = emptyList(),
        val errors: List<TinyfishFetchError> = emptyList(),
    )

    @Serializable
    data class TinyfishFetchResultItem(
        val url: String,
        @SerialName("final_url")
        val finalUrl: String? = null,
        val title: String? = null,
        val description: String? = null,
        val language: String? = null,
        val text: String? = null,
    )

    @Serializable
    data class TinyfishFetchError(
        val url: String? = null,
        val error: String? = null,
    )
}
