package me.rerere.rikkahub.data.db.fts

import me.rerere.knowledge.data.entity.KnowledgeChunkEntity
import me.rerere.rikkahub.data.db.AppDatabase

data class FtsHit(
    val chunkId: String,
    val documentId: String,
    val chunkIndex: Int,
    val rank: Int,
    val snippet: String?,
)

/**
 * 知识库 chunk 的 FTS5 全文索引（jieba 分词）。
 *
 * knowledge_chunk 表是唯一事实源，本表是可丢弃的派生索引：
 * 每次检索前通过 count 对账，失配才重建，天然覆盖新增/删除/重试/升级前的旧数据。
 * 与 MessageFtsManager 共用 libsimple 扩展（simple tokenizer / jieba_query / simple_snippet）。
 */
class KnowledgeChunkFtsManager(
    private val database: AppDatabase,
) {
    private val db get() = database.openHelper.writableDatabase

    @Volatile
    private var available: Boolean? = null

    /** 懒建表；失败返回 false（由调用方回退 BM25）。 */
    fun ensureIndex(): Boolean {
        available?.let { return it }
        return synchronized(this) {
            available?.let { return it }
            try {
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS knowledge_chunk_fts USING fts5(
                        content,
                        chunk_id UNINDEXED,
                        knowledge_base_id UNINDEXED,
                        document_id UNINDEXED,
                        chunk_index UNINDEXED,
                        tokenize = 'simple'
                    )
                    """.trimIndent()
                )
                true.also { available = true }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "ensureIndex failed", e)
                false.also { available = false }
            }
        }
    }

    fun countIndexed(knowledgeBaseId: String): Long {
        return db.query(
            "SELECT COUNT(*) FROM knowledge_chunk_fts WHERE knowledge_base_id = ?",
            arrayOf(knowledgeBaseId)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    /** 重建单个知识库的 FTS 索引（delete + insert）。 */
    @Synchronized
    fun rebuildBase(knowledgeBaseId: String, chunks: List<KnowledgeChunkEntity>) {
        db.execSQL(
            "DELETE FROM knowledge_chunk_fts WHERE knowledge_base_id = ?",
            arrayOf(knowledgeBaseId)
        )
        db.beginTransaction()
        try {
            chunks.forEach { chunk ->
                db.execSQL(
                    "INSERT INTO knowledge_chunk_fts(content, chunk_id, knowledge_base_id, document_id, chunk_index) VALUES (?, ?, ?, ?, ?)",
                    arrayOf(
                        chunk.content,
                        chunk.id,
                        chunk.knowledgeBaseId,
                        chunk.documentId,
                        chunk.chunkIndex.toString(),
                    )
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 关键词检索，返回 FTS5 内置 BM25 相关度排序（rank 升序）的命中。 */
    fun search(query: String, knowledgeBaseId: String, topK: Int): List<FtsHit> {
        val hits = mutableListOf<FtsHit>()
        db.query(
            """
            SELECT chunk_id, document_id, chunk_index,
                   simple_snippet(knowledge_chunk_fts, 0, '[', ']', '...', 30) AS snippet
            FROM knowledge_chunk_fts
            WHERE knowledge_base_id = ? AND content MATCH jieba_query(?)
            ORDER BY rank
            LIMIT ?
            """.trimIndent(),
            arrayOf(knowledgeBaseId, query, topK.toString())
        ).use { cursor ->
            var rank = 0
            while (cursor.moveToNext()) {
                rank++
                hits.add(
                    FtsHit(
                        chunkId = cursor.getString(0),
                        documentId = cursor.getString(1),
                        chunkIndex = cursor.getInt(2),
                        rank = rank,
                        snippet = cursor.getString(3),
                    )
                )
            }
        }
        return hits
    }

    private companion object {
        const val TAG = "KnowledgeChunkFtsManager"
    }
}
