package me.rerere.rikkahub.data.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 授权管理层。
 *
 * 让应用以 shell(ADB) 权限调用系统 API。本类只负责授权状态管理：
 * 安装检查、服务连通性、权限授予、状态监听。命令执行由后续的
 * ShizukuCommandExecutor 负责。
 */
object ShizukuService {
    private const val TAG = "ShizukuService"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST_CODE = 10086

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var serviceAvailable = false

    @Volatile
    private var permissionGranted = false

    @Volatile
    private var lastServiceError = ""

    @Volatile
    private var lastPermissionError = ""

    private val stateChangeListeners = mutableListOf<() -> Unit>()

    private var binderListener: Shizuku.OnBinderReceivedListener? = null
    private var binderDeadListener: Shizuku.OnBinderDeadListener? = null
    private var permissionListenerRegistered = false
    private var initialized = false

    /** 初始化并注册 Shizuku 状态监听，建议在 Application.onCreate 调用一次 */
    fun initialize() {
        if (initialized) return
        initialized = true
        registerBinderListeners()
        registerPermissionListener()
        refreshState()
    }

    fun addStateChangeListener(listener: () -> Unit) {
        synchronized(stateChangeListeners) {
            if (!stateChangeListeners.contains(listener)) {
                stateChangeListeners.add(listener)
            }
        }
    }

    fun removeStateChangeListener(listener: () -> Unit) {
        synchronized(stateChangeListeners) {
            stateChangeListeners.remove(listener)
        }
    }

    private fun notifyStateChanged() {
        mainHandler.post {
            synchronized(stateChangeListeners) {
                stateChangeListeners.forEach { it.invoke() }
            }
        }
    }

    private fun registerBinderListeners() {
        if (binderListener == null) {
            binderListener = Shizuku.OnBinderReceivedListener { refreshState() }
            Shizuku.addBinderReceivedListener(binderListener!!)
        }
        if (binderDeadListener == null) {
            binderDeadListener = Shizuku.OnBinderDeadListener { refreshState() }
            Shizuku.addBinderDeadListener(binderDeadListener!!)
        }
    }

    private fun registerPermissionListener() {
        if (permissionListenerRegistered) return
        permissionListenerRegistered = true
        Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PERMISSION_REQUEST_CODE) {
                permissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
                if (!permissionGranted) {
                    lastPermissionError = "用户拒绝了 Shizuku 授权"
                }
                Log.i(TAG, "permission result: granted=$permissionGranted")
                refreshState()
            }
        }
    }

    /** 刷新服务与权限状态，并通知监听者 */
    fun refreshState() {
        val serviceOk = checkService()
        val permOk = serviceOk && checkPermission()
        serviceAvailable = serviceOk
        permissionGranted = permOk
        notifyStateChanged()
    }

    private fun checkService(): Boolean {
        return try {
            val binder: IBinder? = Shizuku.getBinder()
            if (binder == null || !binder.isBinderAlive) {
                lastServiceError = "Shizuku 服务未运行"
                return false
            }
            val uid = Shizuku.getUid()
            if (uid != 0 && uid != 2000) {
                lastServiceError = "Shizuku UID 异常: $uid"
                return false
            }
            lastServiceError = ""
            true
        } catch (e: Throwable) {
            lastServiceError = "Shizuku 服务检查失败: ${e.message}"
            Log.w(TAG, lastServiceError, e)
            false
        }
    }

    private fun checkPermission(): Boolean {
        return try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                lastPermissionError = ""
                true
            } else {
                lastPermissionError = "Shizuku 权限未授予"
                false
            }
        } catch (e: Throwable) {
            lastPermissionError = "Shizuku 权限检查失败: ${e.message}"
            Log.w(TAG, lastPermissionError, e)
            false
        }
    }

    fun isServiceRunning(): Boolean {
        if (!initialized) refreshState()
        return serviceAvailable
    }

    fun hasPermission(): Boolean {
        if (!initialized) refreshState()
        return permissionGranted
    }

    /** 设备能力是否就绪：服务运行且权限已授予 */
    fun isReady(): Boolean = isServiceRunning() && hasPermission()

    fun getServiceError(): String = lastServiceError

    fun getPermissionError(): String = lastPermissionError

    /** 判断 Shizuku 是否已安装（兼容 Sui 后端） */
    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            // 未安装 Shizuku app 时，兼容已激活的 Sui 后端
            try {
                Shizuku.pingBinder() || (Shizuku.getBinder()?.isBinderAlive == true)
            } catch (_: Throwable) {
                false
            }
        }
    }

    /** 发起权限请求，结果通过状态监听回调 */
    fun requestPermission() {
        if (!isServiceRunning()) return
        if (hasPermission()) return
        try {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Throwable) {
            lastPermissionError = "请求权限失败: ${e.message}"
            Log.w(TAG, lastPermissionError, e)
        }
    }
}