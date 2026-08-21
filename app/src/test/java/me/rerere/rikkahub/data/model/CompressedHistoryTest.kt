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
            listOf(summary, old2, newMessage),
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

        assertEquals(listOf(summary), conversation.effectiveMessages())
    }
}
