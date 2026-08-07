package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollUtilsTest {
    @Test
    fun `empty layout is not at bottom`() {
        assertFalse(isRealListBottom(0, null, null, 1000))
    }

    @Test
    fun `middle item fully visible is not at bottom`() {
        assertFalse(
            isRealListBottom(
                totalItemsCount = 10,
                lastVisibleItemIndex = 4,
                lastVisibleItemEndOffset = 980,
                viewportEndOffset = 1000,
                tolerancePx = 3,
            )
        )
    }

    @Test
    fun `terminal item fully visible is at bottom`() {
        assertTrue(
            isRealListBottom(
                totalItemsCount = 10,
                lastVisibleItemIndex = 9,
                lastVisibleItemEndOffset = 1000,
                viewportEndOffset = 1000,
            )
        )
    }

    @Test
    fun `terminal item within rounding tolerance is at bottom`() {
        assertTrue(
            isRealListBottom(
                totalItemsCount = 10,
                lastVisibleItemIndex = 9,
                lastVisibleItemEndOffset = 1002,
                viewportEndOffset = 1000,
                tolerancePx = 2,
            )
        )
    }

    @Test
    fun `partially clipped terminal item is not at bottom`() {
        assertFalse(
            isRealListBottom(
                totalItemsCount = 10,
                lastVisibleItemIndex = 9,
                lastVisibleItemEndOffset = 1040,
                viewportEndOffset = 1000,
                tolerancePx = 2,
            )
        )
    }

    @Test
    fun `short list terminal item above viewport end is at bottom`() {
        assertTrue(
            isRealListBottom(
                totalItemsCount = 2,
                lastVisibleItemIndex = 1,
                lastVisibleItemEndOffset = 500,
                viewportEndOffset = 1000,
            )
        )
    }

    @Test
    fun `long last message without terminal spacer is not at bottom`() {
        assertFalse(
            isRealListBottom(
                totalItemsCount = 12,
                lastVisibleItemIndex = 10,
                lastVisibleItemEndOffset = 2600,
                viewportEndOffset = 1000,
            )
        )
    }

    @Test
    fun `loading indicator cannot replace terminal spacer`() {
        assertFalse(
            isRealListBottom(
                totalItemsCount = 12,
                lastVisibleItemIndex = 10,
                lastVisibleItemEndOffset = 900,
                viewportEndOffset = 1000,
            )
        )
        assertTrue(
            isRealListBottom(
                totalItemsCount = 12,
                lastVisibleItemIndex = 11,
                lastVisibleItemEndOffset = 950,
                viewportEndOffset = 1000,
            )
        )
    }

    @Test
    fun `old terminal index is invalid after item count changes`() {
        assertFalse(
            isRealListBottom(
                totalItemsCount = 11,
                lastVisibleItemIndex = 11,
                lastVisibleItemEndOffset = 950,
                viewportEndOffset = 1000,
            )
        )
    }
}
