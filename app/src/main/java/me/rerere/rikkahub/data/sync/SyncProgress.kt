package me.rerere.rikkahub.data.sync

/** 进度视图中的单个同步单元（与预览项一一对应）。 */
data class SyncProgressItem(
    val relPath: String,
    val label: String,
    val size: Long = 0,
)

/**
 * 同步执行进度（确认同步后弹窗切换为进度视图，逐项展示实时状态）。
 * - [statusByPath]：relPath -> 最新状态；未出现的项视为 WAITING
 * - [done]：已完成（DONE 或 FAILED）的数量，驱动进度条与「已完成 X/N」
 * - [finished]：同步流程已结束（成功/失败/取消），弹窗显示结果并允许关闭
 */
data class SyncProgress(
    val items: List<SyncProgressItem> = emptyList(),
    val statusByPath: Map<String, SyncItemStatus> = emptyMap(),
    val done: Int = 0,
    val finished: Boolean = false,
    val success: Boolean? = null,
    val error: String? = null,
) {
    val total: Int get() = items.size

    val progress: Float
        get() = if (total == 0) 1f else done.toFloat() / total

    companion object {
        /** 由差异预览清单初始化进度项（全部等待中，顺序与预览一致）。 */
        fun fromPreview(preview: SyncPreview): SyncProgress {
            val all = preview.uploads + preview.updates + preview.deletions + preview.conflicts
            return SyncProgress(
                items = all.map { SyncProgressItem(it.relPath, it.label, it.size) },
            )
        }
    }
}
