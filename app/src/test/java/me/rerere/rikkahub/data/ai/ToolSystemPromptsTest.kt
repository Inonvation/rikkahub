package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolSystemPromptsTest {

    private val model = Model(modelId = "test-model")

    private fun tool(name: String, prompt: String): Tool = Tool(
        name = name,
        description = "desc",
        parameters = { null },
        systemPrompt = { _, _ -> prompt },
        execute = { emptyList() },
    )

    @Test
    fun `identical systemPrompts are injected once`() {
        val prompts = buildToolSystemPrompts(
            tools = listOf(
                tool("use_skill", "**Skills** block"),
                tool("skill_admin_list", "**Skills** block"),
                tool("skill_admin_set_enabled", "**Skills** block"),
                tool("todo_write", "Todo guidance"),
                tool("blank", ""),
            ),
            model = model,
            messages = emptyList(),
        )
        assertEquals(listOf("**Skills** block", "Todo guidance"), prompts)
    }

    @Test
    fun `blank prompts are dropped`() {
        val prompts = buildToolSystemPrompts(
            tools = listOf(tool("a", ""), tool("b", "   ")),
            model = model,
            messages = emptyList(),
        )
        assertEquals(emptyList<String>(), prompts)
    }

    @Test
    fun `distinct prompts are preserved`() {
        val prompts = buildToolSystemPrompts(
            tools = listOf(tool("a", "one"), tool("b", "two")),
            model = model,
            messages = emptyList(),
        )
        assertEquals(listOf("one", "two"), prompts)
    }
}
