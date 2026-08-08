package me.rerere.rikkahub.data.sync

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

private const val TAG = "SyncStateStore"

private val Context.syncStateDataStore by preferencesDataStore(name = "sync_state")

@Serializable
data class FileSyncRecord(
    val sha256: String = "",
    val remoteTag: String = "",
    val updatedAtMs: Long = 0L,
    val deviceId: String = "",
)

@Serializable
data class ConflictRecord(
    val relPath: String = "",
    val atMs: Long = 0L,
    val winnerDevice: String = "",
)

@Serializable
data class SyncState(
    val deviceId: String = "",
    val syncedFiles: Map<String, FileSyncRecord> = emptyMap(),
    val lastSyncTime: Long = 0L,
    val syncInProgress: Boolean = false,
    val pendingSync: Boolean = false,
    val dbRestorePending: String? = null,
    val conflicts: List<ConflictRecord> = emptyList(),
)

/**
 * 远端增量同步的本地状态（DataStore 持久化，独立于 settings）。
 *
 * - `syncedFiles`：远端相对路径 -> 上次确认同步时的记录，作为本地 sha 与远端 etag 的中间桥梁。
 * - `syncInProgress`：互斥锁，所有触发源共用，防止并发重入（通过 [tryAcquireSyncLock] CAS 获取）。
 * - `pendingSync`：上次离线/冷却未完成的标记，下次触发时补齐。
 * - `dbRestorePending`：待重启时应用的 DB 下载路径（cacheDir/tmp），见 SyncManager。
 */
class SyncStateStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.syncStateDataStore)

    private companion object {
        val STATE_KEY = stringPreferencesKey("state")
    }

    val stateFlow: Flow<SyncState> = dataStore.data
        .catch { e ->
            if (e is IOException) {
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { prefs -> decode(prefs[STATE_KEY]) }

    suspend fun current(): SyncState = stateFlow.first()

    /** 原子读-改-写。返回更新后的 state。 */
    suspend fun update(transform: (SyncState) -> SyncState): SyncState {
        var result: SyncState? = null
        dataStore.edit { prefs ->
            val next = transform(decode(prefs[STATE_KEY]))
            prefs[STATE_KEY] = JsonInstant.encodeToString(next)
            result = next
        }
        return result ?: SyncState()
    }

    /** 互斥锁 CAS：锁空闲则加锁返回 true；已被占用返回 false。 */
    suspend fun tryAcquireSyncLock(): Boolean {
        var acquired = false
        dataStore.edit { prefs ->
            val current = decode(prefs[STATE_KEY])
            if (!current.syncInProgress) {
                prefs[STATE_KEY] = JsonInstant.encodeToString(current.copy(syncInProgress = true))
                acquired = true
            }
        }
        return acquired
    }

    suspend fun releaseSyncLock() {
        update { it.copy(syncInProgress = false) }
    }

    /** 首次同步时生成 deviceId，后续稳定复用。 */
    suspend fun getOrCreateDeviceId(): String {
        var id: String? = null
        dataStore.edit { prefs ->
            val current = decode(prefs[STATE_KEY])
            if (current.deviceId.isNotBlank()) {
                id = current.deviceId
            } else {
                val newId = Uuid.random().toString()
                prefs[STATE_KEY] = JsonInstant.encodeToString(current.copy(deviceId = newId))
                id = newId
            }
        }
        return id ?: ""
    }

    private fun decode(json: String?): SyncState {
        if (json.isNullOrBlank()) return SyncState()
        return runCatching { JsonInstant.decodeFromString<SyncState>(json) }
            .getOrElse {
                Log.w(TAG, "Failed to decode sync state, using default", it)
                SyncState()
            }
    }
}
