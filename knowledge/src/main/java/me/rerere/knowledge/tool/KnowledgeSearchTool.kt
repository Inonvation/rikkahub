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
import me.rerere.knowledge.vector.toFloatArray
import me.rerere.ai.core.InputSchema

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class KnowledgeSearchTool(
    private val knowledgeManager: KnowledgeManager,
    private val getAllowedKnowledgeBaseIds: suspend () -> Set<String>,
    private val getEmbeddingProvider: suspend () -> Pair<Provider<ProviderSetting.OpenAI>, ProviderSetting.OpenAI>?,
    private val getEmbeddingModel: suspend () -> me.rerere.ai.provider.Model?,
) {
    fun create(): Tool {
        return Tool(
            name = "kb_search",
            description = "Search the user's knowledge base for relevant information. " +
                    "Use this when the user asks about their documents, notes, or uploaded files. " +
                    "Returns the most relevant text chunks with their content.",
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

                // Search all target knowledge bases
                val allResults = mutableListOf<me.rerere.knowledge.retrieval.RetrievalResult>()
                for (baseId in targetIds) {
                    val base = knowledgeManager.baseRepository.getById(baseId) ?: continue
                    val results = knowledgeManager.search(
                        query = query,
                        queryEmbedding = queryEmbedding,
                        knowledgeBaseId = baseId,
                        topK = base.topK,
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

                val resultText = buildString {
                    appendLine("Found ${topResults.size} relevant chunks from the knowledge base:\n")
                    topResults.forEachIndexed { index, result ->
                        appendLine("---")
                        appendLine("[${index + 1}] ${result.chunk.content}")
                        appendLine()
                    }
                }

                listOf(UIMessagePart.Text(resultText))
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