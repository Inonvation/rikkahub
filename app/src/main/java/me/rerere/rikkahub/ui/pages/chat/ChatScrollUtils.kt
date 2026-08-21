package me.rerere.rikkahub.ui.pages.chat

internal fun isChatListAtBottom(
    lastItemEnd: Int?,
    viewportEnd: Int,
    bottomInsetPx: Int,
    tolerancePx: Int = 8,
): Boolean {
    if (lastItemEnd == null) return false
    return lastItemEnd <= viewportEnd - bottomInsetPx - tolerancePx
}

internal fun isChatListPinnedToBottom(
    totalItemsCount: Int,
    lastVisibleIndex: Int?,
    lastItemEnd: Int?,
    viewportEnd: Int,
    afterContentPadding: Int,
    tolerancePx: Int = 8,
): Boolean {
    if (totalItemsCount <= 0 || lastVisibleIndex == null || lastItemEnd == null) return false
    if (lastVisibleIndex != totalItemsCount - 1) return false
    return (viewportEnd - lastItemEnd) >= (afterContentPadding - tolerancePx)
}
