package me.rerere.rikkahub.utils

import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * 将 Throwable / 错误消息翻译成通俗中文原因，方便普通用户理解。
 *
 * 设计原则：翻译是「增强」——匹配不到任何规则时原样返回原始消息，
 * 绝不丢掉有用的具体信息。因此本工具可以安全地用于替换裸 error.message 的展示点。
 *
 * 匹配顺序：明确异常类型（含 cause 链）→ 错误消息关键词 → HTTP 状态码 → 兜底原文。
 */
data class ExplainedError(
    val reason: String,
    val suggestion: String?,
    val rawMessage: String,
)

private data class Rule(
    val keywords: List<String>,
    val reason: String,
    val suggestion: String?,
)

/** 关键词规则：body 里的错误码通常比通用 HTTP 码更具体，故优先匹配 */
private val KEYWORD_RULES = listOf(
    Rule(
        keywords = listOf(
            "context_length_exceeded",
            "context length exceeded",
            "maximum context length",
            "context window is too small",
            "prompt is too long",
            "tokens exceeds the maximum",
        ),
        reason = "消息内容超出模型上下文长度限制",
        suggestion = "精简消息内容，或开新会话后再试",
    ),
    Rule(
        keywords = listOf(
            "model_not_found",
            "model not found",
            "no such model",
            "model does not exist",
            "the model does not exist",
        ),
        reason = "模型不存在或没有使用权限",
        suggestion = "检查模型 ID 是否正确，以及该模型是否对你的 API Key 开放",
    ),
    Rule(
        keywords = listOf(
            "invalid_api_key",
            "api key invalid",
            "incorrect api key",
            "invalid key",
            "authentication failed",
            "unauthorized",
        ),
        reason = "API Key 无效或已过期",
        suggestion = "到设置里核对 API Key 是否正确、是否过期",
    ),
    Rule(
        keywords = listOf(
            "insufficient_quota",
            "insufficient balance",
            "insufficient credits",
            "out of credits",
            "no balance",
            "billing not enabled",
            "payment required",
        ),
        reason = "余额不足",
        suggestion = "给对应的服务商充值后重试",
    ),
    Rule(
        keywords = listOf(
            "rate_limit_exceeded",
            "rate limit exceeded",
            "rate limit",
            "too many requests",
            "slow down",
            "overloaded",
        ),
        reason = "请求过于频繁，触发限流",
        suggestion = "稍等片刻再试，或降低并发请求数",
    ),
    Rule(
        keywords = listOf(
            "file does not exist",
            "no such file",
            "file not found",
        ),
        reason = "找不到相关文件或文件已被删除",
        suggestion = "确认文件路径存在",
    ),
    Rule(
        keywords = listOf(
            "unexpected character",
            "json parse",
            "invalid json",
            "failed to parse",
            "malformed",
        ),
        reason = "服务返回的数据格式异常",
        suggestion = "可能是服务端异常或接口返回不兼容，稍后重试",
    ),
)

/** HTTP 状态码规则 */
private val HTTP_CODE_RULES = mapOf(
    400 to Rule(emptyList(), "请求参数有误", "消息格式或内容可能超出限制，精简后重试"),
    401 to Rule(emptyList(), "API Key 无效或已过期", "到设置里核对 API Key 是否正确、是否过期"),
    402 to Rule(emptyList(), "余额不足", "给对应的服务商充值后重试"),
    403 to Rule(emptyList(), "没有权限访问该资源", "检查 API Key 权限或账号是否被禁用"),
    404 to Rule(emptyList(), "接口或模型不存在", "检查模型 ID 和接口地址是否正确"),
    429 to Rule(emptyList(), "请求过于频繁，触发限流", "稍等片刻再试，或降低并发请求数"),
    500 to Rule(emptyList(), "服务端异常，请稍后重试", null),
    502 to Rule(emptyList(), "服务端网关异常，请稍后重试", null),
    503 to Rule(emptyList(), "服务端暂时不可用，请稍后重试", null),
    504 to Rule(emptyList(), "服务端响应超时，请稍后重试", null),
)

/** 优先匹配带前缀的 HTTP 码，如 "Failed to get response: 404" / "HTTP 429" / "status 403" */
private val HTTP_CODE_PREFIX_REGEX = Regex("""(?i)(?:response|status|http)[^\d]{0,8}(\d{3})""")

/** 兜底：从消息里取第一个形如 3 位状态码的数字 */
private val HTTP_CODE_ANY_REGEX = Regex("""\b([1-5]\d{2})\b""")

/**
 * 把异常翻译成通俗原因。
 * 网络/文件类异常可能藏在 cause 链里（例如外层包装了一层 "Failed to get response"），
 * 因此遍历整个 cause 链查找明确类型的异常。
 */
fun Throwable.toExplainedError(): ExplainedError {
    val raw = message ?: "Unknown error"
    var cursor: Throwable? = this
    while (cursor != null) {
        when (cursor) {
            is UnknownHostException -> return ExplainedError(
                reason = "网络连接失败，请检查网络或接口地址是否正确",
                suggestion = "检查 Wi-Fi/流量，或确认 API 接口地址没写错",
                rawMessage = raw,
            )

            is ConnectException -> return ExplainedError(
                reason = "连接服务器失败，请检查网络或服务器地址",
                suggestion = "检查网络连接，确认服务器地址和端口是否正确",
                rawMessage = raw,
            )

            is SocketTimeoutException -> return ExplainedError(
                reason = "连接超时，请稍后重试",
                suggestion = "检查网络，或降低并发后重试",
                rawMessage = raw,
            )

            is SSLHandshakeException -> return ExplainedError(
                reason = "SSL 证书验证失败",
                suggestion = "通常是服务器证书异常或系统时间不准，可校准时间后重试",
                rawMessage = raw,
            )

            is FileNotFoundException -> return ExplainedError(
                reason = "找不到文件或文件已被删除",
                suggestion = "确认文件路径存在",
                rawMessage = raw,
            )
        }
        cursor = cursor.cause
    }
    return explainMessage(raw, raw)
}

/** 轻量版：只返回通俗描述，供 toast 等单行场景使用。无匹配返回原文。 */
fun explainErrorText(message: String?): String =
    if (message.isNullOrBlank()) "未知错误"
    else explainMessage(message, message).reason

private fun explainMessage(message: String, raw: String): ExplainedError {
    val lower = message.lowercase()

    // 1. 关键词优先（body 里的错误码，比通用 HTTP 码更具体）
    KEYWORD_RULES.forEach { rule ->
        if (rule.keywords.any { lower.contains(it) }) {
            return ExplainedError(rule.reason, rule.suggestion, raw)
        }
    }

    // 2. HTTP 状态码
    val code = HTTP_CODE_PREFIX_REGEX.find(lower)?.groupValues?.get(1)?.toIntOrNull()
        ?: HTTP_CODE_ANY_REGEX.find(lower)?.groupValues?.get(1)?.toIntOrNull()
    if (code != null) {
        HTTP_CODE_RULES[code]?.let { rule ->
            return ExplainedError(rule.reason, rule.suggestion, raw)
        }
        // 未收录的其他状态码
        return ExplainedError("请求失败（HTTP $code）", "请稍后重试，若持续出现请检查服务配置", raw)
    }

    // 3. 兜底：返回原文
    return ExplainedError(raw, null, raw)
}
