package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * V9：「设备能力」功能整体移除后的残留数据清理。
 *
 * 助手的「本地工具」清单里可能还保存着已删除的 device_doctor/storage_cleaner/freeze_apps，
 * 自定义模式 / 内置模式覆盖的 capabilities 里可能还保存着已删除的 DEVICE_TOOLS——
 * 这些取值在枚举/密封类条目删除后会让整条数据解码失败（助手被逐条隔离丢弃、
 * 自定义模式整表回退为空），所以在读取前做一次一次性 JSON 级过滤。
 *
 * 只过滤已移除取值，其余内容原样保留；未命中时不做任何写入。
 */
class PreferenceStoreV9Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 9
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        // 助手（含 lastKnownGood 快照）：过滤已移除的设备本地工具开关
        prefs[SettingsStore.ASSISTANTS]?.let { raw ->
            val cleaned = RemovedDeviceFeatureCleanup.cleanAssistantsJson(raw)
            if (cleaned != raw) prefs[SettingsStore.ASSISTANTS] = cleaned
        }
        prefs[SettingsStore.ASSISTANTS_LKG]?.let { raw ->
            val cleaned = RemovedDeviceFeatureCleanup.cleanAssistantsJson(raw)
            if (cleaned != raw) prefs[SettingsStore.ASSISTANTS_LKG] = cleaned
        }

        // 自定义模式 / 内置模式覆盖：过滤已移除的 DEVICE_TOOLS 能力
        prefs[SettingsStore.CUSTOM_MODES]?.let { raw ->
            val cleaned = RemovedDeviceFeatureCleanup.cleanCustomModesJson(raw)
            if (cleaned != raw) prefs[SettingsStore.CUSTOM_MODES] = cleaned
        }
        prefs[SettingsStore.BUILTIN_MODE_OVERRIDES]?.let { raw ->
            val cleaned = RemovedDeviceFeatureCleanup.cleanBuiltinModeOverridesJson(raw)
            if (cleaned != raw) prefs[SettingsStore.BUILTIN_MODE_OVERRIDES] = cleaned
        }

        prefs[SettingsStore.VERSION] = 9
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}
