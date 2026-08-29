package me.rerere.rikkahub.data.trustedfolders

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.trustedFolderDataStore by preferencesDataStore(
    name = "trusted_folders",
)

/**
 * 信任文件夹整体设置：项目列表（文件夹库）。
 * 激活语义已收敛到助手级绑定（Assistant.trustedFolderProjectId）：绑定即激活，解绑即停用。
 * 审批/配置目录等设置下放到每个 [TrustedFolderProject]。
 */
data class TrustedFolderSettings(
    val projects: List<TrustedFolderProject> = emptyList(),
)

/**
 * 信任文件夹设置持久化。独立 DataStore，不侵入主 Settings 聚合类。
 * 项目列表存为 JSON string（kotlinx.serialization）。
 */
class TrustedFolderStore(private val context: Context) {
    companion object {
        private val PROJECTS = stringPreferencesKey("projects_json")
        private val RECENT_FILES = stringPreferencesKey("recent_files_json")

        /** 最近访问记录上限：超出裁掉最旧的，防止跨项目长期累积无限增长 */
        private const val MAX_RECENT_FILES = 200

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    val settingsFlow: Flow<TrustedFolderSettings> = context.trustedFolderDataStore.data
        .map { preferences ->
            TrustedFolderSettings(
                projects = preferences[PROJECTS]?.let {
                    runCatching { json.decodeFromString<List<TrustedFolderProject>>(it) }.getOrNull()
                }.orEmpty(),
            )
        }

    suspend fun current(): TrustedFolderSettings = settingsFlow.first()

    /** 最近访问的文件记录流（跨项目全量，调用方按项目过滤） */
    val recentFilesFlow: Flow<List<RecentFile>> = context.trustedFolderDataStore.data
        .map { preferences ->
            preferences[RECENT_FILES]?.let {
                runCatching { json.decodeFromString<List<RecentFile>>(it) }.getOrNull()
            }.orEmpty()
        }

    /** 记录一条最近访问：同项目同路径去重后置顶（最新在前） */
    suspend fun recordRecentFile(entry: RecentFile) {
        context.trustedFolderDataStore.edit { preferences ->
            val existing = preferences[RECENT_FILES]?.let {
                runCatching { json.decodeFromString<List<RecentFile>>(it) }.getOrNull()
            }.orEmpty()
            val updated = (listOf(entry) + existing.filterNot { it.projectId == entry.projectId && it.path == entry.path })
                .take(MAX_RECENT_FILES)
            preferences[RECENT_FILES] = json.encodeToString(updated)
        }
    }

    /** 从最近访问移除一条（用户手动叉掉） */
    suspend fun removeRecentFile(projectId: String, path: String) {
        context.trustedFolderDataStore.edit { preferences ->
            val existing = preferences[RECENT_FILES]?.let {
                runCatching { json.decodeFromString<List<RecentFile>>(it) }.getOrNull()
            }.orEmpty()
            val updated = existing.filterNot { it.projectId == projectId && it.path == path }
            preferences[RECENT_FILES] = json.encodeToString(updated)
        }
    }

    suspend fun update(settings: TrustedFolderSettings) {
        context.trustedFolderDataStore.edit { preferences ->
            preferences[PROJECTS] = json.encodeToString(settings.projects)
        }
    }
}
