package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * V9：已移除功能的遗留取值清理（首次引入，清理逻辑见 [cleanRemovedFeatureValues]）。
 *
 * 触发背景是「设备能力」功能整体移除：助手的「本地工具」清单里可能还保存着已删除的
 * device_doctor/storage_cleaner/freeze_apps，自定义模式 / 内置模式覆盖的 capabilities 里
 * 可能还保存着已删除的 DEVICE_TOOLS——这些取值在枚举/密封类条目删除后会让整条数据解码
 * 失败（助手被逐条隔离丢弃、自定义模式整表回退为空），所以在读取前做一次一次性 JSON 级过滤。
 *
 * 清理清单随后续功能移除（如「HTML 转 Markdown」工具）扩展；从更低版本直升的用户由此步一次清干净。
 */
class PreferenceStoreV9Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 9
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs.cleanRemovedFeatureValues()
        prefs[SettingsStore.VERSION] = 9
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}
