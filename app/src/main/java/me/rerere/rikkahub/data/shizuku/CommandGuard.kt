package me.rerere.rikkahub.data.shizuku

/**
 * 命令安全守卫：设备能力层只允许执行白名单内的命令。
 *
 * 执行桥用 [rikka.shizuku.Shizuku.newProcess] 直接 exec 参数数组，不经过 shell，
 * 因此不存在 `sh -c` 拼接注入；这里做命令级白名单，作为第二道防线。
 * 写类子命令（冻结、清理、改设置等）在 Phase 2 接入审批机制前一律拦截。
 */
object CommandGuard {
    private val ALLOWED_BINARIES = setOf(
        "pm", "settings", "dumpsys", "df", "du", "getprop", "find", "logcat",
        "am", "screencap", "ps", "top", "echo", "test", "ls", "cat",
    )

    private val FORBIDDEN_BINARIES = setOf(
        "sh", "bash", "toybox", "su", "chmod", "chown", "mount", "reboot", "dd",
    )

    /** argv[1] 命中即拦截的写/危险操作。Phase 2 接入审批后按工具放行。 */
    private val WRITE_SUBCOMMANDS = mapOf(
        "pm" to setOf(
            "clear", "uninstall", "install", "grant", "revoke",
            "enable", "disable", "disable-user", "set-app-enabled", "trim-caches",
        ),
        "settings" to setOf("put", "delete", "reset"),
        "am" to setOf("force-stop", "kill", "kill-all", "broadcast", "start", "start-activity"),
    )

    /**
     * 校验命令是否允许执行。
     * @return 违规原因；null 表示通过。
     */
    fun check(command: List<String>): String? {
        val raw = command.firstOrNull() ?: return "空命令"
        val binary = raw.substringAfterLast('/')
        if (binary in FORBIDDEN_BINARIES) return "禁止的命令: $binary"
        if (binary !in ALLOWED_BINARIES) return "不允许的命令: $binary"
        val sub = command.getOrNull(1)
        if (sub != null && WRITE_SUBCOMMANDS[binary]?.contains(sub) == true) {
            return "写操作子命令暂未开放: $binary $sub"
        }
        return null
    }
}