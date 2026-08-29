package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动记忆整理的纯逻辑契约：整理提示词组装、操作集宽容解析（白名单 id/别名/分类降级/
 * 条数封顶）、最近一轮文本提取。
 */
class MemoryConsolidationTest {

    // ---- buildMemoryConsolidationPrompt ----

    @Test
    fun `prompt carries existing memories turn texts and schema`() {
        val prompt = buildMemoryConsolidationPrompt(
            existing = listOf(
                AssistantMemory(id = 7, content = "User prefers brief replies.", category = MemoryCategory.PREFERENCE),
                AssistantMemory(id = 9, content = "User is a nurse."),
            ),
            userText = "I switched to night shifts",
            assistantText = "Got it, night shifts can be tough",
        )
        assertTrue(prompt.contains("\"id\": 7"))
        assertTrue(prompt.contains("User prefers brief replies."))
        assertTrue(prompt.contains("User is a nurse."))
        assertTrue(prompt.contains("User: I switched to night shifts"))
        assertTrue(prompt.contains("Assistant: Got it, night shifts can be tough"))
        assertTrue(prompt.contains("\"operations\""))
        assertTrue(prompt.contains("\"add\""))
        assertTrue(prompt.contains("\"update\""))
        assertTrue(prompt.contains("\"delete\""))
    }

    @Test
    fun `existing memories trimmed to newest entries`() {
        val memories = (1..150).map { i ->
            AssistantMemory(
                id = i,
                content = "fact-$i",
                createdAt = i * 100_000L,
                updatedAt = i * 100_000L,
            )
        }
        val prompt = buildMemoryConsolidationPrompt(memories, "u", "a")
        // 条数上限 100：新→旧裁剪，最新的 id=150 必在，最旧的 id=1 先被丢弃
        assertTrue(prompt.contains("\"fact-150\""))
        assertTrue(!prompt.contains("\"fact-1\""))
    }

    // ---- parseMemoryOperations ----

    private val validIds = setOf(1, 12, 7)

    @Test
    fun `parses plain object`() {
        val ops = parseMemoryOperations(
            """{"operations":[
                {"op":"add","category":"preference","content":"Likes tea"},
                {"op":"update","id":12,"content":"Prefers window seats"},
                {"op":"delete","id":7}
            ]}""",
            validIds,
        )
        assertEquals(3, ops.size)
        assertEquals(MemoryOperation.Add("Likes tea", MemoryCategory.PREFERENCE), ops[0])
        assertEquals(MemoryOperation.Update(12, "Prefers window seats", null), ops[1])
        assertEquals(MemoryOperation.Delete(7), ops[2])
    }

    @Test
    fun `parses fenced json with prose around it`() {
        val ops = parseMemoryOperations(
            "Here you go:\n```json\n{\"operations\":[{\"op\":\"add\",\"content\":\"User is vegetarian\"}]}\n```\nDone.",
            validIds,
        )
        assertEquals(listOf(MemoryOperation.Add("User is vegetarian", null)), ops)
    }

    @Test
    fun `accepts bare array`() {
        val ops = parseMemoryOperations("""[{"op":"add","content":"fact"}]""", validIds)
        assertEquals(listOf(MemoryOperation.Add("fact", null)), ops)
    }

    @Test
    fun `op verb aliases are tolerated`() {
        val ops = parseMemoryOperations(
            """{"operations":[
                {"op":"create","content":"a"},
                {"op":"edit","id":1,"content":"b"},
                {"op":"remove","id":7}
            ]}""",
            validIds,
        )
        assertEquals(3, ops.size)
        assertTrue(ops[0] is MemoryOperation.Add)
        assertTrue(ops[1] is MemoryOperation.Update)
        assertTrue(ops[2] is MemoryOperation.Delete)
    }

    @Test
    fun `update and delete with unknown id are rejected`() {
        // 白名单外 id（含幻觉 id）一律丢弃，防止误改误删
        val ops = parseMemoryOperations(
            """{"operations":[
                {"op":"update","id":999,"content":"x"},
                {"op":"delete","id":999},
                {"op":"update","id":1,"content":"ok"}
            ]}""",
            validIds,
        )
        assertEquals(listOf(MemoryOperation.Update(1, "ok", null)), ops)
    }

    @Test
    fun `add without content is skipped`() {
        val ops = parseMemoryOperations(
            """{"operations":[
                {"op":"add"},
                {"op":"add","content":"   "},
                {"op":"add","content":"real"}
            ]}""",
            validIds,
        )
        assertEquals(listOf(MemoryOperation.Add("real", null)), ops)
    }

    @Test
    fun `category tolerant degrade`() {
        val ops = parseMemoryOperations(
            """{"operations":[
                {"op":"add","category":" preference ","content":"a"},
                {"op":"add","category":"weird","content":"b"},
                {"op":"add","category":null,"content":"c"}
            ]}""",
            validIds,
        )
        assertEquals(MemoryCategory.PREFERENCE, (ops[0] as MemoryOperation.Add).category)
        assertEquals(MemoryCategory.OTHER, (ops[1] as MemoryOperation.Add).category)
        assertNull((ops[2] as MemoryOperation.Add).category)
    }

    @Test
    fun `operations capped at limit`() {
        val many = (1..8).joinToString(",") { """{"op":"add","content":"f$it"}""" }
        val ops = parseMemoryOperations("""{"operations":[$many]}""", validIds)
        assertEquals(MAX_MEMORY_OPS_PER_TURN, ops.size)
        assertEquals("f5", (ops.last() as MemoryOperation.Add).content)
    }

    @Test
    fun `garbage input yields empty list`() {
        assertEquals(emptyList<MemoryOperation>(), parseMemoryOperations("", validIds))
        assertEquals(emptyList<MemoryOperation>(), parseMemoryOperations("no json here", validIds))
        assertEquals(emptyList<MemoryOperation>(), parseMemoryOperations("{\"other\":1}", validIds))
    }

    // ---- latestTurnTexts ----

    private fun message(role: MessageRole, text: String) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
    )

    @Test
    fun `latest turn extracts trailing user assistant pair`() {
        val messages = listOf(
            message(MessageRole.USER, "old question"),
            message(MessageRole.ASSISTANT, "old answer"),
            message(MessageRole.USER, "new question"),
            message(MessageRole.ASSISTANT, "new answer"),
        )
        assertEquals("new question" to "new answer", latestTurnTexts(messages))
    }

    @Test
    fun `assistant before user is skipped`() {
        val messages = listOf(
            message(MessageRole.USER, "question"),
            message(MessageRole.ASSISTANT, "answer"),
        )
        // 助手回答不晚于用户消息的异常尾态 → 跳过
        assertNull(latestTurnTexts(messages.reversed()))
    }

    @Test
    fun `missing user or blank texts return null`() {
        assertNull(latestTurnTexts(listOf(message(MessageRole.ASSISTANT, "only assistant"))))
        assertNull(
            latestTurnTexts(
                listOf(
                    message(MessageRole.USER, "  "),
                    message(MessageRole.ASSISTANT, "answer"),
                )
            )
        )
        assertNull(
            latestTurnTexts(
                listOf(
                    message(MessageRole.USER, "question"),
                    message(MessageRole.ASSISTANT, ""),
                )
            )
        )
    }
}
