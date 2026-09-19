package me.rerere.rikkahub.data.repository

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆检索查询提取（纯函数）：主查询 = 最新 USER，短消息回退最近 3 条 USER。
 *
 * 降敏（2026-09）：副查询（最新 ASSISTANT 回答）已移除——它会形成自我强化回路，
 * 让模型上一轮展开过的话题持续把相关记忆拉回上下文。用户消息才是唯一信号源。
 */
class MemoryRepositoryQueryTest {

    private fun message(role: MessageRole, text: String) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
    )

    @Test
    fun `primary is latest user text and assistant answer is ignored`() {
        val queries = MemoryRepository.extractMemoryQueries(
            listOf(
                message(MessageRole.USER, "old question"),
                message(MessageRole.ASSISTANT, "old answer"),
                message(MessageRole.USER, "what about tea brewing?"),
                message(MessageRole.ASSISTANT, "Tea brewing needs 80°C water"),
            )
        )
        assertEquals(listOf("what about tea brewing?"), queries)
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
        // 单查询 = 最近 3 条 USER 拼接；ASSISTANT 文本不参与
        assertEquals(1, queries.size)
        assertTrue(queries[0].contains("I like oolong tea"))
        assertTrue(queries[0].contains("嗯？"))
    }

    @Test
    fun `no user messages yields empty queries`() {
        val queries = MemoryRepository.extractMemoryQueries(
            listOf(message(MessageRole.ASSISTANT, "hello"))
        )
        assertTrue(queries.isEmpty())
    }

    @Test
    fun `query capped at max chars`() {
        val long = "长".repeat(500)
        val queries = MemoryRepository.extractMemoryQueries(
            listOf(message(MessageRole.USER, long))
        )
        assertEquals(1, queries.size)
        queries.forEach { assertTrue(it.length <= MemoryRepository.MEMORY_QUERY_MAX_CHARS) }
    }

    @Test
    fun `full injection threshold stays at two`() {
        // 降敏回归锚点：阈值调大意味着"少量记忆每轮全量出现"的旧行为回归
        assertEquals(2, MemoryRepository.MEMORY_FULL_INJECTION_THRESHOLD)
    }
}
