package me.rerere.rikkahub.data.shizuku

import android.util.Log

data class ShizukuCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val blocked: Boolean = false,
)

/**
 * Shizuku 命令执行桥。
 *
 * 所有命令先经过 [CommandGuard] 白名单校验，再通过 [ShizukuService] 连接的
 * UserService（shell/ADB 身份）执行。未授权或未连接时返回可读错误，不执行。
 */
object ShizukuCommandExecutor {
    private const val TAG = "ShizukuCommandExecutor"
    private const val DEFAULT_TIMEOUT_MS = 30_000L

    fun execute(
        command: List<String>,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    ): ShizukuCommandResult {
        if (command.isEmpty()) {
            return ShizukuCommandResult(-1, "", "空命令", blocked = true)
        }
        val violation = CommandGuard.check(command)
        if (violation != null) {
            Log.w(TAG, "command blocked: $violation -> ${command.joinToString(" ")}")
            return ShizukuCommandResult(-1, "", "命令被安全策略拦截: $violation", blocked = true)
        }
        if (!ShizukuService.isReady()) {
            val reason = ShizukuService.getPermissionError().ifBlank { ShizukuService.getServiceError() }
            return ShizukuCommandResult(-1, "", "Shizuku 未就绪: $reason")
        }
        if (!ShizukuService.ensureUserService()) {
            return ShizukuCommandResult(-1, "", "Shizuku UserService 连接失败")
        }
        return ShizukuService.executeViaUserService(command, timeoutMillis)
            ?: ShizukuCommandResult(-1, "", "Shizuku UserService 未连接")
    }
}