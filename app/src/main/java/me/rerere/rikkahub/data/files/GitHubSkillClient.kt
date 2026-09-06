package me.rerere.rikkahub.data.files

import android.util.Log
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * GitHub 技能来源的网络客户端：仓库 URL 解析、列树、文件下载、路径最新 commit 查询。
 *
 * 从 SkillsVM 抽出共用：导入（SkillsVM）与更新检查（SkillUpdateManager）走同一套逻辑，
 * 保证「安装的内容」和「更新的内容」来源一致。
 *
 * 更新检测方式对标 git-based 工具（oh-my-zsh / lazy.nvim / Claude Code 插件市场）的
 * 「记录安装时 commit SHA → 对比远端最新 SHA」模式，但适配 Android 无 git 二进制：
 * 用 GitHub commits API（`commits?sha={branch}&path={path}&per_page=1`）取「影响该技能
 * 目录的最新 commit」，配合 ETag 条件请求（304 不消耗未认证 API 配额，GitHub 官方
 * 推荐的轮询方式）。
 *
 * 请求策略（针对未认证 API 配额 60 次/小时/IP 与 raw.githubusercontent.com 连通性）：
 * - 列目录用 git trees API 一次性取全树（1 个 API 请求），替代按子目录递归的 N 请求；
 * - 文件内容优先走 raw.githubusercontent.com（无配额），失败回退 contents API 的
 *   base64（占配额但在 raw 被墙/受干扰时可用），两条路互补；
 * - 全程按字节读写（ByteArray），二进制资源（图标等）不再被 UTF-8 解码损坏。
 */
class GitHubSkillClient {
    companion object {
        private const val TAG = "GitHubSkillClient"
        private const val API_BASE = "https://api.github.com"
        private const val RAW_BASE = "https://raw.githubusercontent.com"
        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT = 30_000
        private const val HTTP_NOT_MODIFIED = 304
    }

    /**
     * 解析后的仓库信息。[branch] 为空串表示「跟随默认分支」（URL 未带 /tree/branch 时），
     * 请求时省略 ref/sha 参数，分支改名也不失效。
     */
    data class GitHubRepoInfo(
        val owner: String,
        val repo: String,
        val branch: String,
        val path: String,
    )

    /** commits API 查询结果 */
    sealed class CommitCheck {
        /** 条件请求命中 304：自上次 etag 以来该路径无新提交 */
        data object NotModified : CommitCheck()

        data class Head(val sha: String, val etag: String?) : CommitCheck()

        data class Failed(val code: Int, val reason: String) : CommitCheck()
    }

    /** 列树结果。[paths] 为相对 [GitHubRepoInfo.path] 的文件路径列表 */
    sealed class ListResult {
        data class Success(val paths: List<String>) : ListResult()

        data class Failed(val reason: String) : ListResult()
    }

    /** 批量下载结果。[files] 的 key 为仓库内绝对路径 */
    sealed class FetchResult {
        data class Success(val files: Map<String, ByteArray>) : FetchResult()

        data class Failed(val reason: String) : FetchResult()
    }

    /**
     * 解析 GitHub 仓库 URL。支持的形态（query/fragment、www.、.git 后缀会被自动剥离）：
     * - https://github.com/owner/repo —— 仓库根
     * - https://github.com/owner/repo/tree/branch —— 分支根
     * - https://github.com/owner/repo/tree/branch/sub/path —— 技能子目录
     * - https://github.com/owner/repo/blob/branch/path/to/file —— 文件链接（取所在目录）
     */
    fun parseGitHubUrl(url: String): GitHubRepoInfo? {
        val cleaned = url.trim()
            .substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')
            .replaceFirst("https://www.github.com", "https://github.com", ignoreCase = true)
            .replaceFirst("http://github.com", "https://github.com", ignoreCase = true)
            .replaceFirst("http://www.github.com", "https://github.com", ignoreCase = true)
        val regex = Regex("""https://github\.com/([^/]+)/([^/]+?)(?:/(?<seg>tree|blob)/([^/]+)(/.*)?)?""")
        val match = regex.matchEntire(cleaned) ?: return null
        val repo = match.groupValues[2].removeSuffix(".git")
        if (repo.isBlank()) return null
        val branch = match.groupValues[4]
        var path = match.groupValues[5].trimStart('/')
        if (match.groups["seg"]?.value == "blob" && path.isNotBlank()) {
            // 文件链接 → 取所在目录（技能入口以目录为单位）；根文件时目录为空
            path = path.substringBeforeLast('/', missingDelimiterValue = "")
        }
        if (path.isNotBlank()) {
            // 网页复制的路径可能是 URL 编码的（如 skills%2Fppt-master）；解码时保护字面 +
            path = runCatching {
                URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
            }.getOrDefault(path)
        }
        return GitHubRepoInfo(
            owner = match.groupValues[1],
            repo = repo,
            branch = branch,
            path = path,
        )
    }

