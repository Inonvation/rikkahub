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
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * 构建搜索工具集。
 *
 * @param concise 精简模式（子代理装配用）：去掉 citation/images 等"面向最终回答"的规范
 *   （见 [searchToolSystemPrompt] 的 concise 分支），子代理产出内部摘要、不做引用渲染。
 *
 * 声明信道划分（对齐 wire 截断契约，改动前先读 WireTool.kt）：
 * - description 只写触发条件（≤300 字符），保证截断后语义完整；
 * - 响应格式/引用/图片/多源规范走 systemPrompt（不裁剪 + 内容去重，
 *   多服务商天然共享同一份文本，只注入一次）。
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
                    systemPrompt = { _, _ -> searchToolSystemPrompt(concise) },
                    parameters = { service.parameters(opts) },
                    execute = { args ->
                        val result = service.search(
                            params = args.jsonObject,
                            commonOptions = settings.searchCommonOptions,
                            serviceOptions = opts,
                        )
                        val results = JsonInstantPretty.encodeToJsonElement(
                            result.getOrThrow().copy(retrievedAt = Clock.System.now().toString())
                        ).jsonObject.let { json ->
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
                        Use this when the user requests content from a specific page, when search snippets are insufficient, or when a current claim needs verification against a specific source.
                        Avoid using it for common questions unless the user asks.
                        """.trimIndent(),
                    systemPrompt = { _, _ -> SCRAPE_TOOL_SYSTEM_PROMPT },
                    parameters = { service.scrapingParameters(opts) },
                        execute = { args ->
                            val result = service.scrape(
                                params = args.jsonObject,
                                commonOptions = settings.searchCommonOptions,
                                serviceOptions = opts,
                            )
                            val payload = result.getOrThrow()
                                .copy(retrievedAt = Clock.System.now().toString())
                                .toPayloadWithAutoMarkdown()
                            listOf(UIMessagePart.Text(payload.toString()))
                        }
                    )
                )
            }
        }
    }
}

/**
 * 搜索工具描述（仅触发条件，必须写进 wire 的 300 字符预算内）：
 * 响应格式/引用/图片等使用规范放 [searchToolSystemPrompt]（system 信道，不裁剪）。
 *
 * @param concise 精简模式（子代理装配用）：省掉面向最终回答的表述，子代理产出内部摘要。
 */
private fun searchToolDescription(providerName: String, concise: Boolean): String {
    if (concise) {
        return """
            Search the web using $providerName for current facts, news, or verification.
            Avoid it for general knowledge you already know.
            Do not treat result order as freshness — check titles, URLs and dates before current claims.
            """.trimIndent()
    }
    return """
        Search the web using $providerName for current facts, news, or verification.
        Use when the answer must be up to date; avoid it for general knowledge you already know.
        Do not treat result order as freshness — check titles, URLs and dates before current claims.
        """.trimIndent()
}

/**
 * 抓取工具描述（仅触发条件，≤300 字符）。转换语义从描述里移除，见 [SCRAPE_TOOL_SYSTEM_PROMPT]。
 */
private fun scrapeToolDescription(providerName: String): String = """
    Scrape a URL for detailed page content using $providerName.
    Use this when the user requests content from a specific page, when search snippets are insufficient, or when a current claim needs verification against a specific source.
    Avoid using it for common questions unless the user asks.
    """.trimIndent()

/**
 * 抓取使用规范（system 信道）。描述上限 300 字符放不下完整语义，故上移。
 * `urls[].content` 的 HTML→Markdown 自动转换语义写在这里，模型才不会把转换后内容当"原始 HTML"。
 */
internal const val SCRAPE_TOOL_SYSTEM_PROMPT =
    "**Web Scrape**\n" +
        "- Returns `urls[]` with `url`, `content`, and optional `metadata` (title/description/language/publishedDate); `retrievedAt` is the local retrieval time, never a publication date.\n" +
        "- `content` is raw HTML automatically converted to Markdown; providers that already return Markdown keep it as-is.\n" +
        "- For pages too large to scrape whole, use `search_web` first to locate the right section, then scrape the specific URL."

/**
 * 搜索使用规范（system 信道，随工具注册去重注入一次）。
 *
 * 为什么不放 description：wire 层对工具描述截断到 300 字符（见 WireTool），
 * 引用/图片/多源段落的完整语义放不进 description，会被静默砍掉——
 * 而 `[citation,domain](id)` 是 UI 侧 Markdown 渲染器（MarkdownNew.kt）实际消费的格式，
 * 必须逐字送达模型。system 信道不裁剪、内容去重、逐字节稳定，是这些规范的正确位置。
 * 文本变化需同步 [me.rerere.rikkahub.data.ai.PROMPT_REVISION]。
 */
internal fun searchToolSystemPrompt(concise: Boolean): String {
    if (concise) {
        return """
            **Web Search**
            - When thorough cross-verified info is needed, call MULTIPLE `search_web__*` tools (one per provider) and compare the independent results.
            - If a date or primary source is missing, or sources conflict, run another focused search or scrape the most relevant source before answering.
            """.trimIndent()
    }
    return """
        **Web Search**
        - Response format: `retrievedAt` is the local retrieval time, never a publication date; `items[]` carries `id`, `index`, `title`, `url`, `publishedDate`, `highlights`, `text`; `images[]` may hold image urls related to the query.
        - Citations: after a sentence drawn from results, add `[citation,domain](id)`, e.g. `The capital of France is Paris. [citation,example.com](abc123)`. Multiple citations are allowed; if no results are cited, omit citations entirely.
        - Images: when images help the user understand the answer, embed 2 to 4 of them with Markdown `![](url)` using only urls from `images[]` — never fabricate or alter urls. Usually place them at the very beginning of the reply; skip them entirely if none are relevant.
        - Multi-source: when the user wants thorough cross-verified info, call MULTIPLE `search_web__*` tools (one per enabled provider) and compare the independent results.
        - If a date or primary source is missing, or sources conflict, run another focused search or use `scrape_web` to verify the most relevant source before answering.
        """.trimIndent()
}

/** 兼容旧行为：绑定 searchServiceSelected 主服务商的单工具 */
private fun createSingleSearchTools(settings: Settings, concise: Boolean): Set<Tool> {
    return buildSet {
        add(
            Tool(
                name = "search_web",
                description = searchToolDescription("the selected provider", concise),
                systemPrompt = { _, _ -> searchToolSystemPrompt(concise) },
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
                        JsonInstantPretty.encodeToJsonElement(
                            result.getOrThrow().copy(retrievedAt = Clock.System.now().toString())
                        ).jsonObject.let { json ->
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
                    description = scrapeToolDescription("the selected provider"),
                    systemPrompt = { _, _ -> SCRAPE_TOOL_SYSTEM_PROMPT },
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
                        val payload = result.getOrThrow()
                            .copy(retrievedAt = Clock.System.now().toString())
                            .toPayloadWithAutoMarkdown()
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
