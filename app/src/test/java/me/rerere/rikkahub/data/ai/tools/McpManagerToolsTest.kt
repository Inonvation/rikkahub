package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.ai.mcp.serverUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class McpManagerToolsTest {
    private fun sseServer(
        name: String = "server",
        headers: List<Pair<String, String>> = emptyList(),
        tools: List<McpTool> = emptyList(),
        url: String = "https://example.com/sse",
    ) = McpServerConfig.SseTransportServer(
        id = Uuid.random(),
        commonOptions = McpCommonOptions(name = name, headers = headers, tools = tools),
        url = url,
    )

    private fun streamableServer(
        name: String = "server",
        headers: List<Pair<String, String>> = emptyList(),
        url: String = "https://example.com/mcp",
    ) = McpServerConfig.StreamableHTTPServer(
        id = Uuid.random(),
        commonOptions = McpCommonOptions(name = name, headers = headers),
        url = url,
    )

    @Test
    fun `mcpTransportOf maps sse and streamable_http`() {
        assertEquals("sse", mcpTransportOf(sseServer()))
        assertEquals("streamable_http", mcpTransportOf(streamableServer()))
    }

    @Test
    fun `mcpHeaderKeys joins header names`() {
        val server = sseServer(
            headers = listOf(
                "Authorization" to "Bearer token",
                "X-Custom" to "value",
            )
        )
        assertEquals("Authorization, X-Custom", mcpHeaderKeys(server))
    }

    @Test
    fun `mcpHeaderKeys returns empty string for no headers`() {
        assertEquals("", mcpHeaderKeys(sseServer()))
    }

    @Test
    fun `mcpParseHeaders parses json object to pairs`() {
        val json = buildJsonObject {
            put("Authorization", "Bearer token")
            put("X-Custom", "value")
        }
        assertEquals(
            listOf("Authorization" to "Bearer token", "X-Custom" to "value"),
            mcpParseHeaders(json),
        )
    }

    @Test
    fun `mcpParseHeaders returns empty list for null`() {
        assertEquals(emptyList<Pair<String, String>>(), mcpParseHeaders(null))
    }

    @Test
    fun `mcpParseHeaders skips non-string values`() {
        val json = buildJsonObject {
            put("X-Num", "123")
        }
        assertEquals(listOf("X-Num" to "123"), mcpParseHeaders(json))
    }

    @Test
    fun `mcpParseToolNames parses string array`() {
        val json = buildJsonArray {
            add("tool_a")
            add("tool_b")
        }
        assertEquals(listOf("tool_a", "tool_b"), mcpParseToolNames(json))
    }

    @Test
    fun `mcpParseToolNames returns null for non-array`() {
        assertNull(mcpParseToolNames(buildJsonObject { put("k", "v") }))
    }

    @Test
    fun `mcpParseToolNames returns null for null`() {
        assertNull(mcpParseToolNames(null))
    }

    @Test
    fun `mcpUpdateUrl returns same config when url is null`() {
        val server = sseServer()
        assert(server === mcpUpdateUrl(server, null))
    }

    @Test
    fun `mcpUpdateUrl updates url on sse server`() {
        val server = sseServer(url = "https://old.example.com/sse")
        val updated = mcpUpdateUrl(server, "https://new.example.com/sse")
        assertEquals("https://new.example.com/sse", updated.serverUrl)
        assertEquals(server.id, updated.id)
    }

    @Test
    fun `mcpUpdateUrl updates url on streamable_http server`() {
        val server = streamableServer(url = "https://old.example.com/mcp")
        val updated = mcpUpdateUrl(server, "https://new.example.com/mcp")
        assertEquals("https://new.example.com/mcp", updated.serverUrl)
        assertEquals(server.id, updated.id)
    }
}
