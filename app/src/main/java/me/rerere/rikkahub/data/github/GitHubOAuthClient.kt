package me.rerere.rikkahub.data.github

import android.util.Log
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GitHub OAuth Device Flow 客户端（RFC 8628）。
 *
 * 认证方式对标 gh CLI / Copilot CLI / Git Credential Manager：仅需 OAuth App 的 client_id
 * （无需 client_secret，前提是 App 设置勾选了 Enable Device Flow），用户在任意浏览器打开
 * https://github.com/login/device 输入 8 位 user_code 完成授权，应用侧按 interval 轮询取 token。
 *
 * 产出 `gho_` 开头的长期用户 token（GitHub OAuth token 默认不过期，无需刷新；失效仅发生在
 * 用户撤销授权等场景，靠 API 401 检测）。user_code 提交限 50 次/小时/应用，正常使用打不满。
 */
class GitHubOAuthClient(private val clientId: String) {
    companion object {
        private const val TAG = "GitHubOAuthClient"
        private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        private const val TOKEN_URL = "https://github.com/login/oauth/access_token"
        private const val USER_URL = "https://api.github.com/user"
        private const val VERIFICATION_URI_DEFAULT = "https://github.com/login/device"
        private const val GRANT_TYPE_DEVICE_CODE = "urn:ietf:params:oauth:grant-type:device_code"

        /** 提升配额只需任意有效 token；repo 读私仓，read:user 取账号展示信息（拍板：一次要全） */
        const val DEFAULT_SCOPE = "repo read:user"

        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT = 20_000
    }

    data class DeviceCodeStart(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresInSec: Long,
        val intervalSec: Long,
    )

    /** 轮询单次结果。SlowDown 携带的新 interval 已按官方规则（原 interval + 5s）折算 */
    sealed interface TokenPoll {
        data object Pending : TokenPoll
        data class SlowDown(val intervalSec: Long) : TokenPoll
        data object Denied : TokenPoll
        data object Expired : TokenPoll
        data class Success(val accessToken: String, val scope: String) : TokenPoll
        data class Error(val message: String) : TokenPoll
    }

    data class GitHubUserProfile(
        val login: String,
        val avatarUrl: String,
        val scopes: List<String>,
    )

    fun isConfigured(): Boolean = clientId.isNotBlank()

    /** 发起 device flow，返回展示给用户的 user_code 与轮询参数。网络失败抛异常由调用方兜底。 */
    fun startDeviceCode(scope: String = DEFAULT_SCOPE): DeviceCodeStart {
        val body = form("client_id" to clientId, "scope" to scope)
        val json = postForJson(DEVICE_CODE_URL, body)
            ?: error("无法连接 GitHub，请检查网络")
        return parseDeviceCodeResponse(json) ?: error(describeStartError(json))
    }

    /** 发起失败但响应可读时的用户可懂文案（device_flow_disabled 是最常见配置错误） */
    internal fun describeStartError(json: String): String = runCatching {
        val obj = JsonInstant.parseToJsonElement(json).jsonObject
        when (obj["error"]?.jsonPrimitive?.content) {
            "device_flow_disabled" -> "该 OAuth App 未启用 Device Flow（请在 App 设置勾选 Enable Device Flow 后重试）"
            "incorrect_client_credentials" -> "OAuth client_id 不正确，请检查 local.properties 配置"
            else -> obj["error_description"]?.jsonPrimitive?.content
                ?: obj["error"]?.jsonPrimitive?.content
                ?: "GitHub device code 响应解析失败"
        }
    }.getOrDefault("GitHub device code 响应解析失败")

    fun pollOnce(deviceCode: String): TokenPoll {
        val body = form(
            "client_id" to clientId,
            "device_code" to deviceCode,
            "grant_type" to GRANT_TYPE_DEVICE_CODE,
        )
        // 网络失败抛异常而非 Error 终态：轮询方会按瞬时错误重试直到 user_code 过期，
        // 协议级错误（device_flow_disabled 等，响应可解析）才走 Error 终止
        val json = postForJson(TOKEN_URL, body)
            ?: throw IllegalStateException("无法连接 GitHub，正在重试")
        return parseTokenResponse(json)
    }

    /** GET /user 验证 token 并取账号信息；X-OAuth-Scopes 响应头携带 token 实际 scope。失败返回 null */
    fun fetchUser(token: String): GitHubUserProfile? {
        val connection = (URL(USER_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "RikkaHub")
            setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.use { it.readBytes().decodeToString() }
            val obj = runCatching { JsonInstant.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: return null
            GitHubUserProfile(
                login = obj["login"]?.jsonPrimitive?.content ?: return null,
                avatarUrl = obj["avatar_url"]?.jsonPrimitive?.content.orEmpty(),
                scopes = connection.getHeaderField("X-OAuth-Scopes")
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                    .orEmpty(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "fetchUser failed", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseDeviceCodeResponse(json: String): DeviceCodeStart? {
        return runCatching {
            val obj = JsonInstant.parseToJsonElement(json).jsonObject
            val deviceCode = obj["device_code"]?.jsonPrimitive?.content ?: return null
            val userCode = obj["user_code"]?.jsonPrimitive?.content ?: return null
            DeviceCodeStart(
                deviceCode = deviceCode,
                userCode = userCode,
                verificationUri = obj["verification_uri"]?.jsonPrimitive?.content
                    ?: VERIFICATION_URI_DEFAULT,
                expiresInSec = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 900L,
                intervalSec = obj["interval"]?.jsonPrimitive?.content?.toLongOrNull() ?: 5L,
            )
        }.getOrNull()
    }

    /** 官方端点无论 HTTP 状态都会在 JSON 里给 access_token 或 error 字段，统一按 JSON 解析 */
    internal fun parseTokenResponse(json: String): TokenPoll {
        return runCatching {
            val obj = JsonInstant.parseToJsonElement(json).jsonObject
            val token = obj["access_token"]?.jsonPrimitive?.content
            if (!token.isNullOrBlank()) {
                return TokenPoll.Success(
                    accessToken = token,
                    scope = obj["scope"]?.jsonPrimitive?.content.orEmpty(),
                )
            }
            when (obj["error"]?.jsonPrimitive?.content) {
                "authorization_pending" -> TokenPoll.Pending
                "slow_down" -> TokenPoll.SlowDown(
                    intervalSec = (obj["interval"]?.jsonPrimitive?.content?.toLongOrNull() ?: 5L) + 5L
                )
                "expired_token", "token_expired" -> TokenPoll.Expired
                "access_denied" -> TokenPoll.Denied
                "device_flow_disabled" -> TokenPoll.Error("该 OAuth App 未启用 Device Flow（App 设置勾选 Enable Device Flow）")
                else -> TokenPoll.Error(
                    obj["error_description"]?.jsonPrimitive?.content
                        ?: obj["error"]?.jsonPrimitive?.content
                        ?: "授权失败"
                )
            }
        }.getOrElse { TokenPoll.Error("GitHub 授权响应解析失败") }
    }

    /** 统一 POST 表单并取 JSON 响应；网络失败返回 null（poll 场景由调用方决定重试语义） */
    private fun postForJson(url: String, body: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "RikkaHub")
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            stream?.use { it.readBytes().decodeToString() }
        } catch (e: Exception) {
            Log.w(TAG, "postForJson failed: $url", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun form(vararg pairs: Pair<String, String>): String {
        return pairs.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
    }
}
