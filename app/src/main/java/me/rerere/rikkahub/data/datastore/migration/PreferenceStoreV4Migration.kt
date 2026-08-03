package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

class PreferenceStoreV4Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 4
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        // 将原来每个助手独立的 enableTodoList 迁移为全局开关：
        // 如果当前选中助手的 enableTodoList 为 false，则全局开关也设为 false；否则默认 true。
        val selectedAssistantId = prefs[SettingsStore.SELECT_ASSISTANT]
        val assistantsJson = prefs[SettingsStore.ASSISTANTS]
        val todoListEnabled = if (selectedAssistantId != null && assistantsJson != null) {
            extractAssistantTodoListEnabled(assistantsJson, selectedAssistantId)
        } else {
            true
        }
        prefs[SettingsStore.TODO_LIST_ENABLED] = todoListEnabled

        prefs[SettingsStore.VERSION] = 4
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}

    private fun extractAssistantTodoListEnabled(assistantsJson: String, assistantId: String): Boolean {
        return runCatching {
            val root = JsonInstant.parseToJsonElement(assistantsJson) as? JsonArray
                ?: return@runCatching true

            root.forEach { assistant ->
                val assistantObj = assistant as? JsonObject ?: return@forEach
                val id = assistantObj["id"]?.jsonPrimitive?.content
                if (id == assistantId) {
                    val todoList = assistantObj["enableTodoList"]
                    if (todoList != null) {
                        return@runCatching todoList.jsonPrimitive.content != "false"
                    }
                }
            }
            true
        }.getOrDefault(true)
    }
}
