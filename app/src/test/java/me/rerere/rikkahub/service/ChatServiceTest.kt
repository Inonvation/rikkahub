package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.CompressedHistory
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatServiceTest {
    @Test
    fun `fork conversation inherits folder and workspace context`() {
        val source = Conversation(
            assistantId = Uuid.random(),
            title = "Source conversation",
            messageNodes = emptyList(),
            workspaceCwd = "/workspace/project",
            folderId = Uuid.random(),
        )

        val fork = createForkConversation(source, emptyList())

        assertNotEquals(source.id, fork.id)
        assertEquals(source.assistantId, fork.assistantId)
        assertEquals(source.workspaceCwd, fork.workspaceCwd)
        assertEquals(source.folderId, fork.folderId)
        assertEquals("", fork.title)
        assertFalse(fork.isPinned)
    }

    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val params = backgroundTextGenerationParams(model)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.AUTO, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
    }

    @Test
    fun `display messages skip compressed summary and append new assistant reply`() {
        val oldUser = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("old")),
        )
        val summary = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            isSynthetic = true,
            parts = listOf(UIMessagePart.Text("[Summary]")),
        )
        val newUser = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("new")),
        )
        val assistantReply = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("answer")),
        )

        val result = displayMessagesForChunk(
            displayMessages = listOf(oldUser, newUser),
            chunkMessages = listOf(summary, oldUser, newUser, assistantReply),
        )

        assertEquals(listOf(oldUser, newUser, assistantReply), result)
    }

    @Test
    fun `display messages append real user guidance injected at step boundary`() {
        val assistantReply = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("working...")),
        )
        // steering 在轮边界注入的真实用户引导（非合成）必须进入显示列表
        val guidance = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("聚焦在性能问题上继续")),
        )

        val result = displayMessagesForChunk(
            displayMessages = listOf(assistantReply),
            chunkMessages = listOf(assistantReply, guidance),
        )

        assertEquals(listOf(assistantReply, guidance), result)
    }

    @Test
    fun `display messages update existing message by id`() {
        val id = Uuid.random()
        val before = UIMessage(
            id = id,
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("partial")),
        )
        val after = before.copy(parts = listOf(UIMessagePart.Text("partial done")))

        val result = displayMessagesForChunk(
            displayMessages = listOf(before),
            chunkMessages = listOf(after),
        )

        assertEquals(listOf(after), result)
    }

    @Test
    fun `display messages duplicate id updates first occurrence and keeps order`() {
        val id = Uuid.random()
        val first = UIMessage(id = id, role = MessageRole.USER, parts = listOf(UIMessagePart.Text("first")))
        val second = UIMessage(id = id, role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("second")))
        val updated = first.copy(parts = listOf(UIMessagePart.Text("first updated")))

        val result = displayMessagesForChunk(
            displayMessages = listOf(first, second),
            chunkMessages = listOf(updated),
        )

        // 与 indexOfFirst 语义一致：只替换首个匹配，且不移动位置、不追加
        assertEquals(listOf(updated, second), result)
    }

    @Test
    fun `effective messages mark compressed summaries synthetic keep originals untouched`() {
        val kept = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("kept original")),
        )
        val summary = UIMessage(
            id = Uuid.random(),
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("compressed summary")),
        )
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(kept.toMessageNode()),
            compressedHistory = CompressedHistory(
                messages = listOf(summary, kept),
                lastOriginalMessageId = kept.id,
            ),
        )

        val result = conversation.effectiveMessages()

        // 摘要（id 不在 currentMessages 中）标合成 → displayMessagesForChunk 不追加进显示列表；
        // 保留的原始消息不受影响
        assertTrue(result[0].isSynthetic)
        assertFalse(result[1].isSynthetic)
    }

    @Test
    fun `parse compact command extracts optional instruction`() {
        assertEquals("", parseCompactCommand("/compact"))
        assertEquals("", parseCompactCommand("  /compact  "))
        assertEquals("Focus on API changes", parseCompactCommand("/compact Focus on API changes"))
        assertEquals("多行说明\n第二行", parseCompactCommand("/compact 多行说明\n第二行"))

        // 非 /compact 一律拒绝：前缀撞车、其他命令、正文提及、空输入
        assertNull(parseCompactCommand("/compactfoo"))
        assertNull(parseCompactCommand("/clear"))
        assertNull(parseCompactCommand("先看看 /compact 再说"))
        assertNull(parseCompactCommand(null))
    }

    @Test
    fun `split compress scope keeps recent tail and noop on short conversation`() {
        val messages = (0 until 25).map {
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("m$it")))
        }

        val (toCompress, toKeep) = splitCompressScope(messages, keepRecentMessages = 10)!!
        assertEquals(15, toCompress.size)
        assertEquals(messages.dropLast(10), toCompress)
        assertEquals(messages.takeLast(10), toKeep)

        // 会话比保留窗口还短 → null（无需压缩）
        assertNull(splitCompressScope(messages.take(5), keepRecentMessages = 10))

        // keep=0 → 全部压缩、不保留
        val (all, none) = splitCompressScope(messages, keepRecentMessages = 0)!!
        assertEquals(messages, all)
        assertTrue(none.isEmpty())
    }

    @Test
    fun `split by char budget preserves order and isolates oversized item`() {
        val items = listOf("a".repeat(30), "b".repeat(30), "c".repeat(30))
        // 30+30=60 不超过预算 60：[a,b] + [c] 两块
        val chunks = splitByCharBudget(items, budget = 60)
        assertEquals(2, chunks.size)
        assertEquals(listOf(items[0], items[1]), chunks[0])
        assertEquals(listOf(items[2]), chunks[1])

        // 预算 50 装不下两条 30：逐条成块
        assertEquals(3, splitByCharBudget(items, budget = 50).size)

        // 单条超预算：独占一块，不与其余合并
        val oversized = listOf("x".repeat(100), "y".repeat(5))
        val oversizedChunks = splitByCharBudget(oversized, budget = 10)
        assertEquals(listOf(oversized[0]), oversizedChunks[0])
        assertEquals(listOf(oversized[1]), oversizedChunks[1])

        assertTrue(splitByCharBudget(emptyList(), budget = 100).isEmpty())
    }
}
