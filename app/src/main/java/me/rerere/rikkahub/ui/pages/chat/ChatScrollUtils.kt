package me.rerere.rikkahub.ui.pages.chat

/**
 * reverseLayout 聊天列表的"真实底部"判定：底部固定项（ScrollBottom spacer）在
 * reverseLayout 中是 index 0，位于视口最底。只有"最底可见项是 index 0 且其底边
 * 完整进入视口"才算真正在底部，避免中部间距、加载项、超长末消息冒充底部。
 */
internal fun isRealListBottom(
    firstVisibleItemIndex: Int?,
    firstVisibleItemEndOffset: Int?,
    viewportEndOffset: Int,
    tolerancePx: Int = 0,
): Boolean {
    if (firstVisibleItemIndex == null || firstVisibleItemEndOffset == null) {
        return false
    }
    return firstVisibleItemIndex == 0 &&
        firstVisibleItemEndOffset <= viewportEndOffset + tolerancePx
}

/**
 * 消息在 reverseLayout LazyColumn 中的 item index：底部固定项（spacer + loading/systemPrompt）
 * 占据 bottomSlots 个槽位，消息按时间倒序从下往上排，因此 messageNodes[messageIndex]
 * （0 = 最早）映射到 item index = bottomSlots + (messageNodesSize - 1 - messageIndex)。
 */
internal fun messageItemIndex(
    messageNodesSize: Int,
    messageIndex: Int,
    bottomSlots: Int,
): Int = bottomSlots + (messageNodesSize - 1 - messageIndex)
