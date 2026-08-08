package me.rerere.rikkahub.data.sync

import kotlinx.serialization.Serializable

/**
 * 远端增量同步配置（设备本地项，不参与 settings 白名单同步）。
 */
@Serializable
data class SyncConfig(
    /** 总开关 */
    val enabled: Boolean = false,
    /** 后端类型：webdav / s3 */
    val provider: SyncProviderKind = SyncProviderKind.WEBDAV,
    /** 最小同步间隔（小时），0 表示不限制 */
    val intervalHours: Int = 24,
    /** 启动时自动同步 */
    val autoSyncOnLaunch: Boolean = true,
    /** 是否同步聊天数据库 */
    val includeDatabase: Boolean = true,
    /** 是否同步托管附件（upload/skills/fonts） */
    val includeFiles: Boolean = true,
)

@Serializable
enum class SyncProviderKind {
    WEBDAV,
    S3,
}
