package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.convertHtmlToMarkdown
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import me.rerere.search.ScrapedResult
import kotlin.uuid.Uuid

/**
 * 构建搜索工具集。
 *
 * @param concise 精简模式（子代理装配用）：去掉 citation/images/示例等"面向最终回答"的说明，
 *   只保留查询用途，因为子代理产出的是内部摘要、不做面向用户的引用渲染。
 *   母代理（默认 false）保留完整说明。
 *
 * 结构：
 * - 保留单工具 `search_web`/`scrape_web`（绑定 searchServiceSelected 主服务商，兼容旧行为）
 * - 为每个启用服务商（enabledSearchServiceIds 多选）生成独立工具
 *   `search_web__{id前6位}` / `scrape_web__{id前6位}`，供多来源交叉检索。
 *   每个工具独立参数 schema、独立密钥、独立执行——一个源的失败/参数不影响其他源。
 */
fun createSearchTools(settings: Settings, concise: Boolean = false): Set<Tool> {
    val selectedId = settings.searchServices
        .getOrNull(settings.searchServiceSelected)?.id
    // 每个启用服务商一个独立工具；选中的主服务商由旧版 search_web 覆盖，避免重复
    val enabled = settings.searchServices.filter {
        it.id in settings.enabledSearchServiceIds && it.id != selectedId
    }

    return buildSet {
        // 兼容旧单工具（绑定 searchServiceSelected 主服务商）
        addAll(createSingleSearchTools(settings, concise))

        // 每启用服务商一个独立工具
        enabled.forEach { opts ->
            val service = SearchService.getService(opts)
            val shortId = opts.id.toString().take(6)
            add(
                Tool(
                    name = "search_web__$shortId",
                    description = searchToolDescription(opts.displayName, concise),
                    parameters = { service.parameters(opts) },
                    execute = { args ->
                        val result = service.search(
                            params = args.jsonObject,
                            commonOptions = settings.searchCommonOptions,
                            serviceOptions = opts,
                        )
                        val results = JsonInstantPretty.encodeToJsonElement(result.getOrThrow()).jsonObject.let { json ->
                            val map = json.toMutableMap()
                            map["provider"] = JsonPrimitive(opts.displayName)
                            map["items"] = JsonArray(map["items"]!!.jsonArray.mapIndexed { index, item ->
                                JsonObject(item.jsonObject.toMutableMap().apply {
                                    put("id", JsonPrimitive(Uuid.random().toString().take(6)))
                                    put("index", JsonPrimitive(index + 1))
                                })
                            })
                            JsonObject(map)
                        }
                        listOf(UIMessagePart.Text(results.toString()))
                    }
                )
            )
            if (service.scrapingParameters(opts) != null) {
                add(
                    Tool(
                        name = "scrape_web__$shortId",
                        description = """
                            Scrape a URL for detailed page content using ${opts.displayName}.
                            Use this when the user requests content from a specific page or when search snippets are insufficient.
                            Avoid using it for common questions unless the user asks.
                            Returned content is automatically converted to Markdown when the provider returns raw HTML.
                            """.trimIndent(),
                        parameters = { service.scrapingParameters(opts) },
                        execute = { args ->
                            val result = service.scrape(
                                params = args.jsonObject,
                                commonOptions = settings.searchCommonOptions,
                                serviceOptions = opts,
                            )
                            val payload = result.getOrThrow().toPayloadWithAutoMarkdown()
                            listOf(UIMessagePart.Text(payload.toString()))
                        }
                    )
                )
            }
        }
    }
}

/**
 * 搜索工具描述。完整版（母代理）含响应格式/引用/图片/示例，供最终回答使用；
 * 精简版（子代理）只保留查询用途与多源交叉提示——子代理产出内部摘要，不做面向用户的引用渲染。
 */
private fun searchToolDescription(providerName: String, concise: Boolean): String {
    if (concise) {
        return """
        Search the web for up-to-date or specific information using $providerName.
        Use this when the user (or the parent agent) asks for the latest news, current facts, or needs verification.
        Avoid using it for general knowledge you already know unless the user asks for verification.
        Generate focused keywords and run multiple searches if needed.
            When thorough cross-verified info is wanted, call MULTIPLE search_web__* tools (one per provider) and compare results.
            """.trimIndent()
    }
    return """
        Search the web for up-to-date or specific information using $providerName.
        Use this when the user asks for the latest news, current facts, or needs verification.
        Avoid using it for general knowledge you already know unless the user asks for verification.
        Generate focused keywords and run multiple searches if needed.

        Multi-source: when the user wants thorough cross-verified info, call MULTIPLE search_web__* tools
        (one per enabled search provider) and compare the independent results.

        Response format:
        - items[].id (short id), title, url, text
        - images[]: image urls related to the query (may be empty)

        Citations:
        - After using results, add `[citation,domain](id)` after the sentence.
        - Multiple citations are allowed.
        - If no results are cited, omit citations.

        Images:
        - When images help the user understand the answer, embed relevant ones using Markdown: `![](url)`.
        - Embed 2 to 4 images, and only use urls from `images[]` (never fabricate or alter urls).
        - Usually place the images at the very beginning of your reply; skip them entirely if none are relevant.

        Example:
        The capital of France is Paris. [citation,example.com](abc123)
        The population is about 2.1 million. [citation,example.com](abc123) [citation,example2.com](def456)
        """.trimIndent()
}

