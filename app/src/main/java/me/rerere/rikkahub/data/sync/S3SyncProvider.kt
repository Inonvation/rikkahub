package me.rerere.rikkahub.data.sync

import android.util.Log
import io.ktor.client.HttpClient
import me.rerere.rikkahub.data.sync.s3.S3Client
import me.rerere.rikkahub.data.sync.s3.S3Config
import java.io.File
import java.time.Instant

private const val TAG = "S3SyncProvider"

/**
 * S3 增量同步适配器。S3 为扁平 key 空间，无目录概念，
 * 所有文件统一放在 `sync/` 前缀下，relPath 即去掉该前缀的 key。
 */
class S3SyncProvider(
    private val config: S3Config,
    httpClient: HttpClient,
) : SyncProvider {
    private val client = S3Client(config, httpClient)

    private val keyPrefix = "sync/"

    private fun fullKey(relPath: String): String = "$keyPrefix${relPath.trim('/')}"

    override suspend fun test(): Result<Unit> = runCatching {
        client.listObjects(prefix = keyPrefix, maxKeys = 1).getOrThrow()
        Log.i(TAG, "test: connection ok")
    }

    override suspend fun ensureBaseCollection(): Result<Unit> {
        // S3 无目录概念，直接成功
        return Result.success(Unit)
    }

    override suspend fun listRemote(): Result<List<RemoteFile>> = runCatching {
        val files = mutableListOf<RemoteFile>()
        var token: String? = null
        do {
            val page = client.listObjects(
                prefix = keyPrefix,
                maxKeys = 1000,
                continuationToken = token,
            ).getOrThrow()
            for (obj in page.objects) {
                if (obj.key.endsWith("/")) continue
                val relPath = obj.key.removePrefix(keyPrefix)
                // 会话增量同步用独立的 index.json + items 通道，不参与整树列出（避免每次拉取上千个会话文件）
                if (relPath.startsWith("$CONVERSATION_SYNC_DIR/")) continue
                files += RemoteFile(
                    relPath = relPath,
                    etag = obj.etag,
                    lastModifiedMs = obj.lastModified?.toEpochMilli(),
                    size = obj.size,
                )
            }
            token = page.nextContinuationToken
        } while (page.isTruncated && token != null)
        files
    }

    override suspend fun upload(relPath: String, content: ByteArray): Result<UploadResult> = runCatching {
        val rel = relPath.trim('/')
        client.putObject(fullKey(rel), content).getOrThrow()
        val meta = client.headObject(fullKey(rel)).getOrThrow()
        Log.i(TAG, "upload success: $rel")
        UploadResult(
            etag = meta.etag,
            lastModifiedMs = parseLastModified(meta.lastModified),
        )
    }

    /** 流式上传：避免大文件整读进内存（S3 用文件通道流式 PUT）。 */
    override suspend fun uploadFile(relPath: String, file: File): Result<UploadResult> = runCatching {
        val rel = relPath.trim('/')
        client.putObject(fullKey(rel), file).getOrThrow()
        val meta = client.headObject(fullKey(rel)).getOrThrow()
        Log.i(TAG, "uploadFile success: $rel (${file.length()} bytes)")
        UploadResult(
            etag = meta.etag,
            lastModifiedMs = parseLastModified(meta.lastModified),
        )
    }

    override suspend fun downloadToFile(relPath: String, targetFile: File): Result<Unit> {
        return client.downloadObjectToFile(fullKey(relPath), targetFile)
    }

    override suspend fun head(relPath: String): Result<RemoteFile> = runCatching {
        val meta = client.headObject(fullKey(relPath)).getOrThrow()
        RemoteFile(
            relPath = relPath.trim('/'),
            etag = meta.etag,
            lastModifiedMs = parseLastModified(meta.lastModified),
            size = meta.size,
        )
    }

    override suspend fun delete(relPath: String): Result<Unit> {
        return client.deleteObject(fullKey(relPath))
    }

    private fun parseLastModified(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value).toEpochMilli() }
            .getOrElse {
                Log.w(TAG, "Failed to parse last-modified: $value")
                null
            }
    }
}
