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
    private val generateMultiQueries: suspend (query: String) -> List<String> = { emptyList() },
) {
    fun create(): Tool {
        return Tool(
            name = "kb_search",
            description = "Search the user's knowledge base for relevant text chunks from uploaded documents.\n\n" +
                    "Choose the retrieval mode based on the query type:\n" +
                    "- \"hybrid\" (default): Combined semantic + keyword search, best for general questions and understanding content.\n" +
                    "- \"semantic\": Pure vector similarity search, best for conceptual queries, paraphrasing, or when exact keywords may differ.\n" +
                    "- \"keyword\": Pure keyword/term search, best for finding specific names, codes, IDs, or exact terminology.\n" +
                    "- \"scan\": Line-by-line exact/partial match, best for counting, listing, structured data lookups, or finding ALL occurrences of a term.\n\n" +
                    "You can call this tool multiple times with different modes and combine the results for complex queries.",
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
                        put("mode", buildJsonObject {
                            put("type", "string")
                            put("description", "Retrieval mode: \"hybrid\" (semantic+keyword, default), " +
                                    "\"semantic\" (concept/meaning search), " +
                                    "\"keyword\" (exact term search), " +
                                    "\"scan\" (line-by-line match for counting/listing).")
                        })
                        put("topK", buildJsonObject {
                            put("type", "integer")
                            put("description", "Max results to return, overrides the knowledge base default. Only for hybrid/semantic/keyword modes.")
                        })
                        put("keywordWeight", buildJsonObject {
                            put("type", "number")
                            put("description", "Keyword bias in hybrid mode: 0=pure semantic, 2=double keyword weight. Default: knowledge base setting.")
                        })
                        put("scan", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Deprecated: use mode=\"scan\" instead. Set true for exhaustive line-by-line matching.")
                        })
                        put("scanLimit", buildJsonObject {
                            put("type", "integer")
                            put("description", "Max results for scan mode. Default 100.")
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

                // 向后兼容：scan=true → mode="scan"
                val scanLegacy = obj["scan"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val mode = obj["mode"]?.jsonPrimitive?.content ?: if (scanLegacy) "scan" else "hybrid"
                val scanLimit = obj["scanLimit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 100

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

                val searchQuery = rewriteQuery(query)

                when (mode) {
                    "scan" -> return@Tool listOf(UIMessagePart.Text(scanAll(searchQuery, targetIds, scanLimit)))
                    "keyword" -> return@Tool listOf(UIMessagePart.Text(keywordSearchAll(searchQuery, targetIds)))
                    else -> {
                        // hybrid 或 semantic 需要 embedding
                        return@Tool listOf(UIMessagePart.Text(
                            semanticOrHybridSearch(searchQuery, targetIds, mode)
                        ))
                    }
                }
            }
        )
    }

    /**
     * Hybrid 或 Semantic 检索：生成 embedding → 多查询扩展（可选）→ 检索 → 合并
     */
    private suspend fun semanticOrHybridSearch(query: String, targetIds: List<String>, mode: String): String {
        val reranker = getReranker()

        val configByBase = targetIds.associateWith { getEmbeddingForBase(it) }
        val useHydeByBase = targetIds.associateWith { baseId ->
            knowledgeManager.baseRepository.getById(baseId)?.useHyde ?: false
        }
        val hydeText = if (useHydeByBase.any { it.value }) {
            try { generateHydeText(query) } catch (_: Exception) { null }
        } else null

        // 多查询扩展：在 hybrid/semantic 模式下生效
        val queries = mutableListOf<String>()
        val baseQuery = if (hydeText != null && useHydeByBase.any { it.value }) hydeText else query
        queries.add(baseQuery)
        for (baseId in targetIds) {
            val base = knowledgeManager.baseRepository.getById(baseId) ?: continue
            if (base.useMultiquery) {
                try {
                    queries.addAll(generateMultiQueries(query))
                } catch (_: Exception) { /* fallback to single query */ }
                break // 只生成一次 multi-query
            }
        }

        // 生成所有 query 的 embedding（按 model+query 去重）
        val embeddingByKey = mutableMapOf<String, FloatArray?>()
        for (q in queries) {
            for ((baseId, config) in configByBase) {
                if (config == null) continue
                val key = "${config.model.id}:$q"
                if (embeddingByKey.containsKey(key)) continue
                embeddingByKey[key] = try {
                    val result = config.provider.generateEmbedding(
                        providerSetting = config.providerSetting,
                        params = EmbeddingGenerationParams(
                            model = config.model,
                            input = listOf(q),
                        )
                    )
                    result.embeddings.firstOrNull()?.toFloatArray()
                } catch (e: Exception) { null }
            }
        }

        // 每个知识库 × 每个 query 检索
        val allResults = mutableListOf<me.rerere.knowledge.retrieval.RetrievalResult>()
        for (baseId in targetIds) {
            val base = knowledgeManager.baseRepository.getById(baseId) ?: continue
            val config = configByBase[baseId]

            val overrideTopK = if (base.topK > 0) base.topK else 10
            val overrideKeywordWeight = base.keywordWeight
            val overrideMmrLambda = base.mmrLambda

            for (q in queries) {
                val effectiveQuery = q
                val key = config?.let { "${it.model.id}:$effectiveQuery" }
                val queryEmbedding = key?.let { embeddingByKey[it] }

                if (mode == "semantic" && queryEmbedding != null) {
                    val results = knowledgeManager.semanticSearch(
                        queryEmbedding = queryEmbedding,
                        knowledgeBaseId = baseId,
                        topK = overrideTopK,
                        similarityThreshold = base.similarityThreshold,
                        reranker = reranker,
                        mmrLambda = overrideMmrLambda,
                    )
                    allResults.addAll(results)
                } else {
                    val results = knowledgeManager.search(
                        query = effectiveQuery,
                        queryEmbedding = queryEmbedding,
                        knowledgeBaseId = baseId,
                        topK = overrideTopK,
                        similarityThreshold = base.similarityThreshold,
                        reranker = reranker,
                        keywordWeight = overrideKeywordWeight,
                        mmrLambda = overrideMmrLambda,
                    )
                    allResults.addAll(results)
                }
            }
        }

        // 多查询结果合并：按 score 降序，去重
        allResults.sortByDescending { it.score }
        val seenIds = mutableSetOf<String>()
        val deduped = allResults.filter { seenIds.add(it.chunk.id) }

        val maxTopK = targetIds.maxOf { id ->
            knowledgeManager.baseRepository.getById(id)?.topK ?: 10
        }
        val topResults = deduped.take((maxTopK * queries.size).coerceAtMost(50))

        if (topResults.isEmpty()) {
            return "No relevant information found in the knowledge base for query: \"$query\""
        }

        return formatResults(topResults, targetIds)
    }

    /**
     * 纯关键词检索（FTS5/BM25），不生成 embedding。
     */
    private suspend fun keywordSearchAll(query: String, targetIds: List<String>): String {
        val allResults = mutableListOf<me.rerere.knowledge.retrieval.RetrievalResult>()
        for (baseId in targetIds) {
            val base = knowledgeManager.baseRepository.getById(baseId) ?: continue
            val results = knowledgeManager.keywordSearch(
                query = query,
                knowledgeBaseId = baseId,
                topK = base.topK,
            )
            allResults.addAll(results)
        }
        allResults.sortByDescending { it.score }

        if (allResults.isEmpty()) {
            return "No keyword matches found in the knowledge base for query: \"$query\""
        }

        return formatResults(allResults, targetIds)
    }

    /**
     * 格式化检索结果，包含上下文窗口扩展。
     */
    private suspend fun formatResults(
        results: List<me.rerere.knowledge.retrieval.RetrievalResult>,
        targetIds: List<String>,
    ): String {
        // Small-to-Big：解析父块，用大粒度内容替换子块内容
        val resolvedResults = resolveParentChunks(results)

        // 上下文窗口扩展
        val expandedResults = expandContextWindow(resolvedResults, targetIds)

        val chunkIds = expandedResults.map { it.chunk.id }
        val docNames = knowledgeManager.chunkDao
            .getDocumentNamesByChunkIds(chunkIds)
            .associate { it.chunkId to it.fileName }

        return buildString {
            appendLine("Found ${results.size} relevant chunks from the knowledge base:\n")
            expandedResults.forEachIndexed { index, result ->
                val source = docNames[result.chunk.id]
                val scoreText = when (result.scoreKind) {
                    "relevance" -> "相关度 ${"%.0f".format(result.score * 100)}%"
                    else -> "RRF ${"%.3f".format(result.score)}"
                }
                appendLine("---")
                appendLine("[${index + 1}] 来源: ${source ?: "未知文档"} (${scoreText})")
                appendLine(result.chunk.content)
                appendLine()
            }
        }
    }

    /**
     * Small-to-Big：将命中子块替换为父块内容，返回大粒度上下文。
     * 多个子块指向同一父块时去重。
     */
    private suspend fun resolveParentChunks(
        results: List<me.rerere.knowledge.retrieval.RetrievalResult>,
    ): List<me.rerere.knowledge.retrieval.RetrievalResult> {
        val parentIds = results.mapNotNull { it.chunk.parentChunkId }.toSet()
        if (parentIds.isEmpty()) return results

        val parentChunks = knowledgeManager.chunkDao.getByIds(parentIds.toList())
            .associateBy { it.id }

        val seenParentIds = mutableSetOf<String>()
        val resolved = mutableListOf<me.rerere.knowledge.retrieval.RetrievalResult>()

        for (result in results) {
            val parentId = result.chunk.parentChunkId
            if (parentId != null) {
                val parent = parentChunks[parentId]
                if (parent != null && seenParentIds.add(parentId)) {
                    resolved.add(result.copy(chunk = parent))
                }
            } else {
                resolved.add(result)
            }
        }

        return resolved
    }

    /**
     * 上下文窗口扩展：对每个命中 chunk，拉取同一文档的前后 N 个 chunk。
     * 去重，保持原始排序。
     */
    private suspend fun expandContextWindow(
        results: List<me.rerere.knowledge.retrieval.RetrievalResult>,
        targetIds: List<String>,
    ): List<me.rerere.knowledge.retrieval.RetrievalResult> {
        // 从第一个知识库读取 context_window 配置（所有知识库共享同一配置）
        val contextWindow = targetIds.firstNotNullOfOrNull { id ->
            knowledgeManager.baseRepository.getById(id)
        }?.contextWindow ?: 0
        if (contextWindow <= 0) return results

        val expanded = mutableListOf<me.rerere.knowledge.retrieval.RetrievalResult>()
        val seenIds = mutableSetOf<String>()

        for (result in results) {
            // 添加相邻 chunk
            val adjacentChunks = knowledgeManager.chunkDao.getAdjacentChunks(
                documentId = result.chunk.documentId,
                minIndex = result.chunk.chunkIndex - contextWindow,
                maxIndex = result.chunk.chunkIndex + contextWindow,
            )
            for (chunk in adjacentChunks) {
                if (seenIds.add(chunk.id)) {
                    expanded.add(
                        me.rerere.knowledge.retrieval.RetrievalResult(
                            chunk = chunk,
                            score = result.score,
                            rank = 0,
                            scoreKind = "context",
                            snippet = null,
                        )
                    )
                }
            }

            // 添加原始命中 chunk
            if (seenIds.add(result.chunk.id)) {
                expanded.add(result)
            }
        }

        return expanded
    }

    /**
     * 全量扫描模式：对目标知识库所有 chunk 按行做大小写不敏感匹配，
     * 返回去重后的命中行（含计数），用于统计/计数/穷尽列举类查询。
     */
    private suspend fun scanAll(query: String, targetIds: List<String>, limit: Int = 100): String {
        val cleanedQuery = query.replace(Regex("[\"']"), "").trim()
        val terms = cleanedQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return "No matches for empty query."

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
            hits.take(limit).forEachIndexed { index, hit ->
                appendLine("${index + 1}. $hit")
            }
            if (total > limit) {
                appendLine()
                appendLine("... and ${total - limit} more. Total: $total")
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
}