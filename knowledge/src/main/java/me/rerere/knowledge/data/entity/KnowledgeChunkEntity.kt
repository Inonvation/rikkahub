package me.rerere.knowledge.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "knowledge_chunk",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeDocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = KnowledgeBaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["knowledge_base_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("document_id"),
        Index("knowledge_base_id")
    ]
)
data class KnowledgeChunkEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "document_id")
    val documentId: String,

    @ColumnInfo(name = "knowledge_base_id")
    val knowledgeBaseId: String,

    @ColumnInfo(name = "chunk_index")
    val chunkIndex: Int,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB)
    val embedding: ByteArray? = null,

    @ColumnInfo(name = "token_count")
    val tokenCount: Int = 0,

    @ColumnInfo(name = "metadata")
    val metadata: String = "{}",

    @ColumnInfo(name = "context_prefix", defaultValue = "")
    val contextPrefix: String = "",

    @ColumnInfo(name = "parent_chunk_id")
    val parentChunkId: String? = null,
)