    /**
     * 用 git trees API 一次性列出 [info.path] 下的全部文件（含嵌套子目录），
     * 返回相对 [info.path] 的路径列表。1 个 API 请求，替代逐目录递归的 N 请求。
     */
    fun listTreeFiles(info: GitHubRepoInfo): ListResult {
        val ref = if (info.branch.isBlank()) "HEAD" else info.branch
        val url = "$API_BASE/repos/${info.owner}/${info.repo}/git/trees/${encodeSegment(ref)}?recursive=1"
        val (code, body, _) = request(url, etag = null)
        if (code != 200) return ListResult.Failed(describeHttpError(code))
        return runCatching {
            val obj = JsonInstant.parseToJsonElement(body!!.decodeToString()).jsonObject
            if (obj["truncated"]?.jsonPrimitive?.booleanOrNull == true) {
                return ListResult.Failed("仓库文件过多，无法列出目录")
            }
            val prefix = if (info.path.isBlank()) "" else "${info.path}/"
            val paths = obj["tree"]?.jsonArray.orEmpty().mapNotNull { element ->
                val item = element.jsonObject
                val absPath = item["path"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (item["type"]?.jsonPrimitive?.content != "blob") return@mapNotNull null
                if (info.path.isBlank()) absPath else absPath.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
            }
            ListResult.Success(paths)
        }.getOrElse { e ->
            Log.w(TAG, "listTreeFiles: parse failed", e)
            ListResult.Failed("解析 GitHub 响应失败")
        }
    }

    /** 下载一批仓库内绝对路径文件；raw 优先，失败回退 contents API base64。 */
    fun downloadFilesByAbsPath(info: GitHubRepoInfo, absPaths: List<String>): FetchResult {
        val result = LinkedHashMap<String, ByteArray>()
        val ref = if (info.branch.isBlank()) "HEAD" else info.branch
        for (absPath in absPaths) {
            val rawUrl = "$RAW_BASE/${info.owner}/${info.repo}/$ref/${encodePath(absPath)}"
            val content = downloadBytes(rawUrl)
                ?: fetchViaContentsApi(info, absPath)
                ?: return FetchResult.Failed("下载失败：$absPath（raw 与 API 均不可达）")
            result[absPath] = content
        }
        return FetchResult.Success(result)
    }

    /** 拉取 [info.path] 下全部文件（相对路径 key）。更新应用路径使用。 */
    fun fetchSkillFiles(info: GitHubRepoInfo): FetchResult {
        val listed = listTreeFiles(info)
        if (listed is ListResult.Failed) return FetchResult.Failed(listed.reason)
        val relPaths = (listed as ListResult.Success).paths
        val prefix = if (info.path.isBlank()) "" else "${info.path}/"
        val fetched = downloadFilesByAbsPath(info, relPaths.map { prefix + it })
        return when (fetched) {
            is FetchResult.Failed -> fetched
            is FetchResult.Success -> FetchResult.Success(fetched.files.mapKeys { it.key.removePrefix(prefix) })
        }
    }

    /**
     * 在列树结果中找技能根目录（相对 [info.path]）：根有 SKILL.md → 单技能；
     * 否则扫描**任意层级**的 SKILL.md（skills/<name>/ 布局常见于技能合集仓库），
     * 嵌套在其他技能目录内部的（技能自带的示例/子技能）不重复算作独立技能。
     */
    internal fun findSkillRoots(relativePaths: List<String>): List<String> {
        if (relativePaths.contains("SKILL.md")) return listOf("")
        val dirs = relativePaths
            .filter { it.endsWith("/SKILL.md") }
            .map { it.removeSuffix("/SKILL.md") }
        return dirs.filter { dir ->
            dirs.none { other -> other != dir && dir.startsWith("$other/") }
        }.sorted()
    }

    /** raw 之外的第二条路：contents API 单文件 base64。文件超过 1MB 时 GitHub 不返回 content，会失败。 */
    private fun fetchViaContentsApi(info: GitHubRepoInfo, absPath: String): ByteArray? {
        val refParam = if (info.branch.isBlank()) "" else "?ref=" + encodeSegment(info.branch)
        val url = "$API_BASE/repos/${info.owner}/${info.repo}/contents/${encodePath(absPath)}$refParam"
        val (code, body, _) = request(url, etag = null)
        if (code != 200 || body == null) return null
        return runCatching {
            val obj = JsonInstant.parseToJsonElement(body.decodeToString()).jsonObject
            val content = obj["content"]?.jsonPrimitive?.content
            if (obj["encoding"]?.jsonPrimitive?.content == "base64" && content != null) {
                Base64.getMimeDecoder().decode(content)
            } else {
                null
            }
        }.getOrElse { e ->
            Log.w(TAG, "fetchViaContentsApi: parse failed: $absPath", e)
            null
        }
    }

    /**
     * 查询「影响该路径的最新 commit」（path 为空即分支 HEAD）。
     * 传入上次记录的 etag 时走条件请求，304 表示无变化。
     */
    fun getPathCommitHead(info: GitHubRepoInfo, etag: String?): CommitCheck {
        val shaParam = if (info.branch.isBlank()) "" else "&sha=" + encodeSegment(info.branch)
        val pathParam = if (info.path.isBlank()) "" else "&path=" + encodePath(info.path)
        val url = "$API_BASE/repos/${info.owner}/${info.repo}/commits?per_page=1$shaParam$pathParam"
        val (code, body, newEtag) = request(url, etag)
        return when {
            code == HTTP_NOT_MODIFIED -> CommitCheck.NotModified
            code == 200 -> {
                val sha = parseCommitsResponse(body?.decodeToString())
                if (sha == null) {
                    CommitCheck.Failed(200, "响应中无 commit 信息")
                } else {
                    CommitCheck.Head(sha, newEtag)
                }
            }
            else -> CommitCheck.Failed(code, describeHttpError(code))
        }
    }

    /** 解析 commits API 响应（数组取第一条的 sha）。供单元测试直接验证。 */
    internal fun parseCommitsResponse(json: String?): String? {
        if (json.isNullOrBlank()) return null
        // 用 kotlinx 而非 org.json：本方法被 JVM 单测直接调用，org.json 在 JVM 是抛异常的 stub
        return runCatching {
            val array = JsonInstant.parseToJsonElement(json).jsonArray
            val sha = array.firstOrNull()?.jsonObject?.get("sha")?.jsonPrimitive?.content
            sha?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun describeHttpError(code: Int): String = when (code) {
        403 -> "GitHub API 限流或无权限（未认证配额 60 次/小时/IP，请稍后再试）"
        404 -> "仓库、分支或路径不存在（私有仓库暂不支持）"
        409 -> "仓库为空"
        else -> "HTTP $code"
    }

    /** 统一的 GET：返回 (响应码, 响应体字节, ETag)。非 2xx/304 时 body 可能为 null。 */
    private fun request(url: String, etag: String?): Triple<Int, ByteArray?, String?> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "RikkaHub")
        if (!etag.isNullOrBlank()) {
            connection.setRequestProperty("If-None-Match", etag)
        }
        return try {
            val code = connection.responseCode
            val body = if (code == 200) connection.inputStream.use { it.readBytes() } else null
            val newEtag = connection.getHeaderField("ETag")
            Triple(code, body, newEtag)
        } finally {
            connection.disconnect()
        }
    }

    fun downloadText(url: String): String? {
        return downloadBytes(url)?.decodeToString()
    }

    private fun downloadBytes(url: String): ByteArray? {
        return try {
            request(url, etag = null).second
        } catch (e: Exception) {
            Log.w(TAG, "downloadBytes failed: $url", e)
            null
        }
    }

    /** 仅对路径单段编码，保留路径分隔符；URLEncoder 是 form 编码，空格需修正为 %20 */
    private fun encodePath(path: String): String {
        return path.split('/').joinToString("/") { segment -> encodeSegment(segment) }
    }

    private fun encodeSegment(segment: String): String {
        return URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }
}
