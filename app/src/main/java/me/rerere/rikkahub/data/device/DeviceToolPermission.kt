package me.rerere.rikkahub.data.device

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

import kotlinx.coroutines.launch

private val Context.toolPermissionDataStore by preferencesDataStore("device_tool_permissions")

/** 参与三档审批的设备写工具 */
val DEVICE_TOOL_NAMES = listOf(
    "freeze_app",
    "unfreeze_app",
    "freeze_batch",
    "clean_cache",
    "clean_files",
)

val DEVICE_TOOL_LABELS = mapOf(
    "freeze_app" to "冻结应用",
    "unfreeze_app" to "解冻应用",
    "freeze_batch" to "批量冻结",
    "clean_cache" to "清理缓存",
    "clean_files" to "删除文件",
)

/**
 * 设备工具三档审批（ALLOW / ASK / FORBID）。
 *
 * 内存缓存由 DataStore 收集，供 Tool.needsApproval 同步读取；
 * 设置页通过 Flow 展示与修改。
 */
class DeviceToolPermission(
    private val context: Context,
    scope: CoroutineScope,
) {
    private val masterKey = stringPreferencesKey("master")
    private fun toolKey(name: String) = stringPreferencesKey("tool_$name")

    val masterFlow: Flow<PermissionLevel> =
        context.toolPermissionDataStore.data.map { prefs ->
            PermissionLevel.fromString(prefs[masterKey])
        }

    fun toolFlow(name: String): Flow<PermissionLevel> =
        context.toolPermissionDataStore.data.map { prefs ->
            PermissionLevel.fromString(prefs[toolKey(name)])
        }

    /** 内存缓存：工具名 -> 级别（未单独设置时为 null） */
    private val cache = MutableStateFlow<Map<String, PermissionLevel>>(emptyMap())
    internal val allLevels: StateFlow<Map<String, PermissionLevel>> = cache

    @Volatile
    private var masterLevel: PermissionLevel = PermissionLevel.ASK

    init {
        scope.launch {
            masterFlow.collect { masterLevel = it }
        }
        DEVICE_TOOL_NAMES.forEach { name ->
            scope.launch {
                toolFlow(name).collect { level ->
                    cache.update { it + (name to level) }
                }
            }
        }
    }

    /** 同步读取某工具的有效级别 */
    fun policyFor(name: String): PermissionLevel =
        cache.value[name] ?: masterLevel

    /** 是否需要在执行前询问用户 */
    fun needsApproval(name: String): Boolean = policyFor(name) == PermissionLevel.ASK

    /** 是否被禁止 */
    fun isForbidden(name: String): Boolean = policyFor(name) == PermissionLevel.FORBID

    suspend fun setMaster(level: PermissionLevel) {
        context.toolPermissionDataStore.edit { prefs ->
            prefs[masterKey] = level.name
        }
    }

    suspend fun setTool(name: String, level: PermissionLevel) {
        context.toolPermissionDataStore.edit { prefs ->
            prefs[toolKey(name)] = level.name
        }
    }

    suspend fun masterSnapshot(): PermissionLevel = masterFlow.first()

    suspend fun toolSnapshot(name: String): PermissionLevel = toolFlow(name).first()
}