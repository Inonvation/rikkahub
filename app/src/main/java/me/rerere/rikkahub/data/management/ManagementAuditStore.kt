package me.rerere.rikkahub.data.management

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.dao.ManagementAuditDao
import me.rerere.rikkahub.data.db.entity.ManagementAuditEntity

data class ManagementAuditEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val tool: String,
    val target: String,
    val result: String,
    val detail: String = "",
)

/** 管理模式写操作的内存审计，环形保留最近 [maxEntries] 条，进程重启后清空。 */
class ManagementAuditStore(
    private val dao: ManagementAuditDao? = null,
    private val maxEntries: Int = 200,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _entries = MutableStateFlow<List<ManagementAuditEntry>>(emptyList())

    val entries: StateFlow<List<ManagementAuditEntry>> = _entries.asStateFlow()

    init {
        dao?.let { auditDao ->
            scope.launch {
                auditDao.observeRecent(maxEntries)
                    .onEach { list ->
                        _entries.value = list.map { it.toEntry() }
                    }
                    .collect()
            }
        }
    }

    @Synchronized
    fun record(
        tool: String,
        target: String,
        result: String,
        detail: String = "",
    ) {
        val entry = ManagementAuditEntry(
            tool = tool,
            target = target,
            result = result,
            detail = detail,
        )
        _entries.value = (
            _entries.value + entry
            ).takeLast(maxEntries)
        dao?.let { auditDao ->
            scope.launch {
                auditDao.insert(entry.toEntity())
            }
        }
    }

    @Synchronized
    fun recent(limit: Int = 50): List<ManagementAuditEntry> =
        _entries.value.takeLast(limit.coerceAtLeast(1)).reversed()

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        dao?.let { auditDao ->
            scope.launch {
                auditDao.clearAll()
            }
        }
    }
}

private fun ManagementAuditEntry.toEntity() = ManagementAuditEntity(
    timestamp = timestamp,
    tool = tool,
    target = target,
    result = result,
    detail = detail,
)

private fun ManagementAuditEntity.toEntry() = ManagementAuditEntry(
    timestamp = timestamp,
    tool = tool,
    target = target,
    result = result,
    detail = detail,
)
