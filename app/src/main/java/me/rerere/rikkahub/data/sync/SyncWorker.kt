package me.rerere.rikkahub.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * WorkManager 每日兜底同步任务。
 * 与启动同步互斥靠 SyncStateStore.syncInProgress + lastSyncTime 天然去重。
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    override suspend fun doWork(): Result {
        return try {
            get<CloudSyncCoordinator>().syncIfNeeded(force = false)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
