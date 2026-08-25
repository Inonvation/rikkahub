package me.rerere.rikkahub.data.ai.openai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.provider.OPENAI_CODEX_BASE_URL
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.OpenAICodexCredentials
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.openai.OpenAICodexTokenProvider
import me.rerere.common.http.await
import me.rerere.rikkahub.data.datastore.SettingsStore
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val AUTH_BASE_URL = "https://auth.openai.com"
private const val DEVICE_AUTH_BASE_URL = "$AUTH_BASE_URL/api/accounts/deviceauth"
private const val DEVICE_VERIFICATION_URL = "$AUTH_BASE_URL/codex/device"
private const val TOKEN_URL = "$AUTH_BASE_URL/oauth/token"
private const val DEVICE_CALLBACK_URL = "$AUTH_BASE_URL/deviceauth/callback"

// 官方 Codex 客户端使用的公开 OAuth client id（非机密），与 codex-rs login 常量一致：
// codex-rs/login/src/auth/manager.rs 中 `pub const CLIENT_ID: &str = "app_EMoamEEZ73f0CkXaXp7hrann";`
private const val CODEX_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
private const val DEFAULT_POLL_INTERVAL_SECONDS = 5L
private const val DEVICE_AUTH_TIMEOUT_MS = 15 * 60 * 1000L
private const val TOKEN_REFRESH_LEEWAY_MS = 5 * 60 * 1000L
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/** 浏览器设备授权时展示给用户的一次性代码。 */
data class OpenAICodexDeviceCode(
    val userCode: String,
    val verificationUrl: String = DEVICE_VERIFICATION_URL,
)

/**
 * Codex 设备授权登录与订阅 Token 管理。
 *
 * 流程与官方 Codex CLI 的 `codex --login`（ChatGPT 账号）一致：
 * 1. `POST auth.openai.com/api/accounts/deviceauth/usercode` 获取 device_auth_id / user_code；
 * 2. 用户在浏览器打开 `auth.openai.com/codex/device` 输入一次性代码；
 * 3. 轮询 `deviceauth/token` 直到拿到 authorization_code；
 * 4. 用 authorization_code + code_verifier 在 `oauth/token` 换取 access/refresh token。
 *
 * access token 过期前 5 分钟自动刷新；刷新与读取用 per-provider 互斥锁防并发。
 */
