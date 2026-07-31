package me.rerere.knowledge.data.dao

import androidx.room.ColumnInfo

/**
 * chunk 与其所属文档文件名的关联结果，用于检索结果来源展示。
 */
data class ChunkDocumentName(
    @ColumnInfo(name = "chunkId")
    val chunkId: String,

    @ColumnInfo(name = "fileName")
    val fileName: String,
)
