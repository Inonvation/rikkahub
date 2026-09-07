package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity

private const val TAG = "SolveGenerationFgs"

/**
 * 拍照搜题生成保活前台服务。
 *
 * 与 ChatGenerationForegroundService 同构但独立：聊天版的通知点击回跳到具体会话，
 * 解题没有会话概念，通知点击仅拉起主界面。生成仍由 SolveVM 的协程持有，
 * 本服务只提供 Android 前台服务生命周期，避免页面退后台后流式被系统冻结。
 */
class SolveGenerationForegroundService : Service() {
    companion object {
        private const val ACTION_ACQUIRE = "me.rerere.rikkahub.action.SOLVE_GENERATION_ACQUIRE"
        private const val ACTION_RELEASE = "me.rerere.rikkahub.action.SOLVE_GENERATION_RELEASE"
        private const val EXTRA_SOLVE_ID = "solve_id"

        const val NOTIFICATION_ID = 2003

        fun acquire(context: Context, solveId: String): Boolean {
            val intent = Intent(context, SolveGenerationForegroundService::class.java).apply {
                action = ACTION_ACQUIRE
                putExtra(EXTRA_SOLVE_ID, solveId)
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.onFailure {
                Log.e(TAG, "Unable to start solve generation foreground service", it)
            }.getOrDefault(false)
        }

        fun release(context: Context, solveId: String) {
            val intent = Intent(context, SolveGenerationForegroundService::class.java).apply {
                action = ACTION_RELEASE
                putExtra(EXTRA_SOLVE_ID, solveId)
            }
            runCatching {
                context.startService(intent)
            }.onFailure {
                Log.e(TAG, "Unable to release solve generation foreground service", it)
            }
        }
    }

    private val activeSolves = java.util.LinkedHashSet<String>()
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACQUIRE -> acquire(intent)
            ACTION_RELEASE -> release(intent)
            else -> stopService()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeSolves.clear()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        super.onDestroy()
    }

    private fun acquire(intent: Intent) {
        val solveId = intent.getStringExtra(EXTRA_SOLVE_ID) ?: return stopService()
        activeSolves.add(solveId)
        updateForegroundNotification()
    }

    private fun release(intent: Intent) {
        intent.getStringExtra(EXTRA_SOLVE_ID)?.let { activeSolves.remove(it) }
        if (activeSolves.isEmpty()) {
            stopService()
        }
    }

    private fun updateForegroundNotification() {
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter foreground", e)
            activeSolves.clear()
            stopSelf()
        }
    }

    private fun stopService() {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_rikkahub)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_solve_generation_title))
            .setContentIntent(getLaunchPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun getLaunchPendingIntent(): PendingIntent {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
