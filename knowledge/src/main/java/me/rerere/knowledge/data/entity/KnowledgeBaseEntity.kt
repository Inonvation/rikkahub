package me.rerere.knowledge.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "knowledge_base")
data class KnowledgeBaseEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "description", defaultValue = "")
    val description: String = "",

    @ColumnInfo(name = "embedding_model_id")
    val embeddingModelId: String? = null,

    @ColumnInfo(name = "rerank_model_id")
    val rerankModelId: String? = null,

    @ColumnInfo(name = "chunk_size")
    val chunkSize: Int = DEFAULT_CHUNK_SIZE,

    @ColumnInfo(name = "chunk_overlap")
    val chunkOverlap: Int = DEFAULT_CHUNK_OVERLAP,

    @ColumnInfo(name = "chunk_strategy")
    val chunkStrategy: String = DEFAULT_CHUNK_STRATEGY,

    @ColumnInfo(name = "top_k")
    val topK: Int = DEFAULT_TOP_K,

    @ColumnInfo(name = "similarity_threshold")
    val similarityThreshold: Float = 0f,

    @ColumnInfo(name = "use_hyde", defaultValue = "0")
    val useHyde: Boolean = false,

    @ColumnInfo(name = "status")
    val status: String = "completed",

    @ColumnInfo(name = "error")
    val error: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        /** 分块大小默认值（字符）。业界推荐 ~512 token，中文约 1 字符≈1 token。 */
        const val DEFAULT_CHUNK_SIZE = 512

        /** 分块重叠默认值（字符），约为 chunk size 的 10%。 */
        const val DEFAULT_CHUNK_OVERLAP = 50

        /** 默认分块策略，按句子切分，对中文友好。 */
        const val DEFAULT_CHUNK_STRATEGY = "sentence"

        /** 默认检索返回条数。 */
        const val DEFAULT_TOP_K = 10
    }
}