package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import kotlin.time.Duration.Companion.days

/**
 * 基于文件的 TodoList 持久化存储。
 * 一个 conversation 一个 JSON 文件，放在 filesDir/todos/ 下。
 */
class TodoStorage(private val context: Context) {
    private val todoDir = File(context.filesDir, "todos")

    init {
        todoDir.mkdirs()
        cleanOrphanedFiles()
    }

    fun save(conversationId: String, todoList: TodoList) {
        getFile(conversationId).writeText(JsonInstant.encodeToString(todoList))
    }

    fun load(conversationId: String): TodoList? {
        val file = getFile(conversationId)
        if (!file.exists()) return null
        return runCatching {
            JsonInstant.decodeFromString<TodoList>(file.readText())
        }.getOrNull()
    }

    fun delete(conversationId: String) {
        getFile(conversationId).delete()
    }

    private fun getFile(conversationId: String): File {
        return File(todoDir, "$conversationId.json")
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