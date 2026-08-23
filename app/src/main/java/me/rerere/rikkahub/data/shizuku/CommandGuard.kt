package me.rerere.rikkahub.data.shizuku

/**
 * 命令安全守卫：设备能力层只允许执行白名单内的命令。
 *
 * 执行桥用 UserService 的 ProcessBuilder 直接 exec 参数数组，不经过 shell，
 * 因此不存在 `sh -c` 拼接注入；这里做命令级白名单，作为第二道防线。
 *
 * [allowWrite] 为 true 时放行写类子命令（冻结、清理等），调用方必须经过
 * SafetyGuard 校验与用户审批；rm 等高风险命令强制要求 allowWrite。
 */
object CommandGuard {
    private val ALLOWED_BINARIES = setOf(
        "pm", "settings", "dumpsys", "df", "du", "getprop", "find", "logcat",
        "am", "screencap", "ps", "top", "echo", "test", "ls", "cat", "rm",
    )

    private val FORBIDDEN_BINARIES = setOf(
        "sh", "bash", "toybox", "su", "chmod", "chown", "mount", "reboot", "dd",
    )

    /** argv[1] 命中即视为写/危险操作，默认拦截，allowWrite 时放行 */
    private val WRITE_SUBCOMMANDS = mapOf(
        "pm" to setOf(
            "clear", "uninstall", "install", "grant", "revoke",
            "enable", "disable", "disable-user", "set-app-enabled", "trim-caches",
        ),
        "settings" to setOf("put", "delete", "reset"),
        "am" to setOf("force-stop", "kill", "kill-all", "broadcast", "start", "start-activity"),
    )

    /** find 中会导致写操作或不可控行为的参数，任何情况下拦截 */
    private val FIND_DANGEROUS_ARGS = setOf(
        "-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprintf", "-fprint", "-fls",
    )

    /**
     * 校验命令是否允许执行。
     * @param allowWrite 是否允许写类子命令（调用方须已通过 SafetyGuard 与用户审批）
     * @return 违规原因；null 表示通过。
     */
    fun check(command: List<String>, allowWrite: Boolean = false): String? {
        val raw = command.firstOrNull() ?: return "空命令"
        val binary = raw.substringAfterLast('/')
        if (binary in FORBIDDEN_BINARIES) return "禁止的命令: $binary"
        if (binary !in ALLOWED_BINARIES) return "不允许的命令: $binary"
        if (binary == "rm" && !allowWrite) {
            return "rm 需要审批后执行"
        }
        if (binary == "find") {
            val danger = command.drop(1).firstOrNull { it in FIND_DANGEROUS_ARGS }
            if (danger != null) return "find 参数包含危险选项: $danger"
        }
        val sub = command.getOrNull(1)
        if (!allowWrite && sub != null && WRITE_SUBCOMMANDS[binary]?.contains(sub) == true) {
            return "写操作子命令需要审批: $binary $sub"
        }
        return null
    }
}
