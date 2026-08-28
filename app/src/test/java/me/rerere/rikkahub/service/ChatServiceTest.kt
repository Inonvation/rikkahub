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
}
