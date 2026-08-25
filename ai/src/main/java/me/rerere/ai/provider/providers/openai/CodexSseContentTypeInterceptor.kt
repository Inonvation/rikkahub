package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.OPENAI_CODEX_BASE_URL
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource

/**
 * 兼容 Codex 后端流式响应缺失 `Content-Type: text/event-stream` 的情况。
 *
 * OkHttp 自带 [okhttp3.sse.EventSources] 会在解析 SSE 前校验 Content-Type，
 * 而官方 Codex 后端的流式响应有时不携带该头。本拦截器仅在「官方 Codex 端点 +
 * 响应体缺少 Content-Type」时，把响应体包装为 event-stream 类型；其余请求一律原样放行，
 * 不影响其他 Provider 的严格校验。包装只覆盖 contentType，不缓冲流，保持 SSE 流式语义。
 */
class CodexSseContentTypeInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return chain.proceed(request).withCodexSseContentType(request)
    }
}

/** 供拦截器使用；抽成纯函数便于单元测试。 */
internal fun Response.withCodexSseContentType(request: Request): Response {
    if (!request.url.isCodexBackendUrl()) return this
    val body = body ?: return this
    if (body.contentType() != null) return this
    return newBuilder()
        .body(ContentTypeOverrideBody(delegate = body, contentType = EVENT_STREAM_MEDIA_TYPE))
        .build()
}

private fun okhttp3.HttpUrl.isCodexBackendUrl(): Boolean {
    val codexHost = OPENAI_CODEX_BASE_URL.toHttpUrl().host
    return host.equals(codexHost, ignoreCase = true) &&
        encodedPath.startsWith("/backend-api/codex")
}

private class ContentTypeOverrideBody(
    private val delegate: ResponseBody,
    private val contentType: MediaType,
) : ResponseBody() {
    override fun contentType(): MediaType = contentType
    override fun contentLength(): Long = delegate.contentLength()
    override fun source(): BufferedSource = delegate.source()
}

private val EVENT_STREAM_MEDIA_TYPE = "text/event-stream".toMediaType()
