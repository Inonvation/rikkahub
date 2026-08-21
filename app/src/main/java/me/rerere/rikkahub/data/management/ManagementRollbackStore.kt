package me.rerere.rikkahub.data.management

import me.rerere.rikkahub.data.datastore.Settings

data class ManagementUndoSnapshot(
    val timestamp: Long = System.currentTimeMillis(),
    val tool: String,
    val target: String,
    val settings: Settings,
)

/** 管理模式设置类写操作的内存回滚快照，最多保留最近 [maxEntries] 次。 */
class ManagementRollbackStore(
    private val maxEntries: Int = 20,
) {
    private val entries = ArrayDeque<ManagementUndoSnapshot>()

    @Synchronized
    fun record(
        settings: Settings,
        tool: String,
        target: String,
    ) {
        if (entries.size >= maxEntries) {
            entries.removeFirst()
        }
        entries.addLast(
            ManagementUndoSnapshot(
                tool = tool,
                target = target,
                settings = settings,
            )
        )
    }

    @Synchronized
    fun undo(): ManagementUndoSnapshot? =
        if (entries.isEmpty()) null else entries.removeLast()
}
