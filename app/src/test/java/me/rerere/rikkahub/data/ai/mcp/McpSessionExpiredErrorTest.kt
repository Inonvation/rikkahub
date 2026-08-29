package me.rerere.rikkahub.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSessionExpiredErrorTest {
    @Test
    fun `gateway session expired body is detected`() {
        // mcpmarket 等聚合网关的响应体：HTTP 层错误 + JSON body
        val cause = StreamableHttpError(
            code = 400,
            message = """Streamable HTTP error: {"RequestId":"3f2b0b9e","Code":"SessionExpired","Message":"session c822 is expired"}""",
        )
        val error = McpException(
            code = -1,
            message = "Error while sending message: " + cause.message,
            cause = cause,
        )
        assertTrue(isSessionExpiredError(error))
    }

    @Test
    fun `session not found message is detected`() {
        val error = McpException(
            code = -1,
            message = "Error while sending message: Streamable HTTP error: Session not found",
        )
        assertTrue(isSessionExpiredError(error))
    }

    @Test
    fun `http 404 is detected as session expiry`() {
        val error = McpException(
            code = -1,
            message = "Error while sending message",
            cause = StreamableHttpError(code = 404, message = "Not Found"),
        )
        assertTrue(isSessionExpiredError(error))
    }

    @Test
    fun `session without any lost keyword is not detected`() {
        val error = McpException(
            code = -1,
            message = "Error while sending message: Streamable HTTP error: Internal Server Error",
        )
        assertFalse(isSessionExpiredError(error))
    }

    @Test
    fun `unrelated tool and transport errors are not detected`() {
        assertFalse(isSessionExpiredError(IllegalStateException("Tool execution failed")))
        assertFalse(
            isSessionExpiredError(
                RuntimeException("Maximum reconnection attempts exceeded", java.io.IOException("timeout"))
            )
        )
        assertFalse(isSessionExpiredError(RuntimeException("Invalid tool arguments: missing query")))
    }

    @Test
    fun `keyword must co-occur with session in a single message`() {
        // "session" 与失效关键词必须出现在同一条消息里，避免跨 cause 误判
        val error = RuntimeException(
            "session context",
            RuntimeException("invalid arguments"),
        )
        assertFalse(isSessionExpiredError(error))
    }
}
