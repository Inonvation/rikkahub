package me.rerere.rikkahub.data.db.fts

import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.MemoryEntity

data class MemoryFtsHit(
    val memoryId: Int,
    val rank: Int,
)

/**
 * 记忆的 FTS5 全文索引（jieba 分词）。
 *
 * memoryentity 表是唯一事实源，本表是可丢弃的派生索引：
 * 每次检索前通过 count 对账，失配才重建，天然覆盖增删改（编辑会 invalidate 触发重建）。
 * 与 KnowledgeChunkFtsManager 共用 libsimple 扩展（simple tokenizer / jieba_query）。
 */
class MemoryFtsManager(
    private val database: AppDatabase,
) {
    private val db get() = database.openHelper.writableDatabase

    @Volatile
    private var available: Boolean? = null

    /** 懒建表；失败返回 false（由调用方回退「最近记忆」兜底）。 */
    fun ensureIndex(): Boolean {
        available?.let { return it }
        return synchronized(this) {
            available?.let { return it }
            try {
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts5(
                        content,
                        assistant_id UNINDEXED,
                        memory_id UNINDEXED,
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

    fun countIndexed(assistantId: String): Long {
        if (!ensureIndex()) return 0L
        return db.query(
            "SELECT COUNT(*) FROM memory_fts WHERE assistant_id = ?",
            arrayOf(assistantId)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    /** 重建某个 assistant 的记忆索引（delete + insert）。 */
    @Synchronized
    fun rebuild(assistantId: String, memories: List<MemoryEntity>) {
        if (!ensureIndex()) return
        db.execSQL(
            "DELETE FROM memory_fts WHERE assistant_id = ?",
            arrayOf(assistantId)
        )
        db.beginTransaction()
        try {
            memories.forEach { memory ->
                db.execSQL(
                    "INSERT INTO memory_fts(content, assistant_id, memory_id) VALUES (?, ?, ?)",
                    arrayOf(
                        memory.content,
                        memory.assistantId,
                        memory.id.toString(),
                    )
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 写时失效：编辑/删除只删索引，下次检索 count 对账发现失配后重建。
     * 避免「内容变了但 count 没变」的对账盲区。
     */
    fun invalidate(assistantId: String) {
        if (!ensureIndex()) return
        db.execSQL(
            "DELETE FROM memory_fts WHERE assistant_id = ?",
            arrayOf(assistantId)
        )
    }

    /** 关键词检索，返回 FTS5 内置 BM25 相关度排序（rank 升序）的命中。 */
    fun search(assistantId: String, query: String, topK: Int): List<MemoryFtsHit> {
        if (!ensureIndex()) return emptyList()
        val hits = mutableListOf<MemoryFtsHit>()
        db.query(
            """
            SELECT memory_id
            FROM memory_fts
            WHERE assistant_id = ? AND content MATCH jieba_query(?)
            ORDER BY rank
            LIMIT ?
            """.trimIndent(),
            arrayOf(assistantId, query, topK.toString())
        ).use { cursor ->
            var rank = 0
            while (cursor.moveToNext()) {
                rank++
                hits.add(
                    MemoryFtsHit(
                        memoryId = cursor.getInt(0),
                        rank = rank,
                    )
                )
            }
        }
        return hits
    }

    private companion object {
        const val TAG = "MemoryFtsManager"
    }
}
