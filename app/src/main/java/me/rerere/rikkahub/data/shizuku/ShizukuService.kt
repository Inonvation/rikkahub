package me.rerere.rikkahub.data.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import rikka.shizuku.IShizukuCommandService
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs

/**
 * Shizuku 授权与 UserService 连接管理层。
 *
 * 授权部分：安装检查、服务连通性、权限授予、状态监听。
 * 执行部分：通过 [UserService]（以 shell/ADB 身份运行）执行白名单命令。
 */
object ShizukuService {
    private const val TAG = "ShizukuService"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST_CODE = 10086
    private const val BIND_TIMEOUT_MS = 5_000L

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

    // UserService 连接
    private var userServiceArgs: UserServiceArgs? = null
    private val serviceLock = Object()

    @Volatile
    private var commandService: IShizukuCommandService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            synchronized(serviceLock) {
                commandService = IShizukuCommandService.Stub.asInterface(binder)
                serviceLock.notifyAll()
            }
            Log.i(TAG, "UserService connected")
            refreshState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(serviceLock) {
                commandService = null
                serviceLock.notifyAll()
            }
            Log.w(TAG, "UserService disconnected")
            refreshState()
        }
    }

    /** 初始化并注册 Shizuku 状态监听，建议在 Application.onCreate 调用一次 */
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        userServiceArgs = UserServiceArgs(ComponentName(context, UserService::class.java))
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

    /**
     * 确保 UserService 已连接（阻塞等待，最多 [BIND_TIMEOUT_MS]）。
     * 调用方应处于 IO 线程。
     */
    fun ensureUserService(timeoutMillis: Long = BIND_TIMEOUT_MS): Boolean {
        if (commandService != null) return true
        if (!isReady()) return false
        synchronized(serviceLock) {
            if (commandService != null) return true
            val args = userServiceArgs ?: return false
            try {
                Shizuku.bindUserService(args, serviceConnection)
            } catch (e: Throwable) {
                Log.w(TAG, "bindUserService failed", e)
                return false
            }
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (commandService == null && System.currentTimeMillis() < deadline) {
                try {
                    (serviceLock as java.lang.Object).wait(200)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return commandService != null
        }
    }

    /** 通过 UserService 执行命令；未连接时返回 null */
    fun executeViaUserService(cmd: List<String>, timeoutMillis: Long): ShizukuCommandResult? {
        val service = commandService ?: return null
        return try {
            val exitCode = service.execute(cmd.toTypedArray(), timeoutMillis)
            ShizukuCommandResult(
                exitCode = exitCode,
                stdout = service.getStdout(),
                stderr = service.getStderr(),
                timedOut = exitCode == -1,
            )
        } catch (e: Throwable) {
            Log.e(TAG, "executeViaUserService failed", e)
            ShizukuCommandResult(-1, "", "命令执行失败: ${e.message}")
        }
    }
}