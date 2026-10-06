package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ContextCheckpointTest {

    private fun msg(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun summary(text: String) = msg(text).copy(isContextCheckpoint = true)

    private fun conversationOf(messages: List<UIMessage>): Conversation =
        Conversation.ofId(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messages = messages.map { MessageNode(messages = listOf(it)) },
        )

    @Test
    fun effectiveMessagesReturnsFullHistoryWithoutCheckpoint() {
        val messages = listOf(msg("old1"), msg("old2"))
        assertEquals(messages, conversationOf(messages).effectiveMessages())
    }

    @Test
    fun effectiveMessagesStartsAtTheLastCheckpoint() {
        val old1 = msg("old1")
        val old2 = msg("old2")
        val summary = summary("[Summary]")
        val newMessage = msg("new")
        val conversation = conversationOf(listOf(old1, old2, summary, newMessage))

        // 检查点之前的消息只留给用户查看，不再发送给模型
        assertEquals(listOf(summary, newMessage), conversation.effectiveMessages())
    }

    @Test
    fun effectiveMessagesUsesTheNewestCheckpointOfSeveral() {
        val firstSummary = summary("[Summary 1]")
        val secondSummary = summary("[Summary 2]")
        val tail = msg("tail")
        val conversation = conversationOf(
            listOf(msg("old1"), firstSummary, msg("middle"), secondSummary, tail),
        )

        assertEquals(listOf(secondSummary, tail), conversation.effectiveMessages())
    }

    @Test
    fun checkpointNodeIsKeptEvenWhenItIsTheOnlyMessage() {
        val conversation = conversationOf(listOf(msg("old1"), summary("[Summary]")))

        // 压缩后尚未继续对话：请求上下文就是这份摘要，不能退化成空
        assertEquals(listOf("[Summary]"), conversation.effectiveMessages().map { it.toText() })
    }
}
