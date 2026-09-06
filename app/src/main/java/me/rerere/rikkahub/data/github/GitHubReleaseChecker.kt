package me.rerere.rikkahub.data.github

import android.util.Log
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内「检查更新」：读 fork 仓库（Inonvation/rikkahub）的 GitHub Releases 最新版。
 *
 * 发布惯例（见 .agents/skills/publish-release）：tag 名为版本号不带 v 前缀（如 `2.12.1`），
 * 仅上传 arm64 APK 且文件名带版本号。比较逻辑按 `X.Y.Z` 数值三元组；
 * 已绑定账号时请求带 Bearer（不占未认证配额），未绑定也可手动检查（60 次/小时足够）。
 */
class GitHubReleaseChecker(private val tokenProvider: () -> String? = { null }) {
    companion object {
        private const val TAG = "GitHubReleaseChecker"
        private const val REPO = "Inonvation/rikkahub"
        private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
        private const val RELEASES_PAGE = "https://github.com/$REPO/releases"
        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT = 20_000
    }

    data class AppRelease(
        val tagName: String,
        val htmlUrl: String,
        val apkUrl: String?,
    )

    sealed interface CheckResult {
        data class UpToDate(val tagName: String) : CheckResult
        data class Available(val release: AppRelease) : CheckResult
        data class Unavailable(val reason: String) : CheckResult
    }

    /** 阻塞请求（调用方需在 IO 线程执行） */
    fun check(currentVersionName: String): CheckResult {
        val connection = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "RikkaHub")
            tokenProvider()?.takeIf { it.isNotBlank() }?.let {
                setRequestProperty("Authorization", "Bearer $it")
            }
        }
        return try {
            when (connection.responseCode) {
                200 -> {
                    val body = connection.inputStream.use { it.readBytes().decodeToString() }
                    val release = parseRelease(body)
                        ?: return CheckResult.Unavailable("GitHub Releases 响应解析失败")
                    if (compareVersionNames(release.tagName, currentVersionName) > 0) {
                        CheckResult.Available(release)
                    } else {
                        CheckResult.UpToDate(release.tagName)
                    }
                }
                404 -> CheckResult.Unavailable("仓库还没有发布版本")
                403 -> CheckResult.Unavailable("GitHub API 限流，请稍后再试")
                else -> CheckResult.Unavailable("HTTP ${connection.responseCode}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "check release failed", e)
            CheckResult.Unavailable("网络失败：${e.message ?: "unknown"}")
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseRelease(json: String): AppRelease? {
        return runCatching {
            val obj = JsonInstant.parseToJsonElement(json).jsonObject
            val tag = obj["tag_name"]?.jsonPrimitive?.content ?: return null
            val apkUrl = obj["assets"]?.jsonArray.orEmpty()
                .filterIsInstance<JsonObject>()
                .firstOrNull { asset ->
                    val name = asset["name"]?.jsonPrimitive?.content.orEmpty()
                    name.endsWith(".apk", ignoreCase = true) && name.contains("arm64", ignoreCase = true)
                }
                ?.get("browser_download_url")?.jsonPrimitive?.content
            AppRelease(
                tagName = tag,
                htmlUrl = obj["html_url"]?.jsonPrimitive?.content ?: RELEASES_PAGE,
                apkUrl = apkUrl,
            )
        }.getOrNull()
    }

    /**
     * 版本号数值比较（tag 与 BuildConfig.VERSION_NAME 同为 `X.Y.Z` 惯例）：
     * 逐段取数字（非数字段按 0），缺段补 0 —— "2.13" > "2.12.1"、"2.12" == "2.12.0"。
     */
    internal fun compareVersionNames(a: String, b: String): Int {
        val pa = a.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }
}
