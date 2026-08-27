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