package me.rerere.ai.provider.providers.openai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CodexSseContentTypeInterceptorTest {
    private fun responseFor(url: String, body: String, contentType: String? = null): Response {
        val request = Request.Builder().url(url).build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody(contentType?.toMediaType()))
            .build()
    }

    @Test
    fun `adds event stream content type to Codex response without content type`() {
        val request = Request.Builder()
            .url("https://chatgpt.com/backend-api/codex/responses")
            .build()
        val response = responseFor(
            url = "https://chatgpt.com/backend-api/codex/responses",
            body = "data: {\"type\":\"response.completed\"}\n\n",
        )

        val wrapped = response.withCodexSseContentType(request)

        assertEquals("text/event-stream", wrapped.body?.contentType()?.toString())
    }

    @Test
    fun `keeps existing content type untouched on Codex endpoint`() {
        val request = Request.Builder()
            .url("https://chatgpt.com/backend-api/codex/responses")
            .build()
        val response = responseFor(
            url = "https://chatgpt.com/backend-api/codex/responses",
            body = "{}",
            contentType = "application/json",
        )

        val result = response.withCodexSseContentType(request)

        assertSame(response, result)
        assertEquals("application/json; charset=utf-8", result.body?.contentType()?.toString())
    }

    @Test
    fun `leaves non Codex responses alone even without content type`() {
        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .build()
        val response = responseFor(
            url = "https://api.openai.com/v1/responses",
            body = "data: {}\n\n",
        )

        val result = response.withCodexSseContentType(request)

        assertSame(response, result)
        assertEquals(null, result.body?.contentType())
    }
}
