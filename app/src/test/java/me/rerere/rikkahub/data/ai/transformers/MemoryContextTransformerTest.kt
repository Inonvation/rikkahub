package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryContextTransformerTest {

    private fun message(role: MessageRole, text: String) = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
    )

    @Test
    fun `empty block leaves messages untouched`() {
        val messages = listOf(
            message(MessageRole.SYSTEM, "system"),
            message(MessageRole.USER, "hello"),
        )
        assertEquals(messages, appendMemoryContext(messages, ""))
    }

    @Test
    fun `block appended to last user message`() {
        val messages = listOf(
            message(MessageRole.SYSTEM, "system"),
            message(MessageRole.USER, "old question"),
            message(MessageRole.ASSISTANT, "answer"),
            message(MessageRole.USER, "new question"),
        )
        val result = appendMemoryContext(messages, "<memories>[]</memories>")
        // 最后一条 USER 消息尾部追加；历史消息不动（缓存前缀稳定）
        assertTrue(result[3].parts.firstOrNull() is UIMessagePart.Text)
        val text = (result[3].parts.first() as UIMessagePart.Text).text
        assertTrue(text.startsWith("new question"))
        assertTrue(text.contains("<memories>[]</memories>"))
        assertEquals("old question", (result[1].parts.first() as UIMessagePart.Text).text)
        assertEquals("system", (result[0].parts.first() as UIMessagePart.Text).text)
    }

    @Test
    fun `no trailing user message skips injection`() {
        val messages = listOf(
            message(MessageRole.SYSTEM, "system"),
            message(MessageRole.USER, "question"),
            message(MessageRole.ASSISTANT, "partial answer"),
        )
        val result = appendMemoryContext(messages, "<memories>[]</memories>")
        assertEquals(messages, result)
    }

    @Test
    fun `non text parts preserved when appending`() {
        val imageMessage = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Image(""),
                UIMessagePart.Text("describe this"),
            ),
        )
        val messages = listOf(imageMessage)
        val result = appendMemoryContext(messages, "block")
        assertEquals(2, result[0].parts.size)
        assertEquals("describe this\n\nblock", (result[0].parts[1] as UIMessagePart.Text).text)
    }
}
