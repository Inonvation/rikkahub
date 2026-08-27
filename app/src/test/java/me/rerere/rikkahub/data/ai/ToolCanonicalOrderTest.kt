package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 守护 [canonicalToolOrder]：provider 前缀缓存把 tools 数组顺序视为请求前缀的一部分，
 * 排序必须只由工具集合决定（族枚举序 + 名字典序），对装配路径的书写顺序免疫。
 */
class ToolCanonicalOrderTest {

    private fun tool(name: String): Tool = Tool(
        name = name,
        description = "test $name",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = { emptyList() },
    )

    @Test
    fun `sorted by family ordinal then name regardless of input order`() {
        val shuffled = listOf(
            tool("workspace_read"),    // WORKSPACE
            tool("mcp__github__search"), // MCP
            tool("todo_write"),        // TODO
            tool("get_time"),          // LOCAL
            tool("search_web"),        // SEARCH
            tool("clipboard_read"),    // LOCAL
        )
        val expected = listOf(
            "mcp__github__search", // MCP(0)
            "search_web",          // SEARCH(2)
            "workspace_read",      // WORKSPACE(3)
            "todo_write",          // TODO(9)
            "clipboard_read",      // LOCAL(15)，同族按名字典序
            "get_time",
        )
        // 多组不同输入排列都必须得到同一输出
        assertEquals(expected, shuffled.canonicalToolOrder().map { it.name })
        assertEquals(expected, shuffled.asReversed().canonicalToolOrder().map { it.name })
    }

    @Test
    fun `same family keeps lexicographic order`() {
        val tools = listOf(tool("eval_b"), tool("eval_a"), tool("eval_c"))
        assertEquals(listOf("eval_a", "eval_b", "eval_c"), tools.canonicalToolOrder().map { it.name })
    }

    @Test
    fun `order is stable and idempotent`() {
        val tools = listOf(tool("kb_search"), tool("memory_tool"), tool("ask_user"))
        val once = tools.canonicalToolOrder()
        assertEquals(once.map { it.name }, once.canonicalToolOrder().map { it.name })
        assertEquals(once.map { it.name }, tools.shuffled().canonicalToolOrder().map { it.name })
    }
}
