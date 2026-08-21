package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatScrollUtilsTest {
    @Test
    fun `empty layout is neither at bottom nor pinned`() {
        assertFalse(isChatListAtBottom(lastItemEnd = null, viewportEnd = 1000, bottomInsetPx = 100))
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
    fun `terminal item fully visible inside input inset is at bottom`() {
        assertTrue(
            isChatListAtBottom(
                lastItemEnd = 900,
                viewportEnd = 1000,
                bottomInsetPx = 90,
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
}
