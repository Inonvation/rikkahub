package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.ContextCompositionEntity

@Dao
interface ContextCompositionDAO {
    /** 每次生成请求都写入最新快照，直接 REPLACE 旧行 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ContextCompositionEntity)

    @Query("SELECT * FROM context_composition WHERE conversation_id = :conversationId")
    suspend fun getById(conversationId: String): ContextCompositionEntity?

    @Query("DELETE FROM context_composition WHERE conversation_id = :conversationId")
    suspend fun deleteByConversationId(conversationId: String)
}