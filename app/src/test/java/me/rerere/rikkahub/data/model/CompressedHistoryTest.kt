package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class CompressedHistoryTest {

    private fun msg(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun conversationOf(messages: List<UIMessage>): Conversation =
        Conversation.ofId(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messages = messages.map { MessageNode(messages = listOf(it)) },
        )

    @Test
    fun effectiveMessagesReturnsFullHistoryWithoutSnapshot() {
        val messages = listOf(msg("old1"), msg("old2"))
        assertEquals(messages, conversationOf(messages).effectiveMessages())
    }

    @Test
    fun effectiveMessagesUsesSnapshotAndAppendsNewMessages() {
        val old1 = msg("old1")
        val old2 = msg("old2")
        val summary = msg("[Summary]")
        val newMessage = msg("new")
        val conversation = conversationOf(listOf(old1, old2, newMessage)).copy(
            compressedHistory = CompressedHistory(
                messages = listOf(summary, old2),
                lastOriginalMessageId = old2.id,
                summaryText = "[Summary]",
            )
        )

        assertEquals(
            // 摘要消息（id 不在 currentMessages 中）被 effectiveMessages 标记为合成，
            // 供 displayMessagesForChunk 区分「请求上下文快照」与「steering 注入的真实用户消息」
            listOf(summary.copy(isSynthetic = true), old2, newMessage),
            conversation.effectiveMessages(),
        )
    }

    @Test
    fun effectiveMessagesFallsBackToSnapshotWhenMarkerMissing() {
        val summary = msg("[Summary]")
        val conversation = conversationOf(listOf(msg("old1"), msg("old2"))).copy(
            compressedHistory = CompressedHistory(
                messages = listOf(summary),
                lastOriginalMessageId = Uuid.random(),
                summaryText = "[Summary]",
            )
        )

        assertEquals(listOf(summary.copy(isSynthetic = true)), conversation.effectiveMessages())
    }
}
