package me.rerere.rikkahub.data.device

/**
 * 设备工具审批级别：
 * - ALLOW：自动允许，不弹确认
 * - ASK：每次询问（默认）
 * - FORBID：禁止使用
 */
enum class PermissionLevel {
    ALLOW,
    ASK,
    FORBID;

    companion object {
        fun fromString(value: String?): PermissionLevel =
            when (value) {
                "ALLOW" -> ALLOW
                "FORBID" -> FORBID
                else -> ASK
            }
    }
}