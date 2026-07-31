package me.rerere.knowledge.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.knowledge.KnowledgeManager
import me.rerere.knowledge.data.entity.KnowledgeBaseEntity
import me.rerere.knowledge.retrieval.Reranker
import me.rerere.knowledge.vector.toFloatArray
import me.rerere.ai.core.InputSchema

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 单个知识库的 embedding 配置（provider + setting + model 来自同一次模型解析，原子获取）。
 */
data class EmbeddingConfig(
    val provider: Provider<ProviderSetting.OpenAI>,
    val providerSetting: ProviderSetting.OpenAI,
    val model: me.rerere.ai.provider.Model,
)

class KnowledgeSearchTool(
    private val knowledgeManager: KnowledgeManager,
    private val getAllowedKnowledgeBaseIds: suspend () -> Set<String>,
    private val getEmbeddingForBase: suspend (baseId: String) -> EmbeddingConfig?,
    private val getReranker: suspend () -> Reranker?,
    private val rewriteQuery: suspend (query: String) -> String = { it },
    private val generateHydeText: suspend (query: String) -> String? = { null },
) {
    fun create(): Tool {
        return Tool(
            name = "kb_search",
            description = "Search the user's knowledge base for relevant text chunks from uploaded documents. " +
                    "Use this first for questions about the user's documents, notes, or files. " +
                    "Set scan=true for counting or exhaustive listing queries.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", "The search query to find relevant information in the knowledge base")
                        })
                        put("knowledgeBaseIds", buildJsonObject {
                            put("type", "array")
                            put("description", "Optional list of knowledge base IDs to search. " +
                                    "If empty, all available knowledge bases are searched. " +
                                    "Call the `kb_list` tool first to discover available IDs.")
                            put("items", buildJsonObject {
                                put("type", "string")
                            })
                        })
                        put("scan", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Set true to scan all chunks for exact matches and return an exhaustive, deduplicated count/list. " +
                                    "Use for counting/statistics/exhaustive-listing queries. Default false.")
                        })
                    },
                    required = listOf("query")
                )
            },
            needsApproval = { false },
            execute = { args ->
                val obj = args.jsonObject
                val query = obj["query"]?.jsonPrimitive?.content ?: return@Tool listOf(
                    UIMessagePart.Text("Error: query is required")
                )

                val scan = obj["scan"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false

                val requestedIds = obj["knowledgeBaseIds"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive?.content
                } ?: emptyList()

                val allowedIds = getAllowedKnowledgeBaseIds()
                val targetIds = if (requestedIds.isEmpty()) {
                    allowedIds.toList()
                } else {
                    requestedIds.filter { it in allowedIds }
                }

                if (targetIds.isEmpty()) {
                    return@Tool listOf(
                        UIMessagePart.Text("No knowledge bases available. Please add documents to a knowledge base first.")
                    )
                }

                // 查询改写：把"最近对话 + 当前问题"改写为自包含 query（未配置时原样返回）
                val searchQuery = rewriteQuery(query)

                // 全量扫描模式：精确匹配全部 chunk，返回穷尽计数/列表（用于统计类查询）
                if (scan) {
                    return@Tool listOf(UIMessagePart.Text(scanAll(searchQuery, targetIds)))
                }

                // 解析 reranker（循环外用同一实例；未配置/失败时返回 null 回退 RRF 排序）
                val reranker = getReranker()

                // 每库解析 embedding 配置（维度须与该库 chunk 一致；未配置/失败 → null，该库走纯关键词检索）。
                // 多个库共用同一 embedding 模型时，同一 query 向量只生成一次（按 model.id 去重复用）。
                // 开启 HyDE 的知识库使用假设答案文本生成 embedding。
                val configByBase = targetIds.associateWith { getEmbeddingForBase(it) }
                val useHydeByBase = targetIds.associateWith { baseId ->
                    knowledgeManager.baseRepository.getById(baseId)?.useHyde ?: false
                }
                val hydeText = if (useHydeByBase.any { it.value }) {
                    try {
                        generateHydeText(searchQuery)
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }

                // key: "modelId:queryText"
                val embeddingByKey = mutableMapOf<String, FloatArray?>()
                for ((baseId, config) in configByBase) {
                    if (config == null) continue
                    val effectiveQuery = if (useHydeByBase[baseId] == true && !hydeText.isNullOrBlank()) {
                        hydeText
                    } else {
                        searchQuery
                    }
                    val key = "${config.model.id}:$effectiveQuery"
                    if (embeddingByKey.containsKey(key)) continue
                    embeddingByKey[key] = try {
                        val result = config.provider.generateEmbedding(
                            providerSetting = config.providerSetting,
                            params = EmbeddingGenerationParams(
                                model = config.model,
                                input = listOf(effectiveQuery),
                            )
                        )
                        result.embeddings.firstOrNull()?.toFloatArray()
                    } catch (e: Exception) {
                        null
                    }
                }

                // Search all target knowledge bases
                val allResults = mutableListOf<me.rerere.knowledge.retrieval.RetrievalResult>()
                for (baseId in targetIds) {
                    val base = knowledgeManager.baseRepository.getById(baseId) ?: continue
                    val config = configByBase[baseId]
                    val effectiveQuery = if (base.useHyde && !hydeText.isNullOrBlank()) {
                        hydeText
                    } else {
                        searchQuery
                    }
                    val key = config?.let { "${it.model.id}:$effectiveQuery" }
                    val queryEmbedding = key?.let { embeddingByKey[it] }
                    val results = knowledgeManager.search(
                        query = searchQuery,
                        queryEmbedding = queryEmbedding,
                        knowledgeBaseId = baseId,
                        topK = base.topK,
                        similarityThreshold = base.similarityThreshold,
                        reranker = reranker,
                        keywordWeight = 1f,
                    )
                    allResults.addAll(results)
                }

                allResults.sortByDescending { it.score }
                val maxTopK = targetIds.maxOf { id ->
                    knowledgeManager.baseRepository.getById(id)?.topK ?: 10
                }
                val topResults = allResults.take(maxTopK)

                if (topResults.isEmpty()) {
                    return@Tool listOf(
                        UIMessagePart.Text("No relevant information found in the knowledge base for query: \"$searchQuery\"")
                    )
                }

                // 一次性查询所有命中 chunk 的文档名，用于来源标注
                val chunkIds = topResults.map { it.chunk.id }
                val docNames = knowledgeManager.chunkDao
                    .getDocumentNamesByChunkIds(chunkIds)
                    .associate { it.chunkId to it.fileName }

                val resultText = buildString {
                    appendLine("Found ${topResults.size} relevant chunks from the knowledge base:\n")
                    topResults.forEachIndexed { index, result ->
                        val source = docNames[result.chunk.id]
                        val scoreText = when (result.scoreKind) {
                            "relevance" -> "相关度 ${"%.0f".format(result.score * 100)}%"
                            else -> "RRF ${"%.3f".format(result.score)}"
                        }
                        val content = result.chunk.content
                        appendLine("---")
                        appendLine("[${index + 1}] 来源: ${source ?: "未知文档"} (${scoreText})")
                        appendLine(content)
                        appendLine()
                    }
                }

                listOf(UIMessagePart.Text(resultText))
            }
        )
    }

    /**
     * 全量扫描模式：对目标知识库所有 chunk 按行做大小写不敏感匹配，
     * 返回去重后的命中行（含计数），用于统计/计数/穷尽列举类查询。
     * 按行匹配保证"一行一条记录"的名单类文档计数准确；
     * 去重消除 chunk overlap 造成的同一行重复计数。
     * 先按"所有词都命中"精确匹配；无结果时回退到"任一词命中"，避免 query 含泛词时漏召回。
     */
    private suspend fun scanAll(query: String, targetIds: List<String>): String {
        // 清洗引号，避免 "xxx" 匹配不到 xxx
        val cleanedQuery = query.replace(Regex("[\"']"), "").trim()
        val terms = cleanedQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return "No matches for empty query."

        val MAX_HITS = 100
        val hits = LinkedHashSet<String>()
        val perBase = mutableMapOf<String, Int>()

        for (baseId in targetIds) {
            val chunks = knowledgeManager.chunkDao.getByKnowledgeBaseId(baseId)
            val baseHits = LinkedHashSet<String>()
            for (chunk in chunks) {
                for (line in chunk.content.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.isBlank()) continue
                    if (terms.all { term -> trimmed.contains(term, ignoreCase = true) }) {
                        baseHits.add(trimmed)
                        hits.add(trimmed)
                    }
                }
            }
            if (baseHits.isNotEmpty()) {
                perBase[baseId] = baseHits.size
            }
        }

        // 全词匹配无结果 → 回退到任一词命中
        if (hits.isEmpty()) {
            for (baseId in targetIds) {
                val chunks = knowledgeManager.chunkDao.getByKnowledgeBaseId(baseId)
                val baseHits = LinkedHashSet<String>()
                for (chunk in chunks) {
                    for (line in chunk.content.lines()) {
                        val trimmed = line.trim()
                        if (trimmed.isBlank()) continue
                        if (terms.any { term -> trimmed.contains(term, ignoreCase = true) }) {
                            baseHits.add(trimmed)
                            hits.add(trimmed)
                        }
                    }
                }
                if (baseHits.isNotEmpty()) {
                    perBase[baseId] = baseHits.size
                }
            }
        }

        if (hits.isEmpty()) {
            return "No matches found in the knowledge base for query: \"$cleanedQuery\""
        }

        val total = hits.size
        return buildString {
            appendLine("Scan result: found $total matches for \"$cleanedQuery\" in the knowledge base (deduplicated).")
            if (perBase.size > 1) {
                perBase.forEach { (baseId, count) ->
                    appendLine("- knowledge base $baseId: $count matches")
                }
            }
            appendLine()
            hits.take(MAX_HITS).forEachIndexed { index, hit ->
                appendLine("${index + 1}. $hit")
            }
            if (total > MAX_HITS) {
                appendLine()
                appendLine("... and ${total - MAX_HITS} more. Total: $total")
            }
        }
    }

    /**
     * 创建一个独立的 `kb_list` 工具，让模型动态获取当前可访问的知识库列表。
     */
    fun createListTool(): Tool {
        return Tool(
            name = "kb_list",
            description = "List all knowledge bases available to the current assistant. " +
                    "Call this first to discover which knowledge bases exist and their IDs before calling kb_search.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject { },
                    required = emptyList()
                )
            },
            needsApproval = { false },
            execute = {
                val bases = buildList {
                    val allowedIds = getAllowedKnowledgeBaseIds()
                    for (baseId in allowedIds) {
                        val base = runCatching {
                            runBlocking { knowledgeManager.baseRepository.getById(baseId) }
                        }.getOrNull()
                        if (base != null) {
                            add(base)
                        } else {
                            // 即使查询失败也保留 ID，让模型知道它存在但信息不可用
                            add(
                                KnowledgeBaseEntity(
                                    id = baseId,
                                    name = "Unknown",
                                )
                            )
                        }
                    }
                }

                val json = buildJsonArray {
                    bases.forEach { base ->
                        add(buildJsonObject {
                            put("id", base.id)
                            put("name", base.name)
                            put("description", base.description)
                        })
                    }
                }

                listOf(UIMessagePart.Text("Available knowledge bases: $json"))
            }
        )
    }

    private fun buildKnowledgeBaseList(): String {
        val bases = runBlocking {
            try {
                knowledgeManager.baseRepository.getAll().first()
            } catch (_: Exception) {
                return@runBlocking emptyList()
            }
        }
        if (bases.isEmpty()) return "None"
        return bases.joinToString("; ") { base ->
            val desc = if (base.description.isNotBlank()) " - ${base.description}" else ""
            "${base.name}$desc (id: ${base.id})"
        }
    }
}