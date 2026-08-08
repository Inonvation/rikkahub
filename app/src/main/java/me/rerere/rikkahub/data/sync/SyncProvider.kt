package me.rerere.rikkahub.data.sync

import java.io.File

/** 远端单个文件（relPath 为相对同步根目录的路径，统一用 / 分隔）。 */
data class RemoteFile(
    val relPath: String,
    val etag: String?,
    val lastModifiedMs: Long?,
    val size: Long,
)

/** 上传结果：远端最新 etag 与修改时间。 */
data class UploadResult(
    val etag: String?,
    val lastModifiedMs: Long?,
)

/**
 * 远端增量同步的存储抽象，屏蔽 WebDAV / S3 差异。
 *
 * relPath 命名空间（相对同步根）：
 * - settings.json          设置白名单
 * - db/rikka_hub.db         checkpoint 后的单文件数据库
 * - upload/<name>           上传附件
 * - skills/<relpath>        skills 文件（含子目录）
 * - fonts/<name>            字体文件
 */
interface SyncProvider {
    /** 连通性/配置校验。 */
    suspend fun test(): Result<Unit>

    /** 确保同步根集合存在（WebDAV 需要 mkcol；S3 无目录概念，直接成功）。 */
    suspend fun ensureBaseCollection(): Result<Unit>

    /** 列出同步根下全部文件（递归，不含目录）。 */
    suspend fun listRemote(): Result<List<RemoteFile>>

    /** 上传文件内容，返回远端 etag 与修改时间（可能为 null）。 */
    suspend fun upload(relPath: String, content: ByteArray): Result<UploadResult>

    /** 下载到本地文件。 */
    suspend fun downloadToFile(relPath: String, targetFile: File): Result<Unit>

    /** 查询单个文件元信息；不存在时返回 failure。 */
    suspend fun head(relPath: String): Result<RemoteFile>

    /** 删除远端文件。 */
    suspend fun delete(relPath: String): Result<Unit>
}
