package me.rerere.rikkahub.data.sync.s3

import kotlinx.serialization.Serializable

@Serializable
data class S3Config(
    val endpoint: String = "",
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val bucket: String = "",
    val region: String = "auto",
    val pathStyle: Boolean = true,
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.CHAT_FILES
    ),
) {
    val host: String
        get() = endpoint
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')

    val isHttps: Boolean
        get() = endpoint.startsWith("https://")

    fun bucketUrl(): String {
        return if (pathStyle) {
            "${endpoint.trimEnd('/')}/$bucket"
        } else {
            val scheme = if (isHttps) "https://" else "http://"
            "$scheme$bucket.$host"
        }
    }

    @Serializable
    enum class BackupItem {
        DATABASE,
        /** 兼容旧配置：旧版 FILES 含聊天附件+字体；新版不再使用，仅防旧 JSON 反序列化失败 */
        FILES,
        /** 聊天附件（upload 目录） */
        CHAT_FILES,
        /** 技能文件（可能很大，默认不随整包备份，避免超出远端单文件上限） */
        SKILLS,
        /** 字体文件 */
        FONTS,
    }
}
