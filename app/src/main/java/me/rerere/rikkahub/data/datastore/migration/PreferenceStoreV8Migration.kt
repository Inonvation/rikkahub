package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.withProviderIsolation
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/**
 * V8：修复历史「复制供应商」只换 provider.id、未重生成 Model.id 的脏数据。
 *
 * 两个同类型供应商（如两份阿里云百炼）若共享 Model.id：
 * - 模型选择弹窗按 model.id 高亮 → 同名模型两边同时显示选中
 * - findModelById 首中即返回 → 可能用到错误供应商的 apiKey/baseUrl
 *
 * 同时给同名供应商自动加「副本」后缀，避免列表/弹窗里名称混在一起。
 *
 * 一次性迁移用随机 UUID 重生成；落盘后加载期不会再改。
 */
class PreferenceStoreV8Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 8
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        val providersJson = prefs[SettingsStore.PROVIDERS]
        if (providersJson != null) {
            val migrated = migrateProvidersJson(providersJson)
            if (migrated != providersJson) {
                prefs[SettingsStore.PROVIDERS] = migrated
                // lastKnownGood 若存在，同步修复，避免回退到脏快照
                prefs[SettingsStore.PROVIDERS_LKG]?.let { lkg ->
                    prefs[SettingsStore.PROVIDERS_LKG] = migrateProvidersJson(lkg)
                }
            }
        }

        prefs[SettingsStore.VERSION] = 8
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

internal fun migrateProvidersJson(providersJson: String): String {
    return runCatching {
        val providers = JsonInstant.decodeFromString<List<ProviderSetting>>(providersJson)
        val isolated = providers.withProviderIsolation { _, _ -> Uuid.random() }
        if (isolated == providers) providersJson else JsonInstant.encodeToString(isolated)
    }.getOrDefault(providersJson)
}
