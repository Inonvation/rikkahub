package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import kotlin.time.Duration.Companion.days

/**
 * 基于文件的 TodoList 持久化存储，todo 的唯一状态源。
 * 一个 conversation 一个 JSON 文件，放在 filesDir/todos/ 下。
 * UI / 提醒注入都从这里读，不再从对话消息里反查 todo_write 调用。
 */
class TodoStorage(private val context: Context) {
    private val todoDir = File(context.filesDir, "todos")

    // 内存态 + 变更信号：UI 订阅它实时刷新；进程内多次 save 只触发一次流更新
    private val versions = MutableStateFlow<Map<String, Long>>(emptyMap())

    init {
        todoDir.mkdirs()
        cleanOrphanedFiles()
    }

    fun save(conversationId: String, todoList: TodoList) {
        getFile(conversationId).writeText(JsonInstant.encodeToString(todoList))
        versions.update { it + (conversationId to (it[conversationId] ?: 0L) + 1) }
    }

    fun load(conversationId: String): TodoList? {
        val file = getFile(conversationId)
        if (!file.exists()) return null
        return runCatching {
            JsonInstant.decodeFromString<TodoList>(file.readText())
        }.getOrNull()
    }

    /** 订阅某个会话的 todo 状态；写入时推送新值，无 todo 时推 null。 */
    fun loadAsFlow(conversationId: String): Flow<TodoList?> =
        versions.map { _ -> load(conversationId) }

    fun delete(conversationId: String) {
        getFile(conversationId).delete()
        getReminderFile(conversationId).delete()
        versions.update { it - conversationId }
    }

    /** 保存"上次注入 todo 提醒时已累计的工具步数"，供提醒冷却使用（防提醒贬值）。 */
    fun saveReminderStep(conversationId: String, step: Int) {
        getReminderFile(conversationId).writeText(step.toString())
    }

    /** 读取上次提醒基线；从未提醒过或文件损坏时返回 null。 */
    fun loadReminderStep(conversationId: String): Int? {
        val file = getReminderFile(conversationId)
        if (!file.exists()) return null
        return file.readText().toIntOrNull()
    }

    private fun getFile(conversationId: String): File {
        return File(todoDir, "$conversationId.json")
    }

    private fun getReminderFile(conversationId: String): File {
        return File(todoDir, "$conversationId.reminder")
    }

    /** 清理 30 天未修改的孤儿文件 */
    private fun cleanOrphanedFiles() {
        val cutoff = System.currentTimeMillis() - 30.days.inWholeMilliseconds
        todoDir.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }
}