class OpenAICodexAuthService(
    private val httpClient: OkHttpClient,
    private val settingsStore: SettingsStore,
) : OpenAICodexTokenProvider {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val credentialCache = ConcurrentHashMap<Uuid, OpenAICodexCredentials>()
    private val refreshLocks = ConcurrentHashMap<Uuid, Mutex>()

    @Serializable
    private data class DeviceAuthorizationResponse(
        @SerialName("authorization_code") val authorizationCode: String,
        @SerialName("code_verifier") val codeVerifier: String,
    )

    /** 登录与刷新共用的 token 响应字段。 */
    private interface OAuthTokenData {
        val accessToken: String
        val refreshToken: String?
        val idToken: String?
        val expiresIn: Long?
        val accountId: String?
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") override val accessToken: String,
        @SerialName("refresh_token") override val refreshToken: String? = null,
        @SerialName("id_token") override val idToken: String? = null,
        @SerialName("expires_in") override val expiresIn: Long? = null,
        @SerialName("account_id") override val accountId: String? = null,
    ) : OAuthTokenData

    suspend fun signIn(
        providerId: Uuid,
        onDeviceCodeReady: (OpenAICodexDeviceCode) -> Unit,
    ): OpenAICodexCredentials {
        val credentials = withContext(Dispatchers.IO) {
            val deviceCode = requestDeviceCode()
            withContext(Dispatchers.Main) {
                onDeviceCodeReady(OpenAICodexDeviceCode(deviceCode.userCode))
            }
            val authorization = awaitDeviceAuthorization(deviceCode)
            val token = exchangeAuthorizationCode(authorization)
            token.toCredentials(previous = null)
        }
        credentialCache[providerId] = credentials
        persistCredentials(providerId, credentials, activateSubscription = true)
        return credentials
    }

    /**
     * 导入已有凭据（官方 Codex auth.json / 其它来源的 access token），
     * 不经过设备授权，适用于无法完成手机验证等场景。
     *
     * 导入前会向官方 Codex 后端做一次轻量验证：明确 401 时拒绝导入并给出提示，
     * 避免"导入成功但发消息才报 key 无效"的困惑（网页会话 token / 中转站 token
     * 通常无法用于官方后端）。网络异常等非 401 情况不阻塞导入。
     *
     * 凭据仍只用于官方 Codex 端点；refresh token 存在时支持自动续期。
     */
    suspend fun importCredentials(
        providerId: Uuid,
        accessToken: String,
        refreshToken: String?,
        accountId: String,
        email: String? = null,
        planType: String? = null,
    ): OpenAICodexCredentials {
        require(accessToken.isNotBlank()) { "access_token 不能为空" }
        require(accountId.isNotBlank()) { "account_id 不能为空" }
        validateWithOfficialBackend(accessToken, accountId)
        val expiresAt = decodeJwtPayload(accessToken)
            ?.get("exp")?.jsonPrimitive?.longOrNull?.times(1000L) ?: 0L
        val credentials = OpenAICodexCredentials(
            accessToken = accessToken,
            refreshToken = refreshToken?.takeIf { it.isNotBlank() } ?: "",
            accountId = accountId,
            expiresAt = expiresAt,
            email = email,
            planType = planType,
        )
        credentialCache[providerId] = credentials
        persistCredentials(providerId, credentials, activateSubscription = true)
        return credentials
    }

    /** 用与请求层一致的认证头探测官方 Codex 后端，仅把明确的 401 视为不可用。 */
    private suspend fun validateWithOfficialBackend(accessToken: String, accountId: String) {
        val request = Request.Builder()
            .url("$OPENAI_CODEX_BASE_URL/models")
            .header("Authorization", "Bearer $accessToken")
            .header("ChatGPT-Account-Id", accountId)
            .header("originator", "rikkahub")
            .get()
            .build()
        val response = httpClient.newCall(request).await()
        try {
            if (response.code == 401) {
                error(
                    "导入的凭据无法通过官方 Codex 后端验证（HTTP 401）。" +
                        "该 token 可能已过期，或是网页会话 / 中转站专用 token，无法用于官方端点。" +
                        "请重新登录，或导入包含 refresh_token 的官方 auth.json。"
                )
            }
        } finally {
            response.close()
        }
    }

    suspend fun signOut(providerId: Uuid) {
        // 与刷新串行化：若此时有刷新正在进行，等它完成后我们再清空，
        // 避免刷新在登出之后把旧凭据重新写回（refresh token 幽灵复活）。
        refreshLocks.computeIfAbsent(providerId) { Mutex() }.withLock {
            credentialCache.remove(providerId)
            persistCredentials(providerId, credentials = null, activateSubscription = false)
        }
        refreshLocks.remove(providerId)
    }

    override suspend fun getCredentials(
        providerSetting: ProviderSetting.OpenAI,
    ): OpenAICodexCredentials {
        val initial = credentialCache[providerSetting.id]
            ?: providerSetting.codexCredentials
            ?: error("OpenAI Codex is not signed in. Sign in with ChatGPT first.")
        if (!initial.needsRefresh()) return initial

        val lock = refreshLocks.computeIfAbsent(providerSetting.id) { Mutex() }
        return lock.withLock {
            val latestSetting = settingsStore.settingsFlow.value.providers
                .filterIsInstance<ProviderSetting.OpenAI>()
                .firstOrNull { it.id == providerSetting.id }
            val latestCredentials = latestSetting?.codexCredentials
            // 配置最新状态已登出时，拒绝用调用方的过期快照继续刷新（防止登出后幽灵复活）。
            if (latestSetting != null && latestCredentials == null) {
                credentialCache.remove(providerSetting.id)
                error("OpenAI Codex is not signed in. Sign in with ChatGPT first.")
            }
            val current = credentialCache[providerSetting.id]
                ?: latestCredentials
                ?: initial
            if (!current.needsRefresh()) return@withLock current
            if (current.refreshToken.isBlank()) {
                credentialCache.remove(providerSetting.id)
                error(
                    "ChatGPT 订阅凭据已过期，且没有 refresh_token 无法自动续期。" +
                        "请重新登录，或重新导入包含 refresh_token 的官方 auth.json。"
                )
            }

            val refreshed = refresh(current)
            credentialCache[providerSetting.id] = refreshed
            persistCredentials(
                providerId = providerSetting.id,
                credentials = refreshed,
                activateSubscription = false,
            )
            refreshed
        }
    }

    private suspend fun requestDeviceCode(): DeviceCodeResponse {
        val requestBody = json.encodeToString(
            buildJsonObject { put("client_id", CODEX_CLIENT_ID) }
        )
        val request = Request.Builder()
            .url("$DEVICE_AUTH_BASE_URL/usercode")
            .header("Accept", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = execute(request)
        if (response.code == 404) {
            // 官方语义：设备码登录未对该账号开启（chatgpt.com/settings/security）。
            error(
                "OpenAI Codex device login is not enabled for this account. " +
                    "Enable device-code sign-in at chatgpt.com/settings/security and try again."
            )
        }
        if (response.code !in 200..299) {
            error(httpError("Failed to start OpenAI Codex sign-in", response))
        }
        return json.decodeFromString(response.body)
    }

    private suspend fun awaitDeviceAuthorization(
        deviceCode: DeviceCodeResponse,
    ): DeviceAuthorizationResponse {
        val deadline = System.currentTimeMillis() + DEVICE_AUTH_TIMEOUT_MS
        val intervalMs = deviceCode.intervalSeconds.coerceAtLeast(1L) * 1000L
        while (System.currentTimeMillis() < deadline) {
            val requestBody = json.encodeToString(
                buildJsonObject {
                    put("device_auth_id", deviceCode.deviceAuthId)
                    put("user_code", deviceCode.userCode)
                }
            )
            val request = Request.Builder()
                .url("$DEVICE_AUTH_BASE_URL/token")
                .header("Accept", "application/json")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val response = execute(request)
            if (response.code in 200..299) {
                return json.decodeFromString(response.body)
            }
            if (!response.isAuthorizationPending()) {
                error(httpError("OpenAI Codex sign-in was rejected", response))
            }
            delay(intervalMs)
        }
        error("OpenAI Codex sign-in timed out. Start the sign-in flow again.")
    }

    private suspend fun exchangeAuthorizationCode(
        authorization: DeviceAuthorizationResponse,
    ): TokenResponse {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", authorization.authorizationCode)
            .add("redirect_uri", DEVICE_CALLBACK_URL)
            .add("client_id", CODEX_CLIENT_ID)
            .add("code_verifier", authorization.codeVerifier)
            .build()
        return executeTokenRequest(form, "Failed to finish OpenAI Codex sign-in")
    }

    private suspend fun refresh(previous: OpenAICodexCredentials): OpenAICodexCredentials {
        // 与官方 codex-rs（auth/manager.rs RefreshRequest）一致：JSON body 刷新。
        val jsonBody = json.encodeToString(
            buildJsonObject {
                put("client_id", CODEX_CLIENT_ID)
                put("grant_type", "refresh_token")
                put("refresh_token", previous.refreshToken)
            }
        )
        val request = Request.Builder()
            .url(TOKEN_URL)
            .header("Accept", "application/json")
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = execute(request)
        if (response.code !in 200..299) error(httpError("Failed to refresh OpenAI Codex sign-in", response))
        return json.decodeFromString<RefreshTokenResponse>(response.body).toCredentials(previous)
    }

    @Serializable
    private data class RefreshTokenResponse(
        @SerialName("access_token") override val accessToken: String,
        @SerialName("refresh_token") override val refreshToken: String? = null,
        @SerialName("id_token") override val idToken: String? = null,
        @SerialName("expires_in") override val expiresIn: Long? = null,
        @SerialName("account_id") override val accountId: String? = null,
    ) : OAuthTokenData

    private suspend fun executeTokenRequest(form: FormBody, errorPrefix: String): TokenResponse {
        val request = Request.Builder()
            .url(TOKEN_URL)
            .header("Accept", "application/json")
            .post(form)
            .build()
        val response = execute(request)
        if (response.code !in 200..299) error(httpError(errorPrefix, response))
        return json.decodeFromString(response.body)
    }

    private suspend fun execute(request: Request): HttpResult {
        return httpClient.newCall(request).await().use { response ->
            HttpResult(response.code, response.body.string())
        }
    }

    private fun OAuthTokenData.toCredentials(
        previous: OpenAICodexCredentials?,
    ): OpenAICodexCredentials {
        val accessClaims = decodeJwtPayload(accessToken)
        val idClaims = idToken?.let(::decodeJwtPayload)
        val authClaims = idClaims?.get("https://api.openai.com/auth")?.jsonObject
            ?: accessClaims?.get("https://api.openai.com/auth")?.jsonObject
        val resolvedAccountId = accountId
            ?: authClaims?.get("chatgpt_account_id")?.jsonPrimitive?.contentOrNull
            ?: idClaims?.get("chatgpt_account_id")?.jsonPrimitive?.contentOrNull
            ?: accessClaims?.get("chatgpt_account_id")?.jsonPrimitive?.contentOrNull
            ?: previous?.accountId
            ?: error("OpenAI Codex sign-in did not return a ChatGPT account ID.")
        val expiresAt = expiresIn?.takeIf { it > 0 }?.let {
            System.currentTimeMillis() + it * 1000L
        } ?: accessClaims?.get("exp")?.jsonPrimitive?.longOrNull?.times(1000L)
            ?: previous?.expiresAt
            ?: 0L

        return OpenAICodexCredentials(
            accessToken = accessToken,
            refreshToken = refreshToken ?: previous?.refreshToken
                ?: error("OpenAI Codex sign-in did not return a refresh token."),
            accountId = resolvedAccountId,
            expiresAt = expiresAt,
            email = idClaims?.get("email")?.jsonPrimitive?.contentOrNull
                ?: accessClaims?.get("email")?.jsonPrimitive?.contentOrNull
                ?: previous?.email,
            planType = authClaims?.get("chatgpt_plan_type")?.jsonPrimitive?.contentOrNull
                ?: previous?.planType,
        )
    }

    private fun decodeJwtPayload(token: String): kotlinx.serialization.json.JsonObject? =
        me.rerere.rikkahub.data.ai.openai.decodeJwtPayload(token)

    private fun OpenAICodexCredentials.needsRefresh(): Boolean =
        expiresAt > 0L && System.currentTimeMillis() >= expiresAt - TOKEN_REFRESH_LEEWAY_MS

    private suspend fun persistCredentials(
        providerId: Uuid,
        credentials: OpenAICodexCredentials?,
        activateSubscription: Boolean,
    ) {
        settingsStore.update { settings ->
            settings.copy(
                providers = settings.providers.map { provider ->
                    if (provider !is ProviderSetting.OpenAI || provider.id != providerId) {
                        provider
                    } else {
                        provider.copy(
                            authType = if (activateSubscription) {
                                OpenAIAuthType.CHATGPT_SUBSCRIPTION
                            } else {
                                provider.authType
                            },
                            codexCredentials = credentials,
                            // 注意：不再改写 baseUrl。订阅模式下请求统一走官方 Codex 端点
                            // （ai 模块 effectiveBaseUrl），切回 API Key 时原 baseUrl 原样保留。
                            useResponseApi = if (activateSubscription) true else provider.useResponseApi,
                        )
                    }
                }
            )
        }
    }

    private fun HttpResult.isAuthorizationPending(): Boolean {
        if (code !in setOf(400, 403, 404, 409, 429)) return false
        val error = errorMessage().lowercase()
        return error.isBlank() ||
            error.contains("pending") ||
            error.contains("authorization") ||
            error.contains("not found") ||
            error.contains("slow_down")
    }

    private fun httpError(prefix: String, response: HttpResult): String {
        val detail = response.errorMessage().takeIf { it.isNotBlank() }
            ?: if (response.code == 403) {
                "OpenAI denied this network request. Make sure the device can access auth.openai.com and uses the same proxy or VPN as the host."
            } else {
                null
            }
        return buildString {
            append(prefix)
            append(" (HTTP ${response.code})")
            if (detail != null) append(": $detail")
        }
    }

    private fun HttpResult.errorMessage(): String = runCatching {
        extractOpenAIAuthError(body)
    }.getOrDefault("")

    private data class HttpResult(val code: Int, val body: String)
}

@Serializable
internal data class DeviceCodeResponse(
    @SerialName("device_auth_id") val deviceAuthId: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("interval") private val rawInterval: JsonElement? = null,
) {
    val intervalSeconds: Long
        get() = (rawInterval as? JsonPrimitive)?.longOrNull
            ?: DEFAULT_POLL_INTERVAL_SECONDS
}

/** 从 OpenAI auth 错误响应（JSON/HTML）中提取可读的错误信息。 */
internal fun extractOpenAIAuthError(body: String): String {
    val normalizedBody = body.trim()
    if (normalizedBody.isBlank()) return ""

    val root = runCatching {
        Json.parseToJsonElement(normalizedBody) as? JsonObject
    }.getOrNull()
    val message = root?.firstString("error_description", "message", "detail")
        ?: when (val error = root?.get("error")) {
            is JsonPrimitive -> error.contentOrNull
            is JsonObject -> error.firstString("message", "error_description", "code", "type")
            else -> null
        }
    if (!message.isNullOrBlank()) return message.normalizedForError()

    return normalizedBody
        .replace(Regex("<[^>]+>"), " ")
        .normalizedForError()
}

private fun JsonObject.firstString(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
}

private fun String.normalizedForError(): String =
    replace(Regex("\\s+"), " ").trim().take(500)
