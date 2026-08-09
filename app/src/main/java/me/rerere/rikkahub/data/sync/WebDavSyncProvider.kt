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

    /** 同步根（相对 config.path 的路径）：WebDavClient.buildUrl 会自动拼接 config.path，这里只保留相对部分。 */
    private val syncRoot: String = "sync"

    /** 已确认存在的集合路径缓存：避免大量小文件上传时对同一父目录重复 PROPFIND，触发服务端限流（503/429）。 */
    private val ensuredCollections = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun childPath(relPath: String): String = "$syncRoot/${relPath.trim('/')}"

    override suspend fun test(): Result<Unit> = runCatching {
        ensureSyncRootTree()
        Log.i(TAG, "test: connection ok (syncRoot=$syncRoot)")
    }

    override suspend fun ensureBaseCollection(): Result<Unit> = runCatching {
        ensureSyncRootTree()
    }

    /**
     * 逐级创建 syncRoot 路径链。WebDAV 的 MKCOL 要求父目录先存在，
     * 若配置了多级 path（如 `rikkahub_backups/sync`）而父目录缺失，单次 mkcol 会失败。
     */
    private suspend fun ensureSyncRootTree() {
        var acc = ""
        for (segment in syncRoot.trim('/').split("/")) {
            acc = if (acc.isEmpty()) segment else "$acc/$segment"
            if (!ensuredCollections.add(acc)) continue // 已确认存在，跳过重复 PROPFIND
            client.ensureCollectionExists(acc).getOrThrow()
        }
    }

    override suspend fun listRemote(): Result<List<RemoteFile>> = runCatching {
        val files = mutableListOf<RemoteFile>()

        suspend fun walk(dirRel: String) {
            val children: List<WebDavResourceInfo> = client.list(dirRel).getOrThrow()
            for (child in children) {
                val rel = toRelPath(child.href)
                // 空 rel = 目录自身（propfind depth=1 会把集合本身也返回），跳过避免递归死循环
                if (rel.isEmpty()) continue
                if (child.isCollection) {
                    // 会话增量同步用独立的 index.json + items 通道，不走整树列出，
                    // 否则每次差异检测都要拉取全部会话文件清单（上千项），徒增请求量与负载。
                    if (rel == CONVERSATION_SYNC_DIR) continue
                    // 递归用相对 config.path 的完整路径（含 syncRoot 前缀），否则会漏掉 sync 段
                    walk(childPath(rel))
                } else {
                    files += RemoteFile(
                        relPath = rel,
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

    /** 流式上传：避免大文件整读进内存（WebDAV 用文件通道流式 PUT）。 */
    override suspend fun uploadFile(relPath: String, file: File): Result<UploadResult> = runCatching {
        val rel = relPath.trim('/')
        ensureParentCollections(rel)
        client.put(childPath(rel), file).getOrThrow()
        val meta = client.head(childPath(rel)).getOrThrow()
        Log.i(TAG, "uploadFile success: $rel (${file.length()} bytes)")
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

    /** 上传前确保父目录链存在（WebDAV 无自动建目录）。已确认的目录用缓存跳过，减少 PROPFIND。 */
    private suspend fun ensureParentCollections(relPath: String) {
        val parent = relPath.substringBeforeLast("/", missingDelimiterValue = "")
        if (parent.isBlank()) return
        // 逐级 mkcol，已存在（405）视为成功
        var acc = syncRoot
        for (segment in parent.split("/")) {
            acc = "$acc/$segment"
            if (!ensuredCollections.add(acc)) continue // 已确认存在
            client.ensureCollectionExists(acc).getOrThrow()
        }
    }

    /** 把服务器返回的 href 归一化为相对 syncRoot 的 relPath。 */
    private fun toRelPath(href: String): String =
        normalizeRelPath(href, config.url, config.path, syncRoot)

    companion object {
        /**
         * 把服务器返回的 href 归一化为相对 syncRoot 的 relPath（纯函数，可单测）。
         * 兼容两种 href 格式：完整 URL（https://host/dav/...）或绝对路径（/dav/...，坚果云常见）。
         */
        internal fun normalizeRelPath(href: String, url: String, path: String, syncRoot: String): String {
            var h = href.trimEnd('/')
            if (h.startsWith("http://") || h.startsWith("https://")) {
                h = runCatching { java.net.URI(h).path ?: h }.getOrElse { h }
            }
            // 去掉 base URL 的路径段（如 /dav），兼容 href 有/无前导斜杠
            val basePath = runCatching { java.net.URI(url).path?.trim('/') ?: "" }.getOrElse { "" }
            if (basePath.isNotEmpty()) {
                val hTrimmed = h.trimStart('/')
                if (hTrimmed == basePath) return ""
                if (hTrimmed.startsWith("$basePath/")) {
                    h = hTrimmed.removePrefix("$basePath/")
                }
            }
            // 去掉 config.path 前缀（buildUrl 拼接路径时 URL 会含 config.path）
            val pathPrefix = path.trim('/')
            if (pathPrefix.isNotEmpty()) {
                if (h == pathPrefix) return ""
                if (h.startsWith("$pathPrefix/")) h = h.removePrefix("$pathPrefix/")
            }
            val root = syncRoot.trim('/')
            h = if (h == root) "" else if (h.startsWith("$root/")) h.removePrefix("$root/") else h
            return h
        }
    }
}
