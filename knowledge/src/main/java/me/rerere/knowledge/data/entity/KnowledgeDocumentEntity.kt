package me.rerere.knowledge.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "knowledge_document",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeBaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["knowledge_base_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("knowledge_base_id")
    ]
)
data class KnowledgeDocumentEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "knowledge_base_id")
    val knowledgeBaseId: String,

    @ColumnInfo(name = "file_name")
    val fileName: String,

    @ColumnInfo(name = "file_type")
    val fileType: String,

    @ColumnInfo(name = "file_path")
    val filePath: String,

    @ColumnInfo(name = "file_size")
    val fileSize: Long = 0,

    @ColumnInfo(name = "file_hash")
    val fileHash: String? = null,

    @ColumnInfo(name = "chunk_count")
    val chunkCount: Int = 0,

    @ColumnInfo(name = "status")
    val status: String = "pending",

    @ColumnInfo(name = "error")
    val error: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)