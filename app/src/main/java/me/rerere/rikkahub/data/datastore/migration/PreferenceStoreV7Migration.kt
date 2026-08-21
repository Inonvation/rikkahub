package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

/**
 * 用户要求思考冻结栏默认开启；数据被清后旧设置里存的是 false，
 * 只改代码默认值不会覆盖已落盘的值，因此升级时强制迁移为 true。
 */
class PreferenceStoreV7Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 7
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        val displayJson = prefs[SettingsStore.DISPLAY_SETTING]
        if (displayJson != null) {
            val migrated = runCatching {
                val root = JsonInstant.parseToJsonElement(displayJson).jsonObject.toMutableMap()
                root["thinkingFrozenBar"] = JsonPrimitive(true)
                JsonInstant.encodeToString(JsonObject(root))
            }.getOrNull() ?: displayJson
            if (migrated != displayJson) {
                prefs[SettingsStore.DISPLAY_SETTING] = migrated
            }
        }

        prefs[SettingsStore.VERSION] = 7
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}
