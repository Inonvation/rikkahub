package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.TranslationRecordEntity

@Dao
interface TranslationHistoryDao {
    @Insert
    suspend fun insert(record: TranslationRecordEntity)

    @Query("SELECT * FROM translation_history ORDER BY created_at DESC")
    fun listAll(): Flow<List<TranslationRecordEntity>>

    @Query("SELECT * FROM translation_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TranslationRecordEntity?

    @Query("DELETE FROM translation_history WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM translation_history")
    suspend fun deleteAll()

    @Query(
        "DELETE FROM translation_history WHERE id NOT IN (" +
            "SELECT id FROM translation_history ORDER BY created_at DESC LIMIT :keep)"
    )
    suspend fun trimOlderThan(keep: Int)

    /**
     * 入库并裁剪到最近 [keep] 条，一个事务完成，
     * 避免高频翻译场景下历史无限膨胀（纯 UI 记录，无被引用数据，丢弃最老记录安全）。
     */
    @Transaction
    suspend fun insertAndTrim(record: TranslationRecordEntity, keep: Int) {
        insert(record)
        trimOlderThan(keep)
    }
}
