package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANTS
import me.rerere.rikkahub.data.datastore.ENGLISH_TUTOR_ID
import me.rerere.rikkahub.data.datastore.MATH_TUTOR_ID
import me.rerere.rikkahub.data.datastore.MECHANICS_TUTOR_ID
import me.rerere.rikkahub.data.datastore.POLITICS_TUTOR_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

class PreferenceStoreV6Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 6
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        val assistantsJson = prefs[SettingsStore.ASSISTANTS]
        if (assistantsJson != null) {
            val migrated = refreshBuiltinAssistants(assistantsJson)
            if (migrated != assistantsJson) {
                prefs[SettingsStore.ASSISTANTS] = migrated
            }
        }

        prefs[SettingsStore.VERSION] = 6
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

internal fun refreshBuiltinAssistants(assistantsJson: String): String {
    return runCatching {
        val root = JsonInstant.parseToJsonElement(assistantsJson) as? JsonArray
            ?: return@runCatching assistantsJson

        val defaultTutors = mapOf(
            ENGLISH_TUTOR_ID.toString() to DEFAULT_ASSISTANTS.first { it.id == ENGLISH_TUTOR_ID },
            MATH_TUTOR_ID.toString() to DEFAULT_ASSISTANTS.first { it.id == MATH_TUTOR_ID },
            POLITICS_TUTOR_ID.toString() to DEFAULT_ASSISTANTS.first { it.id == POLITICS_TUTOR_ID },
            MECHANICS_TUTOR_ID.toString() to DEFAULT_ASSISTANTS.first { it.id == MECHANICS_TUTOR_ID },
        )

        var changed = false
        val refreshed = root.map { element ->
            val obj = element as? JsonObject ?: return@map element
            val id = obj["id"]?.jsonPrimitive?.contentOrNull
            val default = if (id != null) defaultTutors[id] else null
            if (default != null) {
                changed = true
                // Assistant → JSON 字符串 → JsonElement（避免 encodeToJsonElement 的 import/重载歧义）
                JsonInstant.parseToJsonElement(JsonInstant.encodeToString(default))
            } else {
                element
            }
        }
        if (!changed) assistantsJson else JsonInstant.encodeToString(JsonArray(refreshed))
    }.getOrElse { assistantsJson }
}
