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
    /** 是否同步设置白名单（不含密钥/凭据） */
    val includeSettings: Boolean = true,
    /** 是否同步聊天数据库 */
    val includeDatabase: Boolean = true,
    /** 是否同步聊天附件（upload 目录） */
    val includeChatFiles: Boolean = true,
    /**
     * 是否启用聊天增量同步（会话级文件，替代整库聊天数据上传）。
     * 开启后聊天记录按会话拆分同步，发一条消息只传该会话的小文件。
     */
    val includeConversations: Boolean = true,
    /** 是否同步技能文件（skills 目录） */
    val includeSkills: Boolean = true,
    /** 是否同步字体（fonts 目录） */
    val includeFonts: Boolean = true,
)

@Serializable
enum class SyncProviderKind {
    WEBDAV,
    S3,
}
