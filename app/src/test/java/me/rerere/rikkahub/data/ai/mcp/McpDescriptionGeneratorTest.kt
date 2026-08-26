package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpDescriptionGeneratorTest {

    private val tools = listOf(
        McpTool(name = "search_issues", description = "Search GitHub issues"),
        McpTool(name = "get_issue", description = "Get a single issue by id"),
        McpTool(name = "no_desc_tool", description = null),
    )

    @Test
    fun `user prompt lists server name and tools with descriptions`() {
        val prompt = buildMcpDescriptionUserPrompt("GitHub", tools)
        assertTrue(prompt.contains("MCP server name: GitHub"))
        assertTrue(prompt.contains("- search_issues: Search GitHub issues"))
        assertTrue(prompt.contains("- get_issue: Get a single issue by id"))
        // 无描述的工具只列名字
        assertTrue(prompt.contains("- no_desc_tool"))
        assertFalse(prompt.contains("no_desc_tool:"))
    }

    @Test
    fun `user prompt falls back to unnamed and empty tools`() {
        val prompt = buildMcpDescriptionUserPrompt("", emptyList())
        assertTrue(prompt.contains("MCP server name: (unnamed)"))
        assertTrue(prompt.contains("(none)"))
    }

    @Test
    fun `clean collapses whitespace and strips quotes and punctuation`() {
        val cleaned = cleanMcpDescription("  \"GitHub  搜索、Issue 与 PR 管理。\"  ")
        assertEquals("GitHub 搜索、Issue 与 PR 管理", cleaned)
    }

    @Test
    fun `clean caps to max length`() {
        val long = "x".repeat(MCP_DESCRIPTION_MAX_LENGTH + 50)
        assertEquals(MCP_DESCRIPTION_MAX_LENGTH, cleanMcpDescription(long).length)
    }

    @Test
    fun `clean returns blank for whitespace only`() {
        assertTrue(cleanMcpDescription("   \n\t ").isBlank())
    }

    @Test
    fun `system prompt mentions rules and max length`() {
        val prompt = buildMcpDescriptionSystemPrompt()
        assertTrue(prompt.contains("$MCP_DESCRIPTION_MAX_LENGTH"))
        assertTrue(prompt.contains("based ONLY on the listed tools"))
    }
}
