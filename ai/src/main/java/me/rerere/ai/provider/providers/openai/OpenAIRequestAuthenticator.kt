package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.OpenAICodexCredentials
import me.rerere.ai.provider.OPENAI_CODEX_BASE_URL
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.KeyRoulette
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/**
 * 向 OpenAI Provider 请求层提供「最新」的 ChatGPT 订阅凭据。
 *
 * 由 app 模块的 [OpenAICodexAuthService][me.rerere.rikkahub.data.ai.openai.OpenAICodexAuthService]
 * 实现：读取配置中的凭据、按需刷新并回写。ai 模块只依赖该函数式接口，保持模块边界。
 */
fun interface OpenAICodexTokenProvider {
    suspend fun getCredentials(providerSetting: ProviderSetting.OpenAI): OpenAICodexCredentials
}

/**
 * 订阅模式下实际请求的 base URL 恒为官方 Codex 端点。
 *
 * 这样「认证方式」切换不再改写用户的 baseUrl：内置第三方 Provider（如 RikkaHub）
 * 也能使用订阅，且切回 API Key 时原地址原样保留；订阅凭据也绝不会发往非官方端点。
 */
internal fun ProviderSetting.OpenAI.effectiveBaseUrl(): String =
    if (authType == OpenAIAuthType.CHATGPT_SUBSCRIPTION) OPENAI_CODEX_BASE_URL else baseUrl

/**
 * 集中处理认证头，使所有 OpenAI 端点都按所选认证方式附加凭据。
 *
 * - API Key：与原有逻辑完全一致（`Authorization: Bearer <apiKey>`）。
 * - ChatGPT 订阅：仅允许官方 Codex 端点（chatgpt.com/backend-api/codex），
 *   附加 access token、账号 ID 与 originator 头；向第三方 OpenAI-compatible
 *   地址发送订阅凭据会直接抛错（请求 host 硬校验，与配置 baseUrl 无关）。
 */
internal class OpenAIRequestAuthenticator(
    private val keyRoulette: KeyRoulette,
    private val codexTokenProvider: OpenAICodexTokenProvider? = null,
) {
    suspend fun authenticate(
        builder: Request.Builder,
        providerSetting: ProviderSetting.OpenAI,
    ): Request.Builder {
        return when (providerSetting.authType) {
            OpenAIAuthType.API_KEY -> {
                val key = keyRoulette.next(
                    providerSetting.apiKey,
                    providerSetting.id.toString(),
                )
                builder.header(AUTHORIZATION_HEADER, "Bearer $key")
            }

            OpenAIAuthType.CHATGPT_SUBSCRIPTION -> {
                val requestHost = builder.build().url.host
                val codexHost = OPENAI_CODEX_BASE_URL.toHttpUrl().host
                require(requestHost == codexHost) {
                    "ChatGPT subscription authentication is only available for the official OpenAI Codex endpoint."
                }
                val credentials = codexTokenProvider?.getCredentials(providerSetting)
                    ?: providerSetting.codexCredentials
                    ?: error("OpenAI Codex is not signed in. Sign in with ChatGPT first.")
                require(credentials.accessToken.isNotBlank()) {
                    "OpenAI Codex access token is empty. Sign in with ChatGPT again."
                }
                require(credentials.accountId.isNotBlank()) {
                    "OpenAI Codex account is missing. Sign in with ChatGPT again."
                }
                builder
                    .header(AUTHORIZATION_HEADER, "Bearer ${credentials.accessToken}")
                    .header(CHATGPT_ACCOUNT_HEADER, credentials.accountId)
                    .header(ORIGINATOR_HEADER, ORIGINATOR_VALUE)
            }
        }
    }

    private companion object {
        const val AUTHORIZATION_HEADER = "Authorization"
        const val CHATGPT_ACCOUNT_HEADER = "ChatGPT-Account-Id"
        const val ORIGINATOR_HEADER = "originator"
        const val ORIGINATOR_VALUE = "rikkahub"
    }
}
