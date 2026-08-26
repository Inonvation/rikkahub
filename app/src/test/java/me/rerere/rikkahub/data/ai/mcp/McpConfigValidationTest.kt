package me.rerere.rikkahub.data.ai.mcp

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McpConfigValidationTest {

    private fun server(name: String = "my-server", url: String = "https://example.com/mcp") =
        McpServerConfig.StreamableHTTPServer(
            id = Uuid.random(),
            commonOptions = McpCommonOptions(name = name),
            url = url,
        )

    @Test
    fun `configError returns null for valid config`() {
        assertNull(server().configError())
    }

    @Test
    fun `configError rejects empty name`() {
        val error = server(name = "  ").configError()
        assertEquals("服务器名称不能为空", error)
    }

    @Test
    fun `configError rejects empty url`() {
        val error = server(url = "").configError()
        assertEquals("URL 不能为空", error)
    }

    @Test
    fun `configError rejects non-http scheme`() {
        val error = server(url = "ftp://example.com/mcp").configError()
        assertEquals("URL 协议必须是 http/https", error)
    }

    @Test
    fun `configError rejects url without host`() {
        val error = server(url = "https://").configError()
        assertEquals("URL 缺少主机名", error)
    }
}
