package me.rerere.rikkahub.data.repository

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆检索多查询提取（纯函数）：主查询（最新 USER）+ 副查询（最新 ASSISTANT 回答）。
 */
class MemoryRepositoryQueryTest {

    private fun message(role: MessageRole, text: String) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
    )

    @Test
    fun `primary is latest user text and secondary is latest assistant answer`() {
        val queries = MemoryRepository.extractMemoryQueries(
            listOf(
                message(MessageRole.USER, "old question"),
                message(MessageRole.ASSISTANT, "old answer"),
                message(MessageRole.USER, "what about tea brewing?"),
                message(MessageRole.ASSISTANT, "Tea brewing needs 80°C water"),
            )
        )
        assertEquals(listOf("what about tea brewing?", "Tea brewing needs 80°C water"), queries)
    }

    @Test
    fun `short user message falls back to last three user texts`() {
        val queries = MemoryRepository.extractMemoryQueries(
            listOf(
                message(MessageRole.USER, "I like oolong tea"),
                message(MessageRole.USER, "and green tea"),
                message(MessageRole.USER, "嗯？"),
                message(MessageRole.ASSISTANT, "answer text"),
            )
        )
        // 主查询 = 最近 3 条 USER 拼接；副查询 = 最新 ASSISTANT
        assertEquals(2, queries.size)
        assertTrue(queries[0].contains("I like oolong tea"))
        assertTrue(queries[0].contains("嗯？"))
        assertEquals("answer text", queries[1])
    }

    @Test
    fun `no user messages yields empty queries`() {
        val queries = MemoryRepository.extractMemoryQueries(
            listOf(message(MessageRole.ASSISTANT, "hello"))
        )
        assertTrue(queries.isEmpty())
    }

    @Test
    fun `secondary dropped when identical to primary`() {
        val queries = MemoryRepository.extractMemoryQueries(
            listOf(
                message(MessageRole.USER, "same text"),
                message(MessageRole.ASSISTANT, "same text"),
            )
        )
        assertEquals(listOf("same text"), queries)
    }

    @Test
    fun `queries capped at max chars`() {
        val long = "长".repeat(500)
        val queries = MemoryRepository.extractMemoryQueries(
            listOf(
                message(MessageRole.USER, long),
                message(MessageRole.ASSISTANT, long),
            )
        )
        assertEquals(2, queries.size)
        queries.forEach { assertTrue(it.length <= MemoryRepository.MEMORY_QUERY_MAX_CHARS) }
    }
}