/** 兼容旧行为：绑定 searchServiceSelected 主服务商的单工具 */
private fun createSingleSearchTools(settings: Settings, concise: Boolean): Set<Tool> {
    return buildSet {
        add(
            Tool(
                name = "search_web",
                description = searchToolDescription("the selected provider", concise),
                parameters = {
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    service.parameters(options)
                },
                execute = {
                    val options = settings.searchServices.getOrElse(
                        index = settings.searchServiceSelected,
                        defaultValue = { SearchServiceOptions.DEFAULT })
                    val service = SearchService.getService(options)
                    val result = service.search(
                        params = it.jsonObject,
                        commonOptions = settings.searchCommonOptions,
                        serviceOptions = options,
                    )
                    val results =
                        JsonInstantPretty.encodeToJsonElement(result.getOrThrow()).jsonObject.let { json ->
                            val map = json.toMutableMap()
                            map["items"] =
                                JsonArray(map["items"]!!.jsonArray.mapIndexed { index, item ->
                                    JsonObject(item.jsonObject.toMutableMap().apply {
                                        put("id", JsonPrimitive(Uuid.random().toString().take(6)))
                                        put("index", JsonPrimitive(index + 1))
                                    })
                                })
                            JsonObject(map)
                        }
                    listOf(UIMessagePart.Text(results.toString()))
                }
            )
        )

        val options = settings.searchServices.getOrElse(
            index = settings.searchServiceSelected,
            defaultValue = { SearchServiceOptions.DEFAULT })
        val service = SearchService.getService(options)
        if (service.scrapingParameters(options) != null) {
            add(
                Tool(
                    name = "scrape_web",
                    description = """
                        Scrape a URL for detailed page content.
                        Use this when the user requests content from a specific page or when search snippets are insufficient.
                        Avoid using it for common questions unless the user asks.
                        Returned content is automatically converted to Markdown when the provider returns raw HTML.
                        """.trimIndent(),
                    parameters = {
                        val options = settings.searchServices.getOrElse(
                            index = settings.searchServiceSelected,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        service.scrapingParameters(options)
                    },
                    execute = {
                        val options = settings.searchServices.getOrElse(
                            index = settings.searchServiceSelected,
                            defaultValue = { SearchServiceOptions.DEFAULT })
                        val service = SearchService.getService(options)
                        val result = service.scrape(
                            params = it.jsonObject,
                            commonOptions = settings.searchCommonOptions,
                            serviceOptions = options,
                        )
                        val payload = result.getOrThrow().toPayloadWithAutoMarkdown()
                        listOf(UIMessagePart.Text(payload.toString()))
                    }
                ))
        }
    }
}

/**
 * 粗略判断一段抓取内容是否为 HTML。
 * 命中开头 4KB 内的常见 HTML 标签特征即视为 HTML；纯文本/Markdown 判否。
 */
private fun looksLikeHtml(content: String): Boolean {
    if (content.isBlank()) return false
    val head = content.take(4096).lowercase()
    return listOf("<html", "<head", "<body", "<div", "<article", "<main", "<h1", "<h2", "<h3", "<p>").any {
        head.contains(it)
    }
}

/**
 * 把 scrape 结果序列化成 JSON，并把仍是原始 HTML 的 content 自动转成 Markdown。
 * 已转 Markdown（如 Jina/Firecrawl）的 content 原样保留。
 */
private fun ScrapedResult.toPayloadWithAutoMarkdown(): JsonObject {
    val json = JsonInstantPretty.encodeToJsonElement(this).jsonObject
    val urls = json["urls"]?.jsonArray ?: return json
    val converted = JsonArray(urls.map { item ->
        val obj = item.jsonObject
        val content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (looksLikeHtml(content)) {
            JsonObject(obj.toMutableMap().apply {
                put("content", JsonPrimitive(convertHtmlToMarkdown(content)))
            })
        } else {
            obj
        }
    })
    return JsonObject(json.toMutableMap().apply {
        put("urls", converted)
    })
}
