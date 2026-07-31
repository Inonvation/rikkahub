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
import me.rerere.knowledge.retrieval.Reranker
import me.rerere.knowledge.vector.toFloatArray
import me.rerere.ai.core.InputSchema

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class KnowledgeSearchTool(
    private val knowledgeManager: KnowledgeManager,
    private val getAllowedKnowledgeBaseIds: suspend () -> Set<String>,
    private val getEmbeddingProvider: suspend () -> Pair<Provider<ProviderSetting.OpenAI>, ProviderSetting.OpenAI>?,
    private val getEmbeddingModel: suspend () -> me.rerere.ai.provider.Model?,
    private val getReranker: suspend () -> Reranker?,
) {
    fun create(): Tool {
        return Tool(
            name = "kb_search",
            description = "Search the user's knowledge base for relevant information. " +
                    "Use this when the user asks about their documents, notes, or uploaded files. " +
                    "Returns the most relevant text chunks with their content. " +
                    "If the user asks for counting, statistics, or exhaustive listing (e.g. 'how many people', 'list all', 'which schools'), " +
                    "set scan=true to scan ALL chunks and return an exact count.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", "The search query to find relevant information in the knowledge base")
                        })
                        put("knowledgeBaseIds", buildJsonObject {
                            put("type", "array")
                            put("description", "List of knowledge base IDs to search. " +
                                    "Available: ${buildKnowledgeBaseList()}")
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

                // 全量扫描模式：精确匹配全部 chunk，返回穷尽计数/列表（用于统计类查询）
                if (scan) {
                    return@Tool listOf(UIMessagePart.Text(scanAll(query, targetIds)))
                }

                // Generate query embedding
                val (provider, setting) = getEmbeddingProvider() ?: return@Tool listOf(
                    UIMessagePart.Text("Error: No embedding provider configured")
                )
                val embeddingModel = getEmbeddingModel() ?: return@Tool listOf(
                    UIMessagePart.Text("Error: No embedding model configured")
                )

                val queryEmbedding = try {
                    val result = provider.generateEmbedding(
                        providerSetting = setting,
                        params = EmbeddingGenerationParams(
                            model = embeddingModel,
                            input = listOf(query),
                        )
                    )
                    result.embeddings.firstOrNull()?.toFloatArray()
                } catch (e: Exception) {
                    null
                }

                // 解析 reranker（循环外用同一实例；未配置/失败时返回 null 回退 RRF 排序）
                val reranker = getReranker()

                // Search all target knowledge bases
                val allResults = mutableListOf<me.rerere.knowledge.retrieval.RetrievalResult>()
                for (baseId in targetIds) {
                    val base = knowledgeManager.baseRepository.getById(baseId) ?: continue
                    val results = knowledgeManager.search(
                        query = query,
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
                val topResults = allResults.take(10)

                if (topResults.isEmpty()) {
                    return@Tool listOf(
                        UIMessagePart.Text("No relevant information found in the knowledge base for query: \"$query\"")
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
                        val content = result.snippet
                            // 去除 simple_snippet 的 [..] 高亮标记，给 LLM 干净文本
                            ?.replace("[", "")?.replace("]", "")
                            ?: result.chunk.content
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
        // 查询词按空白分词
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return "No matches for empty query."

        val MAX_HITS = 100
        val hits = LinkedHashSet<String>()
        val perBase = mutableMapOf<String, Int>()

        for (baseId in targetIds) {
            val chunks = knowledgeManager.chunkDao.getByKnowledgeBaseId(baseId)
            // 每库用 Set 去重计数（chunk overlap 会让同一行跨 chunk 重复）
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

        // 全词精确匹配无结果 → 回退到任一词命中（放宽），避免 query 含泛词（如"获奖"/"多少"）时漏召回
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
            return "No matches found in the knowledge base for query: \"$query\""
        }

        val total = hits.size
        return buildString {
            appendLine("Scan result: found $total matches for \"$query\" in the knowledge base (deduplicated).")
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