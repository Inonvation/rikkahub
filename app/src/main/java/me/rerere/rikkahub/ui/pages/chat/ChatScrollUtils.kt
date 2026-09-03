package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.MessageNode

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
 * 会话消息列表中与 assistant 预设开场逐条一致（单消息节点且消息 id 相同）的前缀节点数。
 * ChatList 据此把预设开场收进 PresetMessagesIntro item、真实消息从其后排列；ChatPage
 * 恢复滚动位置时用同一函数换算 item 索引。换算只此一份，避免两处口径分叉——
 * 预设会话"列表 item index ≠ 消息下标"（intro item 占一位），恢复若按消息下标直用
 * 会偏一位（见 docs/chat-session-view-state-plan.md 4.2）。
 */
internal fun matchPresetMessageCount(
    messageNodes: List<MessageNode>,
    presetMessages: List<UIMessage>,
): Int = presetMessages.indices.takeWhile { index ->
    messageNodes.getOrNull(index)?.let { node ->
        node.messages.size == 1 && node.messages.firstOrNull()?.id == presetMessages[index].id
    } == true
}.size

/**
 * 真实消息（会话全列表下标 [messageIndex]）→ LazyColumn item index。
 * [hasPresetIntroItem] 存在时真实消息整体后移一位；仅当 [messageIndex] >= [presetCount]
 * （即确为真实消息、排在预设开场之后）时有意义，调用方须保证不越界。
 */
internal fun chatMessageItemIndex(
    messageIndex: Int,
    presetCount: Int,
    hasPresetIntroItem: Boolean,
): Int = messageIndex - presetCount + if (hasPresetIntroItem) 1 else 0
