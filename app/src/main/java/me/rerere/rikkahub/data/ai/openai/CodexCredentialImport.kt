package me.rerere.rikkahub.data.ai.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/** 从导入文本中解析出的 Codex 订阅凭据。 */
data class CodexCredentialImport(
    val accessToken: String,
    val refreshToken: String?,
    val accountId: String,
    val email: String? = null,
    val planType: String? = null,
)

/**
 * 解析导入的凭据文本。
 *
 * 支持：
 * - 官方 Codex auth.json（`{"auth_mode":"chatgpt","tokens":{"access_token":…,"refresh_token":…,"account_id":…,"id_token":…}}`，
 *   如 codexauth.moshushi.xyz 等工具生成、或 `~/.codex/auth.json`）；
 * - 纯 token JSON（顶层 access_token / refresh_token / account_id，snake_case 或 camelCase）；
 * - id_token 存在时，自动提取 email 与 chatgpt_plan_type。
 */
fun parseCodexCredentialImport(text: String): CodexCredentialImport {
    val normalized = text.trim()
    require(normalized.isNotEmpty()) { "导入内容为空" }

    val root = runCatching {
        Json.parseToJsonElement(normalized).jsonObject
    }.getOrElse {
        throw IllegalArgumentException("无法解析 JSON，请粘贴完整的 auth.json 内容")
    }

    val tokens = root["tokens"]?.jsonObject ?: root
    fun str(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (tokens[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    val accessToken = str("access_token", "accessToken")
        ?: throw IllegalArgumentException("缺少 access_token，无法导入")
    val accountId = str("account_id", "accountId", "chatgpt_account_id")
        ?: throw IllegalArgumentException("缺少 account_id，无法导入（请确认这是 Codex auth.json）")
    val refreshToken = str("refresh_token", "refreshToken")
    val idToken = str("id_token", "idToken")

    val idClaims = idToken?.let(::decodeJwtPayload)
    val authClaims = idClaims?.get("https://api.openai.com/auth")?.jsonObject
    val email = str("email")
        ?: idClaims?.get("email")?.jsonPrimitive?.contentOrNull
    val planType = str("plan_type", "planType")
        ?: authClaims?.get("chatgpt_plan_type")?.jsonPrimitive?.contentOrNull

    return CodexCredentialImport(
        accessToken = accessToken,
        refreshToken = refreshToken,
        accountId = accountId,
        email = email,
        planType = planType,
    )
}

/** 解码 JWT 的 payload 段（base64url，容忍缺失 padding），失败返回 null。 */
internal fun decodeJwtPayload(token: String): JsonObject? {
    val payload = token.split('.').getOrNull(1) ?: return null
    return runCatching {
        val decoded = Base64.getUrlDecoder().decode(payload).decodeToString()
        Json.parseToJsonElement(decoded).jsonObject
    }.getOrNull()
}
