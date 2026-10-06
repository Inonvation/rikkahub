package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * V10：「HTML 转 Markdown」本地工具移除后的残留数据清理。
 *
 * 与 V9 共用 [cleanRemovedFeatureValues]：已升级到 v9 的用户由此步清理助手配置里残留的
 * `html_to_markdown` 本地工具项；从更低版本直升的用户由 V9 一次清干净（本步为空操作）。
 */
class PreferenceStoreV10Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 10
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs.cleanRemovedFeatureValues()
        prefs[SettingsStore.VERSION] = 10
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}
