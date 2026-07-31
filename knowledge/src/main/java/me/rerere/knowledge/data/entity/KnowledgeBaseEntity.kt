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
    val chunkSize: Int = 1024,

    @ColumnInfo(name = "chunk_overlap")
    val chunkOverlap: Int = 200,

    @ColumnInfo(name = "chunk_strategy")
    val chunkStrategy: String = "fixed_size",

    @ColumnInfo(name = "top_k")
    val topK: Int = 10,

    @ColumnInfo(name = "similarity_threshold")
    val similarityThreshold: Float = 0f,

    @ColumnInfo(name = "status")
    val status: String = "completed",

    @ColumnInfo(name = "error")
    val error: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)