package me.rerere.rikkahub.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.ui.pages.study.vocabulary.VocabularySettings

private val Context.vocabularySettingsStore by preferencesDataStore(
    name = "vocabulary_settings",
)

/**
 * 生词面板设置持久化。独立 DataStore，不侵入主 Settings 聚合类。
 */
class VocabularySettingsStore(private val context: Context) {
    companion object {
        private val COOLDOWN = intPreferencesKey("cooldown_seconds")
        private val SORT_BY = stringPreferencesKey("sort_by")
        private val AUTO_SPEAK = booleanPreferencesKey("auto_speak_on_card_tap")
        private val SPEAK_EXAMPLE = booleanPreferencesKey("speak_example")
    }

    val settingsFlow: Flow<VocabularySettings> = context.vocabularySettingsStore.data
        .map { preferences ->
            VocabularySettings(
                cooldownSeconds = preferences[COOLDOWN] ?: 3,
                sortBy = preferences[SORT_BY] ?: "time",
                autoSpeakOnCardTap = preferences[AUTO_SPEAK] ?: false,
                speakExample = preferences[SPEAK_EXAMPLE] ?: false,
            )
        }

    suspend fun update(settings: VocabularySettings) {
        context.vocabularySettingsStore.edit { preferences ->
            preferences[COOLDOWN] = settings.cooldownSeconds
            preferences[SORT_BY] = settings.sortBy
            preferences[AUTO_SPEAK] = settings.autoSpeakOnCardTap
            preferences[SPEAK_EXAMPLE] = settings.speakExample
        }
    }
}
