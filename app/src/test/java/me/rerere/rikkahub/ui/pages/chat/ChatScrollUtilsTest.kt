package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatScrollUtilsTest {
    @Test
    fun `empty layout is not pinned`() {
        assertFalse(
            isChatListPinnedToBottom(
                totalItemsCount = 0,
                lastVisibleIndex = null,
                lastItemEnd = null,
                viewportEnd = 1000,
                afterContentPadding = 50,
            )
        )
    }

    @Test
    fun `bottom content padding dead zone is not pinned`() {
        assertFalse(
            isChatListPinnedToBottom(
                totalItemsCount = 10,
                lastVisibleIndex = 9,
                lastItemEnd = 960,
                viewportEnd = 1000,
                afterContentPadding = 50,
            )
        )
    }

    @Test
    fun `terminal item within padding tolerance is pinned`() {
        assertTrue(
            isChatListPinnedToBottom(
                totalItemsCount = 10,
                lastVisibleIndex = 9,
                lastItemEnd = 950,
                viewportEnd = 1000,
                afterContentPadding = 50,
            )
        )
    }

    @Test
    fun `old terminal index is invalid after item count changes`() {
        assertFalse(
            isChatListPinnedToBottom(
                totalItemsCount = 9,
                lastVisibleIndex = 9,
                lastItemEnd = 950,
                viewportEnd = 1000,
                afterContentPadding = 50,
            )
        )
    }

    @Test
    fun `partially clipped terminal item is not pinned`() {
        assertFalse(
            isChatListPinnedToBottom(
                totalItemsCount = 10,
                lastVisibleIndex = 9,
                lastItemEnd = 970,
                viewportEnd = 1000,
                afterContentPadding = 50,
            )
        )
    }

    // ---------- matchPresetMessageCount / chatMessageItemIndex（preset intro item 占位换算） ----------

    private fun presetMsg(id: Uuid) = UIMessage(
        id = id,
        role = MessageRole.ASSISTANT,
        parts = listOf(UIMessagePart.Text("preset")),
    )

    private fun singleMsgNode(id: Uuid) = MessageNode.of(presetMsg(id))

    @Test
    fun `preset count matches fully aligned prefix`() {
        val p1 = presetMsg(Uuid.random())
        val p2 = presetMsg(Uuid.random())
        val p3 = presetMsg(Uuid.random())
        val nodes = listOf(singleMsgNode(p1.id), singleMsgNode(p2.id), singleMsgNode(p3.id))

        assertEquals(3, matchPresetMessageCount(nodes, listOf(p1, p2, p3)))
    }

    @Test
    fun `preset count stops at first id mismatch`() {
        val p1 = presetMsg(Uuid.random())
        val p2 = presetMsg(Uuid.random())
        val nodes = listOf(singleMsgNode(p1.id), singleMsgNode(Uuid.random()))

        assertEquals(1, matchPresetMessageCount(nodes, listOf(p1, p2)))
    }

    @Test
    fun `preset count ignores message buried in multi-message node`() {
        // node.messages.size == 1 是匹配前提：分支节点(含多条消息)即使混有同 id 消息也不算 preset 开场
        val p1 = presetMsg(Uuid.random())
        val p2 = presetMsg(Uuid.random())
        val branchNode = MessageNode(messages = listOf(presetMsg(p2.id), presetMsg(Uuid.random())))
        val nodes = listOf(singleMsgNode(p1.id), branchNode)

        assertEquals(1, matchPresetMessageCount(nodes, listOf(p1, p2)))
    }

    @Test
    fun `preset count stops when nodes run out`() {
        val p1 = presetMsg(Uuid.random())
        val p2 = presetMsg(Uuid.random())
        val nodes = listOf(singleMsgNode(p1.id))

        assertEquals(1, matchPresetMessageCount(nodes, listOf(p1, p2)))
    }

    @Test
    fun `preset count with empty sides`() {
        val p1 = presetMsg(Uuid.random())
        assertEquals(0, matchPresetMessageCount(emptyList(), listOf(p1)))
        assertEquals(0, matchPresetMessageCount(listOf(singleMsgNode(p1.id)), emptyList()))
        assertEquals(0, matchPresetMessageCount(emptyList(), emptyList()))
    }

    @Test
    fun `first node mismatch yields zero`() {
        val p1 = presetMsg(Uuid.random())
        assertEquals(0, matchPresetMessageCount(listOf(singleMsgNode(Uuid.random())), listOf(p1)))
    }

    @Test
    fun `item index shifts by preset count and intro item`() {
        // 消息全列表下标 3（前 2 条是 preset 开场）→ 合并进 intro item 后 item = 3-2+1 = 2
        assertEquals(2, chatMessageItemIndex(messageIndex = 3, presetCount = 2, hasPresetIntroItem = true))
        // 无 intro item（preset 消息各自独立成 item）→ item = 3-2 = 1
        assertEquals(1, chatMessageItemIndex(messageIndex = 3, presetCount = 2, hasPresetIntroItem = false))
        // preset 开场后的第一条真实消息：落在 intro item 之后的首个真实 item
        assertEquals(1, chatMessageItemIndex(messageIndex = 2, presetCount = 2, hasPresetIntroItem = true))
        // 无 preset 会话（messageIndex == presetCount == 0）的首条消息：无 intro = item 0，有 intro = item 1
        assertEquals(0, chatMessageItemIndex(messageIndex = 0, presetCount = 0, hasPresetIntroItem = false))
        assertEquals(1, chatMessageItemIndex(messageIndex = 0, presetCount = 0, hasPresetIntroItem = true))
    }

    // ---------- chatItemMessageIndexOrNull / resolveChatScrollRestoreTarget ----------

    private fun msgNode() = singleMsgNode(Uuid.random())

    @Test
    fun `item mapping skips intro item and trailing placeholders`() {
        // intro item（index 0）不是消息
        assertNull(chatItemMessageIndexOrNull(itemIndex = 0, messageCount = 3, presetCount = 0, hasPresetIntroItem = true))
        // intro 之后第一条真实消息
        assertEquals(0, chatItemMessageIndexOrNull(itemIndex = 1, messageCount = 3, presetCount = 0, hasPresetIntroItem = true))
        // 无 intro 时 index 0 即消息
        assertEquals(0, chatItemMessageIndexOrNull(itemIndex = 0, messageCount = 3, presetCount = 0, hasPresetIntroItem = false))
        // 越过消息区（摘要/系统提示/底部占位）
        assertNull(chatItemMessageIndexOrNull(itemIndex = 3, messageCount = 3, presetCount = 0, hasPresetIntroItem = false))
        // 无 intro 但仍有 preset 前缀：item 0 = 首条真实消息（preset 已 drop，不占 item）
        assertEquals(2, chatItemMessageIndexOrNull(itemIndex = 0, messageCount = 4, presetCount = 2, hasPresetIntroItem = false))
        // intro 之后 item 1 = 全列表下标 presetCount（真实消息区起点）
        assertEquals(2, chatItemMessageIndexOrNull(itemIndex = 1, messageCount = 4, presetCount = 2, hasPresetIntroItem = true))
    }

    @Test
    fun `restore prefers anchor over stale index`() {
        val nodes = List(10) { msgNode() }
        val anchor = nodes[4].id
        val target = resolveChatScrollRestoreTarget(
            savedIndex = 1,
            savedOffset = 30,
            anchorMessageId = anchor,
            messageNodes = nodes,
            presetCount = 0,
            hasPresetIntroItem = false,
            fallbackItemIndex = 9,
        )
        // 存档 index 过期（头部曾增删），锚点仍在 → 落到锚点当前下标 + 原 offset
        assertEquals(4, target.itemIndex)
        assertEquals(30, target.scrollOffset)
    }

    @Test
    fun `restore maps anchor through preset intro offset`() {
        val preset = List(2) { msgNode() }
        val real = List(5) { msgNode() }
        val nodes = preset + real
        val target = resolveChatScrollRestoreTarget(
            savedIndex = 0,
            savedOffset = 0,
            anchorMessageId = real[0].id,
            messageNodes = nodes,
            presetCount = 2,
            hasPresetIntroItem = true,
            fallbackItemIndex = 6,
        )
        // real[0] 全列表下标 2 → intro 占一位后 item = 2-2+1 = 1
        assertEquals(1, target.itemIndex)
        assertEquals(0, target.scrollOffset)
    }

    @Test
    fun `restore falls back to index when anchor deleted`() {
        val nodes = List(6) { msgNode() }
        val deletedAnchor = Uuid.random()
        val target = resolveChatScrollRestoreTarget(
            savedIndex = 3,
            savedOffset = 12,
            anchorMessageId = deletedAnchor,
            messageNodes = nodes,
            presetCount = 0,
            hasPresetIntroItem = false,
            fallbackItemIndex = 5,
        )
        assertEquals(3, target.itemIndex)
        assertEquals(12, target.scrollOffset)
    }

    @Test
    fun `restore clamps out-of-range index to fallback`() {
        val nodes = List(4) { msgNode() }
        val target = resolveChatScrollRestoreTarget(
            savedIndex = 99,
            savedOffset = 80,
            anchorMessageId = null,
            messageNodes = nodes,
            presetCount = 0,
            hasPresetIntroItem = false,
            fallbackItemIndex = 3,
        )
        assertEquals(3, target.itemIndex)
        assertEquals(0, target.scrollOffset)
    }

    @Test
    fun `restore uses fallback when no saved and empty-safe last index`() {
        val nodes = List(3) { msgNode() }
        val target = resolveChatScrollRestoreTarget(
            savedIndex = 2,
            savedOffset = 0,
            anchorMessageId = null,
            messageNodes = nodes,
            presetCount = 0,
            hasPresetIntroItem = false,
            fallbackItemIndex = 2,
        )
        assertEquals(2, target.itemIndex)
        assertEquals(0, target.scrollOffset)
    }

    @Test
    fun `restore coerces negative offset to zero`() {
        val nodes = List(5) { msgNode() }
        val target = resolveChatScrollRestoreTarget(
            savedIndex = 2,
            savedOffset = -40,
            anchorMessageId = null,
            messageNodes = nodes,
            presetCount = 0,
            hasPresetIntroItem = false,
            fallbackItemIndex = 4,
        )
        assertEquals(2, target.itemIndex)
        assertEquals(0, target.scrollOffset)
    }
}
