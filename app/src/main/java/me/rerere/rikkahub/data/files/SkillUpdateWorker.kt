package me.rerere.rikkahub.data.files

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * WorkManager 每日兜底技能更新检查。
 * 与冷启动检查（RikkaHubApp.startSkillUpdateCheck）共用 SkillUpdateManager 的节流去重，
 * 并发由其内部 mutex 串行化。命中自动更新且本地未修改的技能在后台静默应用。
 */
class SkillUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    override suspend fun doWork(): Result {
        return try {
            get<SkillUpdateManager>().checkAll(force = false)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
