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

/**
 * reverseLayout 列表中底部固定项（ScrollBottom spacer + 可选的系统 prompt）占据的槽位数。
 */
internal fun chatBottomSlots(showSystemPrompt: Boolean): Int = if (showSystemPrompt) 2 else 1

/**
 * 消息在 reverseLayout LazyColumn 中的 item index：底部固定项占据 [bottomSlots] 个槽位，
 * 消息按时间倒序从下往上排，因此 messageNodes[messageIndex]（0 = 最早）映射到
 * item index = bottomSlots + (messageNodesSize - 1 - messageIndex)。
 */
internal fun messageItemIndex(
    messageNodesSize: Int,
    messageIndex: Int,
    bottomSlots: Int,
): Int = bottomSlots + (messageNodesSize - 1 - messageIndex)
