package me.rerere.knowledge.data.entity

import androidx.room.ColumnInfo

data class KnowledgeBaseWithDocumentCount(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "description") val description: String,
    @ColumnInfo(name = "embedding_model_id") val embeddingModelId: String?,
    @ColumnInfo(name = "rerank_model_id") val rerankModelId: String?,
    @ColumnInfo(name = "chunk_size") val chunkSize: Int,
    @ColumnInfo(name = "chunk_overlap") val chunkOverlap: Int,
    @ColumnInfo(name = "chunk_strategy") val chunkStrategy: String,
    @ColumnInfo(name = "top_k") val topK: Int,
    @ColumnInfo(name = "similarity_threshold") val similarityThreshold: Float,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "error") val error: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "document_count") val documentCount: Int,
)