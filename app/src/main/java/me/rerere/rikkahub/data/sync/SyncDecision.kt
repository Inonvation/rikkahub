package me.rerere.rikkahub.data.sync

/**
 * 增量同步决策表（纯函数，无 IO，可单测全覆盖）。
 *
 * 核心思路：本地对每个同步文件算 sha256，与 [SyncStateStore.syncedFiles][relPath]
 * 记录的 [FileSyncRecord] 比较，结合远端 tag（etag，不可用则退化 lastModified）判断动作。
 * ETag 与本地 SHA-256 算法/格式不同，因此必须用 syncedFiles 记录做中间桥梁。
 */
object SyncDecision {

    sealed interface Action {
        /** 上传本地文件到远端 + 更新记录 */
        data object Push : Action
        /** 从远端下载覆盖本地 + 更新记录 */
        data object Pull : Action
        /** 双端都改过：按 updatedAtMs 取新，败者留冲突副本 */
        data object Conflict : Action
        /** 无变化 */
        data object Skip : Action
        /** 本地已删除：传播删除远端 + 清除记录 */
        data object DeleteRemote : Action
    }

    data class Input(
        /** 本地文件是否存在 */
        val localExists: Boolean,
        /** 本地文件 sha256（存在时） */
        val localSha: String?,
        /** 本地文件最后修改时间（ms） */
        val localUpdatedAtMs: Long,
        /** syncedFiles[relPath] 上次确认同步记录，首次同步为 null */
        val record: FileSyncRecord?,
        /** 远端文件是否存在 */
        val remoteExists: Boolean,
        /** 远端 tag（etag；etag 为空时退化为 lastModifiedMs 字符串，统一由调用方生成） */
        val remoteTag: String?,
        /** 远端最后修改时间（ms，用于首次同步/冲突兜底） */
        val remoteLastModifiedMs: Long?,
    )

    /**
     * 决策表（原文 §5.2）：
     * - 远端不存在 → push
     * - 本地 == 记录 sha 且远端 tag != 记录 tag → pull（本地没变，远端被别的设备改）
     * - 本地 != 记录 sha 且远端 tag == 记录 tag → push（只有本地改过）
     * - 本地 != 记录 sha 且远端 tag != 记录 tag → conflict
     * - 本地 == 记录 sha 且远端 tag == 记录 tag → skip
     * 补充（删除传播）：
     * - 本地不存在 && 有记录 → deleteRemote（曾同步过，本地删则远端也删）
     * - 本地不存在 && 无记录 && 远端存在 → pull（首见远端文件）
     * - 双方都存在但无记录（首次）→ 时间戳新者胜（pull 兜底）
     */
    fun decide(input: Input): Action {
        val localSha = input.localSha
        val remoteTag = input.remoteTag
        val rec = input.record
        return when {
            // 两边都没有
            !input.localExists && !input.remoteExists -> Action.Skip

            // 远端不存在：本地为准，push（含曾同步后被远端删除的场景，恢复远端）
            !input.remoteExists -> Action.Push

            // 本地不存在 && 远端存在
            !input.localExists -> {
                if (rec != null) Action.DeleteRemote else Action.Pull
            }

            // 双方都存在
            rec == null -> {
                // 首次同步，无中间桥梁：时间戳新者胜；不可比时本地优先
                val remoteMs = input.remoteLastModifiedMs
                if (remoteMs != null && remoteMs > input.localUpdatedAtMs) Action.Pull else Action.Push
            }

            else -> when {
                localSha == rec.sha256 && remoteTag == rec.remoteTag -> Action.Skip
                localSha == rec.sha256 && remoteTag != rec.remoteTag -> Action.Pull
                localSha != rec.sha256 && remoteTag == rec.remoteTag -> Action.Push
                else -> Action.Conflict
            }
        }
    }
}
