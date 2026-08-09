package me.rerere.rikkahub.data.sync

/**
 * 会话增量同步的本地数据访问抽象。
 *
 * App 侧由 [me.rerere.rikkahub.data.repository.ConversationRepository] 实现（包装 Room），
 * 测试侧用内存 fake 驱动 SyncManager 集成测试。
 */
interface ConversationSyncAccess {
    /** 本地全部会话的轻量索引条目（用于与云端 index 比对，判断哪些会话需要同步）。 */
    suspend fun listLocalIndexEntries(): List<ConversationIndexEntry>

    /**
     * 导出某会话为可上传的同步条目（含附件引用映射）。
     * 若会话 syncUpdatedAt 为 0（存量未参与同步），内部先初始化版本并落库，保证导出的条目版本稳定。
     * @return null 表示会话不存在。
     */
    suspend fun exportConversation(id: String): ConversationSyncItem?

    /**
     * 只读：某会话导出为同步条目后的字节大小（用于差异预览展示占用）。
     * 与 [exportConversation] 不同，不做版本初始化、不写任何状态。
     * @return null 表示会话不存在。
     */
    suspend fun conversationSize(id: String): Long?

    /**
     * 下载合并：把云端 [ConversationSyncItem] 合并进本地 DB。
     * 消息级 LWW（syncUpdatedAt 大者胜，墓碑优先），会话元数据同样 LWW，
     * 附件引用按下载方向映射回本地路径，合并后推进本地会话版本（下次可上传合并结果）。
     * @return 是否发生了合并（item 合法且被应用）。
     */
    suspend fun mergeConversation(item: ConversationSyncItem): Boolean

    /** 应用云端会话删除（index.deleted 中、本地仍存在的会话）。幂等，不重复记墓碑。 */
    suspend fun deleteRemoteConversation(id: String)

    /** 墓碑已发布到云端 index 后，清理本地待传播墓碑（避免无限累积 + 每次同步重写 index）。 */
    suspend fun clearLocalTombstones(ids: Set<String>)

    /** 本地已删除会话的墓碑（用于传播删除到云端）。 */
    suspend fun localDeletedTombstones(): List<ConversationTombstone>
}
