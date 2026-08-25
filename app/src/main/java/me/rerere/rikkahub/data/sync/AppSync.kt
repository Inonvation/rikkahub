package me.rerere.rikkahub.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.sync.s3.S3Config
import java.io.File

private const val TAG = "CloudSync"

/** 数据库操作抽象（App 侧用 AppDatabase + context，测试侧用临时文件）。 */
interface DbSyncAccess {
    /** WAL checkpoint，保证 -wal 合并进主文件。 */
    fun checkpoint()
    /** 数据库主文件。 */
    fun dbFile(): File
    /**
     * 本地数据库是否已有真实数据（默认 false：测试 fake / 未知场景视为空库）。
     * 首次同步（无 syncedFiles 记录）时据此决定 DB 是 pull 还是 push：
     * 本地已有数据 → push（以本机为准），本地为空 → pull（吸收远端），
     * 避免自动同步用远端旧库覆盖本机现有聊天记录。
     */
    suspend fun hasLocalData(): Boolean = false
}

/** App 侧 DbSyncAccess：包装 AppDatabase + context 获取 DB 文件。 */
class AppDbSyncAccess(
    private val context: Context,
    private val database: AppDatabase,
) : DbSyncAccess {
    override fun checkpoint() = database.checkpointWal()
    override fun dbFile(): File = context.getDatabasePath("rikka_hub")
    override suspend fun hasLocalData(): Boolean = try {
        database.conversationDao().countAll() > 0
    } catch (e: Exception) {
        false
    }
}

/** App 侧 SyncSettingsAccess：包装 SettingsStore。 */
class SettingsStoreSyncAccess(
    private val settingsStore: SettingsStore,
) : SyncSettingsAccess {
    override suspend fun currentSettings(): Settings = settingsStore.settingsFlow.first()
    override suspend fun saveSettings(settings: Settings) {
        if (!settings.init) settingsStore.update(settings)
    }
}

/** 按配置构建 SyncProvider。凭据不完整时返回 null。 */
fun buildSyncProvider(config: SyncConfig, webDav: WebDavConfig, s3: S3Config, httpClient: HttpClient): SyncProvider? {
    return when (config.provider) {
        SyncProviderKind.WEBDAV -> {
            if (webDav.url.isBlank() || webDav.username.isBlank() || webDav.password.isBlank()) {
                null
            } else {
                WebDavSyncProvider(webDav, httpClient)
            }
        }

        SyncProviderKind.S3 -> {
            if (s3.endpoint.isBlank() || s3.accessKeyId.isBlank() || s3.secretAccessKey.isBlank() || s3.bucket.isBlank()) {
                null
            } else {
                S3SyncProvider(s3, httpClient)
            }
        }
    }
}

/**
 * 云同步调度器：统一入口，供应用启动 / Worker / 手动按钮调用。
 * 协调互斥锁、最小间隔、pendingSync 语义与 provider 构建。
 */
class CloudSyncCoordinator(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val syncManager: SyncManager,
    private val stateStore: SyncStateStore,
    private val httpClient: HttpClient,
) {
    /**
     * 启动时/定时触发：按 [SyncConfig] 与最小间隔决定是否执行。
     *
     * @param force 手动触发时传 true，忽略最小间隔
     * @param onProgress 同步执行进度回调（relPath + 状态），供进度弹窗实时刷新
     * @return 本次是否实际执行了同步（false 表示未触发：未启用/未到间隔/离线/已在同步）
     */
    suspend fun syncIfNeeded(
        force: Boolean = false,
        onProgress: (relPath: String, status: SyncItemStatus) -> Unit = { _, _ -> },
    ): Boolean {
        val settings = settingsStore.settingsFlow.first()
        val config = settings.syncConfig
        if (!config.enabled) return false

        val now = System.currentTimeMillis()
        val state = stateStore.current()

        // 手动 force / 上次离线未完成(pendingSync) / 距上次同步超间隔 时执行
        val due = force || state.pendingSync ||
            state.lastSyncTime == 0L ||
            (config.intervalHours > 0 && now - state.lastSyncTime >= config.intervalHours * 3600_000L)
        if (!due) return false

        if (!isOnline()) {
            Log.w(TAG, "syncIfNeeded: offline, mark pendingSync")
            stateStore.update { it.copy(pendingSync = true) }
            return false
        }

        // 互斥：已有同步在跑则跳过
        if (!stateStore.tryAcquireSyncLock()) {
            Log.i(TAG, "syncIfNeeded: already in progress")
            return false
        }
        try {
            val result = runSync(config, settings, onProgress)
            if (!result.success) {
                Log.w(TAG, "syncIfNeeded: sync failed: ${result.error}")
            }
            return result.success
        } finally {
            stateStore.releaseSyncLock()
        }
    }

    /**
     * 只读检测本地与云端的差异，供手动同步前的确认弹窗使用。
     * 不传输任何数据、不修改同步状态。
     *
     * @return 分组差异清单；null 表示无法检测（未启用 / 离线 / 凭据不完整 / 检测失败）。
     */
    suspend fun preview(): SyncPreview? {
        val settings = settingsStore.settingsFlow.first()
        val config = settings.syncConfig
        if (!config.enabled) return null
        if (!isOnline()) return null
        val provider = buildSyncProvider(config, settings.webDavConfig, settings.s3Config, httpClient)
            ?: return null
        return syncManager.preview(provider, config)
    }

    private suspend fun runSync(
        config: SyncConfig,
        settings: Settings,
        onProgress: (relPath: String, status: SyncItemStatus) -> Unit = { _, _ -> },
    ): SyncResult {
        val provider = buildSyncProvider(config, settings.webDavConfig, settings.s3Config, httpClient)
        if (provider == null) {
            Log.w(TAG, "runSync: provider credentials incomplete")
            stateStore.update { it.copy(pendingSync = true) }
            return SyncResult(success = false, error = "provider credentials incomplete")
        }
        val result = syncManager.sync(provider, config, onProgress)
        Log.i(TAG, "runSync: success=${result.success} pushed=${result.pushed} pulled=${result.pulled} conflicts=${result.conflicts} deleted=${result.deleted} err=${result.error}")
        return result
    }

    /** 简单在线检测：active network 具备 INTERNET 能力即视为在线。 */
    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return runCatching {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(false)
    }
}
