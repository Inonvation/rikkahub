package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.ai.prompts.ENGLISH_TUTOR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.MATH_TUTOR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.MECHANICS_TUTOR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.POLITICS_TUTOR_PROMPT
import me.rerere.rikkahub.data.datastore.ENGLISH_TUTOR_ID
import me.rerere.rikkahub.data.datastore.MATH_TUTOR_ID
import me.rerere.rikkahub.data.datastore.MECHANICS_TUTOR_ID
import me.rerere.rikkahub.data.datastore.POLITICS_TUTOR_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

class PreferenceStoreV5Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 5
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        val assistantsJson = prefs[SettingsStore.ASSISTANTS]
        if (assistantsJson != null) {
            val migrated = refreshTutorPrompts(assistantsJson)
            if (migrated != assistantsJson) {
                prefs[SettingsStore.ASSISTANTS] = migrated
            }
        }

        prefs[SettingsStore.VERSION] = 5
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

internal fun refreshTutorPrompts(assistantsJson: String): String {
    return runCatching {
        val root = JsonInstant.parseToJsonElement(assistantsJson) as? JsonArray
            ?: return@runCatching assistantsJson

        val tutorPrompts = mapOf(
            ENGLISH_TUTOR_ID.toString() to ENGLISH_TUTOR_PROMPT,
            MATH_TUTOR_ID.toString() to MATH_TUTOR_PROMPT,
            POLITICS_TUTOR_ID.toString() to POLITICS_TUTOR_PROMPT,
            MECHANICS_TUTOR_ID.toString() to MECHANICS_TUTOR_PROMPT,
        )

        var changed = false
        val refreshed = root.map { element ->
            val obj = element as? JsonObject ?: return@map element
            val id = obj["id"]?.jsonPrimitive?.contentOrNull
            val newPrompt = if (id != null) tutorPrompts[id] else null
            if (newPrompt != null) {
                changed = true
                JsonObject(obj + ("systemPrompt" to JsonPrimitive(newPrompt)))
            } else {
                element
            }
        }
        if (!changed) assistantsJson else JsonInstant.encodeToString(JsonArray(refreshed))
    }.getOrElse { assistantsJson }
}
