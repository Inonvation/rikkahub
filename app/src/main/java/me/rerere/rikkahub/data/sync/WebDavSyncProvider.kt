package me.rerere.rikkahub.data.sync

import android.util.Log
import io.ktor.client.HttpClient
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.webdav.WebDavClient
import me.rerere.rikkahub.data.sync.webdav.WebDavResourceInfo
import java.io.File

private const val TAG = "WebDavSyncProvider"

/**
 * WebDAV 增量同步适配器。
 *
 * 与现有 zip 备份隔离：文件统一放在 `<config.path>/sync/` 子目录下，
 * relPath 即相对该 sync 根的路径。
 */
class WebDavSyncProvider(
    private val config: WebDavConfig,
    httpClient: HttpClient,
) : SyncProvider {
    private val client = WebDavClient(config, httpClient)

    /** 同步根（相对 config.url 的路径），如 `rikkahub_backups/sync`。 */
    private val syncRoot: String by lazy {
        val base = config.path.takeIf { it.isNotBlank() }?.trim('/')
        if (base.isNullOrEmpty()) "sync" else "$base/sync"
    }

    private fun childPath(relPath: String): String = "$syncRoot/${relPath.trim('/')}"

    override suspend fun test(): Result<Unit> = runCatching {
        val result = client.ensureCollectionExists(syncRoot)
        Log.i(TAG, "test: connection ok (syncRoot=$syncRoot)")
        result.getOrThrow()
    }

    override suspend fun ensureBaseCollection(): Result<Unit> {
        return client.ensureCollectionExists(syncRoot)
    }

    override suspend fun listRemote(): Result<List<RemoteFile>> = runCatching {
        val files = mutableListOf<RemoteFile>()

        suspend fun walk(dirRel: String) {
            val children: List<WebDavResourceInfo> = client.list(dirRel).getOrThrow()
            for (child in children) {
                if (child.isCollection) {
                    walk(toRelPath(child.href))
                } else {
                    files += RemoteFile(
                        relPath = toRelPath(child.href),
                        etag = child.etag,
                        lastModifiedMs = child.lastModified?.toEpochMilli(),
                        size = child.contentLength,
                    )
                }
            }
        }

        walk(syncRoot)
        files
    }

    override suspend fun upload(relPath: String, content: ByteArray): Result<UploadResult> = runCatching {
        val rel = relPath.trim('/')
        ensureParentCollections(rel)
        client.put(childPath(rel), content).getOrThrow()
        val meta = client.head(childPath(rel)).getOrThrow()
        Log.i(TAG, "upload success: $rel")
        UploadResult(
            etag = meta.etag,
            lastModifiedMs = meta.lastModified?.toEpochMilli(),
        )
    }

    override suspend fun downloadToFile(relPath: String, targetFile: File): Result<Unit> {
        return client.downloadToFile(childPath(relPath), targetFile)
    }

    override suspend fun head(relPath: String): Result<RemoteFile> = runCatching {
        val meta = client.head(childPath(relPath)).getOrThrow()
        RemoteFile(
            relPath = relPath.trim('/'),
            etag = meta.etag,
            lastModifiedMs = meta.lastModified?.toEpochMilli(),
            size = meta.contentLength,
        )
    }

    override suspend fun delete(relPath: String): Result<Unit> {
        return client.delete(childPath(relPath))
    }

    /** 上传前确保父目录链存在（WebDAV 无自动建目录）。 */
    private suspend fun ensureParentCollections(relPath: String) {
        val parent = relPath.substringBeforeLast("/", missingDelimiterValue = "")
        if (parent.isBlank()) return
        // 逐级 mkcol，已存在（405）视为成功
        var acc = syncRoot
        for (segment in parent.split("/")) {
            acc = "$acc/$segment"
            client.ensureCollectionExists(acc).getOrThrow()
        }
    }

    /** 把服务器返回的 href 归一化为相对 syncRoot 的 relPath。 */
    private fun toRelPath(href: String): String {
        var h = href.trimEnd('/')
        val baseUrl = config.url.trimEnd('/')
        if (h.startsWith(baseUrl)) {
            h = h.removePrefix(baseUrl).trimStart('/')
        }
        val root = syncRoot.trim('/')
        h = if (h == root) "" else if (h.startsWith("$root/")) h.removePrefix("$root/") else h
        return h
    }
}
