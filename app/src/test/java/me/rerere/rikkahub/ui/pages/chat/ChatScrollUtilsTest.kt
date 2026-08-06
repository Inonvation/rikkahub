package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollUtilsTest {
    @Test
    fun `empty layout is not at bottom`() {
        assertFalse(isRealListBottom(null, null, 1000))
    }

    @Test
    fun `scrolled up with non-zero first visible is not at bottom`() {
        assertFalse(
            isRealListBottom(
                firstVisibleItemIndex = 4,
                firstVisibleItemEndOffset = 980,
                viewportEndOffset = 1000,
                tolerancePx = 3,
            )
        )
    }

    @Test
    fun `bottom spacer fully visible at bottom edge is at bottom`() {
        assertTrue(
            isRealListBottom(
                firstVisibleItemIndex = 0,
                firstVisibleItemEndOffset = 1000,
                viewportEndOffset = 1000,
            )
        )
    }

    @Test
    fun `bottom spacer within rounding tolerance is at bottom`() {
        assertTrue(
            isRealListBottom(
                firstVisibleItemIndex = 0,
                firstVisibleItemEndOffset = 1002,
                viewportEndOffset = 1000,
                tolerancePx = 2,
            )
        )
    }

    @Test
    fun `bottom spacer clipped beyond tolerance is not at bottom`() {
        assertFalse(
            isRealListBottom(
                firstVisibleItemIndex = 0,
                firstVisibleItemEndOffset = 1040,
                viewportEndOffset = 1000,
                tolerancePx = 2,
            )
        )
    }

    @Test
    fun `short list anchored at bottom is at bottom`() {
        assertTrue(
            isRealListBottom(
                firstVisibleItemIndex = 0,
                firstVisibleItemEndOffset = 500,
                viewportEndOffset = 1000,
            )
        )
    }

    @Test
    fun `bottom item index must be zero`() {
        assertFalse(
            isRealListBottom(
                firstVisibleItemIndex = 1,
                firstVisibleItemEndOffset = 990,
                viewportEndOffset = 1000,
            )
        )
    }

    @Test
    fun `message index maps to reversed item index`() {
        // 5 条消息 + bottomSlots=2（spacer + loading）
        assertEquals(2, messageItemIndex(messageNodesSize = 5, messageIndex = 4, bottomSlots = 2))
        assertEquals(6, messageItemIndex(messageNodesSize = 5, messageIndex = 0, bottomSlots = 2))
        // bottomSlots=1（spacer 或 loading 二选一时）
        assertEquals(1, messageItemIndex(messageNodesSize = 5, messageIndex = 4, bottomSlots = 1))
        assertEquals(5, messageItemIndex(messageNodesSize = 5, messageIndex = 0, bottomSlots = 1))
    }
}
