package me.rerere.rikkahub.ui.pages.chat

internal data class ChatScrollLayoutSnapshot(
    val totalItemsCount: Int,
    val lastVisibleItemIndex: Int?,
    val lastVisibleItemEndOffset: Int?,
    val viewportEndOffset: Int,
) {
    fun isAtBottom(tolerancePx: Int = 0): Boolean = isRealListBottom(
        totalItemsCount = totalItemsCount,
        lastVisibleItemIndex = lastVisibleItemIndex,
        lastVisibleItemEndOffset = lastVisibleItemEndOffset,
        viewportEndOffset = viewportEndOffset,
        tolerancePx = tolerancePx,
    )
}

internal fun isRealListBottom(
    totalItemsCount: Int,
    lastVisibleItemIndex: Int?,
    lastVisibleItemEndOffset: Int?,
    viewportEndOffset: Int,
    tolerancePx: Int = 0,
): Boolean {
    if (totalItemsCount <= 0 || lastVisibleItemIndex == null || lastVisibleItemEndOffset == null) {
        return false
    }
    return lastVisibleItemIndex == totalItemsCount - 1 &&
        lastVisibleItemEndOffset <= viewportEndOffset + tolerancePx
}
