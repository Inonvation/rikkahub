package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 验证 [Conversation.updateCurrentMessages] 的引用复用优化：
 * - 内容未变化的消息 node 必须复用原引用（LazyColumn 依赖引用相等跳过重组，
 *   这是长对话后期流式输出不掉帧的关键）
 * - 内容/引用实际变化时仍走原更新逻辑，行为与优化前等价
 */
class ConversationUpdateTest {

    private fun userMsg(text: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun assistantMsg(text: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text(text)),
    )

    private fun conversationOf(user: UIMessage, assistant: UIMessage): Conversation =
        Conversation.ofId(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messages = listOf(
                MessageNode(messages = listOf(user)),
                MessageNode(messages = listOf(assistant)),
            ),
        )

    @Test
    fun `unmodified messages reuse same node references`() {
        val user = userMsg("hello")
        val assistant = assistantMsg("world")
        val conversation = conversationOf(user, assistant)

        // 模拟流式 chunk：全量消息，未变化消息引用相同
        val updated = conversation.updateCurrentMessages(listOf(user, assistant))

        assertEquals(2, updated.messageNodes.size)
        // 内容未变的 node 必须复用原引用，否则 LazyColumn 全部可见 item 会整列表重组
        assertSame(conversation.messageNodes[0], updated.messageNodes[0])
        assertSame(conversation.messageNodes[1], updated.messageNodes[1])
        assertSame(user, updated.messageNodes[0].currentMessage)
        assertSame(assistant, updated.messageNodes[1].currentMessage)
    }

    @Test
    fun `changed last message updates only that node`() {
        val user = userMsg("hello")
        val assistant = assistantMsg("world")
        val conversation = conversationOf(user, assistant)

        // 模拟流式：最后一条 assistant 消息文本追加（产生新引用）
        val assistant2 = assistantMsg("world more")
        val updated = conversation.updateCurrentMessages(listOf(user, assistant2))

        assertEquals(2, updated.messageNodes.size)
        // 用户消息 node 复用；最后一条 node 更新
        assertSame(conversation.messageNodes[0], updated.messageNodes[0])
        assertNotSame(conversation.messageNodes[1], updated.messageNodes[1])
        assertEquals("world more", updated.messageNodes[1].currentMessage.toText())
        assertSame(assistant2, updated.messageNodes[1].currentMessage)
    }

    @Test
    fun `new message at end is appended as new node`() {
        val user = userMsg("hello")
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messages = listOf(MessageNode(messages = listOf(user))),
        )

        val assistant = assistantMsg("world")
        val updated = conversation.updateCurrentMessages(listOf(user, assistant))

        assertEquals(2, updated.messageNodes.size)
        assertSame(conversation.messageNodes[0], updated.messageNodes[0])
        assertEquals("world", updated.messageNodes[1].currentMessage.toText())
    }

    @Test
    fun `equal content but different reference still updates node`() {
        val user = userMsg("hello")
        val assistant = assistantMsg("world")
        val conversation = conversationOf(user, assistant)

        // 内容 equals 但引用不同（如 visualTransform 复制过）：必须保守更新，不能误复用
        val assistantCopy = assistant.copy()
        val updated = conversation.updateCurrentMessages(listOf(user, assistantCopy))

        assertNotSame(conversation.messageNodes[1], updated.messageNodes[1])
        assertEquals(assistant, updated.messageNodes[1].currentMessage)
        assertSame(assistantCopy, updated.messageNodes[1].currentMessage)
    }

    @Test
    fun `new message id appends to existing node and selects it`() {
        val user = userMsg("hello")
        val assistant = assistantMsg("world")
        val conversation = conversationOf(user, assistant)

        // 新 id 的消息落在同位置 node：追加到 messages 并选中
        val regenerated = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("new answer")),
        )
        val updated = conversation.updateCurrentMessages(listOf(user, regenerated))

        assertEquals(2, updated.messageNodes.size)
        assertSame(conversation.messageNodes[0], updated.messageNodes[0])
        assertEquals(2, updated.messageNodes[1].messages.size)
        assertSame(regenerated, updated.messageNodes[1].currentMessage)
        assertEquals(1, updated.messageNodes[1].selectIndex)
    }

    @Test
    fun `branch selectIndex preserved when target message is not selected`() {
        val user = userMsg("hello")
        val branchA = assistantMsg("answer A")
        val branchB = assistantMsg("answer B")
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messages = listOf(
                MessageNode(messages = listOf(user)),
                MessageNode(messages = listOf(branchA, branchB), selectIndex = 1),
            ),
        )

        // 更新的是未选中分支 branchA（引用相同）：不满足快速路径，走正常更新但 selectIndex 保持
        val updated = conversation.updateCurrentMessages(listOf(user, branchA))

        assertNotSame(conversation.messageNodes[1], updated.messageNodes[1])
        assertEquals(1, updated.messageNodes[1].selectIndex)
        assertSame(branchB, updated.messageNodes[1].currentMessage)
    }

    @Test
    fun `multi round streaming keeps untouched nodes stable`() {
        val user = userMsg("hello")
        var assistant = assistantMsg("")
        val conversation = conversationOf(user, assistant)

        // 模拟连续流式 chunk：最后一条 assistant 文本逐轮增长，其余消息不变
        var updated: Conversation = conversation
        val texts = listOf("你", "你好", "你好世", "你好世界")
        for (text in texts) {
            assistant = assistant.copy(parts = listOf(UIMessagePart.Text(text)))
            updated = updated.updateCurrentMessages(listOf(user, assistant))

            // 每一轮：用户消息 node 引用都稳定复用（不发生重组）
            assertSame(conversation.messageNodes[0], updated.messageNodes[0])
            assertEquals(text, updated.messageNodes[1].currentMessage.toText())
        }
    }
}
