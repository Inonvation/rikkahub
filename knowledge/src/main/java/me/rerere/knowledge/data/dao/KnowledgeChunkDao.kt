package me.rerere.knowledge.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.knowledge.data.entity.KnowledgeChunkEntity

@Dao
interface KnowledgeChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<KnowledgeChunkEntity>)

    @Query("SELECT * FROM knowledge_chunk WHERE knowledge_base_id = :knowledgeBaseId ORDER BY document_id, chunk_index")
    suspend fun getByKnowledgeBaseId(knowledgeBaseId: String): List<KnowledgeChunkEntity>

    /**
     * 轻量投影：只取 id / embedding / 定位字段，不取 content 全文。
     * 供向量缓存加载使用——检索只算余弦相似度，内容在命中后回表取。
     */
    @Query(
        "SELECT id, document_id, knowledge_base_id, chunk_index, embedding " +
            "FROM knowledge_chunk WHERE knowledge_base_id = :knowledgeBaseId"
    )
    suspend fun getVectorsByKnowledgeBaseId(knowledgeBaseId: String): List<ChunkVectorRow>

    @Query("SELECT * FROM knowledge_chunk WHERE id IN (:chunkIds)")
    suspend fun getByChunkIds(chunkIds: List<String>): List<KnowledgeChunkEntity>

    @Query("SELECT * FROM knowledge_chunk WHERE document_id = :documentId ORDER BY chunk_index")
    suspend fun getByDocumentId(documentId: String): List<KnowledgeChunkEntity>

    /**
     * 子串精确匹配：content 含检索词原样的 chunk 必然命中。
     * 兜底 FTS 召回不全时使用（救援模式），LIMIT 约束常见词的全表扫描成本。
     */
    @Query(
        "SELECT * FROM knowledge_chunk WHERE knowledge_base_id = :knowledgeBaseId " +
            "AND content LIKE '%' || :query || '%' LIMIT :limit"
    )
    suspend fun searchBySubstring(knowledgeBaseId: String, query: String, limit: Int): List<KnowledgeChunkEntity>

    @Query("DELETE FROM knowledge_chunk WHERE document_id = :documentId")
    suspend fun deleteByDocumentId(documentId: String)

    @Query("DELETE FROM knowledge_chunk WHERE knowledge_base_id = :knowledgeBaseId")
    suspend fun deleteByKnowledgeBaseId(knowledgeBaseId: String)

    @Query("SELECT COUNT(*) FROM knowledge_chunk WHERE knowledge_base_id = :knowledgeBaseId")
    suspend fun countByKnowledgeBaseId(knowledgeBaseId: String): Int

    /** 获取某知识库下所有文档 ID（含无 chunk 的文档），用于 FTS 增量对账。 */
    @Query("SELECT DISTINCT document_id FROM knowledge_chunk WHERE knowledge_base_id = :knowledgeBaseId")
    suspend fun getDocumentIdsByKnowledgeBaseId(knowledgeBaseId: String): List<String>

    /**
     * 根据 chunk 列表查询所属文档文件名，返回 chunkId -> fileName 映射。
     */
    @Query(
        "SELECT kc.id AS chunkId, kd.file_name AS fileName " +
            "FROM knowledge_chunk kc " +
            "JOIN knowledge_document kd ON kc.document_id = kd.id " +
            "WHERE kc.id IN (:chunkIds)"
    )
    suspend fun getDocumentNamesByChunkIds(chunkIds: List<String>): List<ChunkDocumentName>

    /**
     * 获取同一文档中指定 chunk 前后的相邻 chunk，用于上下文窗口扩展。
     */
    @Query(
        "SELECT * FROM knowledge_chunk WHERE document_id = :documentId " +
            "AND chunk_index >= :minIndex AND chunk_index <= :maxIndex " +
            "ORDER BY chunk_index"
    )
    suspend fun getAdjacentChunks(documentId: String, minIndex: Int, maxIndex: Int): List<KnowledgeChunkEntity>

    /**
     * 根据 ID 批量查询 chunk，用于 Small-to-Big 父块解析。
     */
    @Query("SELECT * FROM knowledge_chunk WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<KnowledgeChunkEntity>

    /**
     * 获取随机 chunk，用于抽背提问。如果指定 topic 则按内容模糊匹配。
     */
    @Query(
        "SELECT * FROM knowledge_chunk " +
            "WHERE (:topic IS NULL OR content LIKE '%' || :topic || '%') " +
            "ORDER BY RANDOM() LIMIT :limit"
    )
    suspend fun getRandomChunks(topic: String?, limit: Int): List<KnowledgeChunkEntity>

    @Query("SELECT * FROM knowledge_chunk ORDER BY RANDOM() LIMIT 5")
    suspend fun getRandomChunksFallback(): List<KnowledgeChunkEntity>
}

/**
 * 向量缓存用的轻量投影行：只含 id / embedding / 定位字段，不含 content 全文。
 */
data class ChunkVectorRow(
    @androidx.room.ColumnInfo(name = "id") val id: String,
    @androidx.room.ColumnInfo(name = "document_id") val documentId: String,
    @androidx.room.ColumnInfo(name = "knowledge_base_id") val knowledgeBaseId: String,
    @androidx.room.ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    @androidx.room.ColumnInfo(name = "embedding") val embedding: ByteArray?,
)