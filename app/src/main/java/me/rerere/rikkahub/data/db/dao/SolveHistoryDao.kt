package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.SolveRecordEntity

@Dao
interface SolveHistoryDao {
    @Insert
    suspend fun insert(record: SolveRecordEntity)

    @Query("SELECT * FROM solve_history ORDER BY created_at DESC")
    fun listAll(): Flow<List<SolveRecordEntity>>

    @Query("SELECT * FROM solve_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SolveRecordEntity?

    @Query("SELECT * FROM solve_history WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<SolveRecordEntity>

    @Query("DELETE FROM solve_history WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM solve_history")
    suspend fun deleteAll()

    @Query("SELECT id FROM solve_history WHERE id NOT IN (" +
        "SELECT id FROM solve_history ORDER BY created_at DESC LIMIT :keep)")
    suspend fun listIdsBeyond(keep: Int): List<String>

    @Query(
        "DELETE FROM solve_history WHERE id NOT IN (" +
            "SELECT id FROM solve_history ORDER BY created_at DESC LIMIT :keep)"
    )
    suspend fun trimOlderThan(keep: Int)

    /**
     * 入库并裁剪到最近 [keep] 条，一个事务完成；返回被裁剪记录的 id，
     * 调用方据此同步删除对应图片文件（imagePath 与记录生命周期绑定，防止私有目录残留孤儿文件）。
     */
    @Transaction
    suspend fun insertAndTrim(record: SolveRecordEntity, keep: Int): List<String> {
        insert(record)
        val staleIds = listIdsBeyond(keep)
        trimOlderThan(keep)
        return staleIds
    }

    /** 更新某条记录的追问快照（JSON 数组字符串，整行覆盖；追问数少，快照写足够） */
    @Query("UPDATE solve_history SET follow_ups = :json WHERE id = :id")
    suspend fun updateFollowUps(id: String, json: String)
}
