package me.rerere.rikkahub.ui.components.message

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进程级展开折叠缓存（sectionExpanded / toolBubbleExpanded）的生命周期治理测试。
 * 两个 map 是进程级单例，用例间共享，每个用例先清空再操作。
 */
class ExpandStateLifecycleTest {

    @After
    fun tearDown() {
        sectionExpanded.clear()
        toolBubbleExpanded.clear()
        recentConversationIds.clear()
    }

    // ---------- trackRecentConversation（进程级最近会话 + 生命周期治理） ----------

    @Test
    fun `track keeps previous conversations across separate page instances`() {
        // 模拟切换会话：每个 ChatPage 导航实例各调用一次（旧实例已被 cleanupChatPages 销毁），
        // 进程级队列跨实例累积，切走时其它会话的记忆不能被下一次访问清掉。
        sectionExpanded["process:c1:n1"] = true
        sectionExpanded["reasoning:c1:t1"] = false
        trackRecentConversation("c1", keepRecentCount = 8)

        // 切到 c2：c2 自己的记忆由访问它的实例正常建立
        sectionExpanded["chain:c2:n1"] = true
        assertEquals(0, trackRecentConversation("c2", keepRecentCount = 8))

        // 切到 c2 后 c1 的记忆仍在：切回 c1 时折叠态可恢复
        assertTrue(sectionExpanded.containsKey("process:c1:n1"))
        assertTrue(sectionExpanded.containsKey("reasoning:c1:t1"))
        assertTrue(sectionExpanded.containsKey("chain:c2:n1"))
    }

    @Test
    fun `track drops conversations beyond capacity by recency`() {
        sectionExpanded["process:c1:n1"] = true
        trackRecentConversation("c1", keepRecentCount = 8)
        repeat(8) { i ->
            val cid = "c${i + 2}"
            sectionExpanded["process:$cid:n1"] = true
            trackRecentConversation(cid, keepRecentCount = 8)
        }

        // 共访问 9 个会话（c1..c9），最早访问的 c1 被回收，其余保留
        assertNull(sectionExpanded["process:c1:n1"])
        assertTrue(sectionExpanded.containsKey("process:c9:n1"))
    }

    @Test
    fun `track re-visit refreshes recency instead of dropping`() {
        // c1 之后再访问 8 个会话、然后重新回到 c1：c1 变为最近访问，
        // 被淘汰的是此时最旧的 c2，而不是重新访问过的 c1
        listOf("c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9", "c1").forEach {
            sectionExpanded["process:$it:n1"] = true
            trackRecentConversation(it, keepRecentCount = 8)
        }

        assertNull(sectionExpanded["process:c2:n1"]) // 最旧的 c2 被回收
        assertTrue(sectionExpanded.containsKey("process:c1:n1")) // 重新访问的 c1 保留
        assertTrue(sectionExpanded.containsKey("process:c9:n1"))
    }

    // ---------- pruneSectionExpanded ----------

    @Test
    fun `prune keeps only listed conversations`() {
        sectionExpanded["chain:c1:n1"] = true
        sectionExpanded["chain:c1:n2"] = false
        sectionExpanded["reasoning:c2:created"] = true
        sectionExpanded["process:c3:n1"] = true
        sectionExpanded["todo:c2"] = false

        val removed = pruneSectionExpanded(setOf("c1", "c2"))

        assertEquals(1, removed) // 仅 c3 的一条
        assertTrue(sectionExpanded.containsKey("chain:c1:n1"))
        assertTrue(sectionExpanded.containsKey("chain:c1:n2"))
        assertTrue(sectionExpanded.containsKey("reasoning:c2:created"))
        assertTrue(sectionExpanded.containsKey("todo:c2"))
        assertNull(sectionExpanded["process:c3:n1"])
    }

    @Test
    fun `prune with empty keep set clears everything`() {
        sectionExpanded["chain:c1:n1"] = true
        sectionExpanded["todo:c2"] = false

        assertEquals(2, pruneSectionExpanded(emptySet()))
        assertTrue(sectionExpanded.isEmpty())
    }

    @Test
    fun `prune with all conversations keeps everything`() {
        sectionExpanded["chain:c1:n1"] = true
        sectionExpanded["reasoning:c2:created"] = true

        assertEquals(0, pruneSectionExpanded(setOf("c1", "c2")))
        assertEquals(2, sectionExpanded.size)
    }

    // ---------- trimToolBubbleExpanded ----------

    @Test
    fun `trim removes oldest entries beyond max`() {
        toolBubbleExpanded["call-1"] = true
        toolBubbleExpanded["call-2"] = false
        toolBubbleExpanded["call-3"] = true
        toolBubbleExpanded["call-4"] = true

        val removed = trimToolBubbleExpanded(maxEntries = 2)

        assertEquals(2, removed)
        assertNull(toolBubbleExpanded["call-1"]) // 最早插入的先被淘汰
        assertNull(toolBubbleExpanded["call-2"])
        assertEquals(true, toolBubbleExpanded["call-3"])
        assertEquals(true, toolBubbleExpanded["call-4"])
    }

    @Test
    fun `trim under limit is no-op`() {
        toolBubbleExpanded["call-1"] = true
        toolBubbleExpanded["call-2"] = false

        assertEquals(0, trimToolBubbleExpanded(maxEntries = 10))
        assertEquals(2, toolBubbleExpanded.size)
    }

    @Test
    fun `trim with zero max clears everything`() {
        toolBubbleExpanded["call-1"] = true
        toolBubbleExpanded["call-2"] = false

        assertEquals(2, trimToolBubbleExpanded(maxEntries = 0))
        assertTrue(toolBubbleExpanded.isEmpty())
    }
}