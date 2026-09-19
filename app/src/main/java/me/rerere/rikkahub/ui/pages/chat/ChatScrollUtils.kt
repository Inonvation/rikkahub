package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid

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

/**
 * 恢复目标：LazyColumn item index + 相对首可见 item 的 scrollOffset。
 */
internal data class ChatScrollRestoreTarget(
    val itemIndex: Int,
    val scrollOffset: Int,
)

/**
 * 把会话离开时的滚动存档解析成当前列表上的恢复落点。
 *
 * 存档约定（与 ChatList 上报对齐）：anchorMessageId 始终对应 firstVisibleItemIndex
 * 那一条 item 的真实消息（首可见 item 不是消息时为 null），因此 offset 与 anchor/index
 * 三者描述同一个视口锚点，恢复时不得把 offset 套到另一 item 上。
 *
 * 解析优先级：
 * 1. 锚点消息仍在列表 → 按其**当前**消息下标换算 item（会话离开期间头部增删后仍钉回
 *    原消息），offset 原样沿用；
 * 2. 锚点失效/无锚点，但存档 index 仍在当前 item 区间 → index+offset 直用（item 空间）；
 * 3. 都不可用 → [fallbackItemIndex]（调用方通常传"最后一条消息开头"）。
 */
internal fun resolveChatScrollRestoreTarget(
    savedIndex: Int,
    savedOffset: Int,
    anchorMessageId: Uuid?,
    messageNodes: List<MessageNode>,
    presetCount: Int,
    hasPresetIntroItem: Boolean,
    fallbackItemIndex: Int,
): ChatScrollRestoreTarget {
    val offset = savedOffset.coerceAtLeast(0)
    if (anchorMessageId != null) {
        val messageIndex = messageNodes.indexOfFirst { it.id == anchorMessageId }
        if (messageIndex in presetCount until messageNodes.size) {
            return ChatScrollRestoreTarget(
                itemIndex = chatMessageItemIndex(messageIndex, presetCount, hasPresetIntroItem),
                scrollOffset = offset,
            )
        }
    }
    val maxIndex = chatMessageItemIndex(
        messageIndex = messageNodes.lastIndex.coerceAtLeast(0),
        presetCount = presetCount,
        hasPresetIntroItem = hasPresetIntroItem,
    ).coerceAtLeast(0)
    return if (savedIndex in 0..maxIndex) {
        ChatScrollRestoreTarget(itemIndex = savedIndex, scrollOffset = offset)
    } else {
        ChatScrollRestoreTarget(itemIndex = fallbackItemIndex.coerceAtLeast(0), scrollOffset = 0)
    }
}

/**
 * 由 [chatMessageItemIndex] 的逆运算 + preset 区间约束，判断某 LazyColumn item 是否对应
 * 会话全列表中的真实消息；是则返回消息下标，否则 null（intro/摘要/系统提示/底部占位等）。
 */
internal fun chatItemMessageIndexOrNull(
    itemIndex: Int,
    messageCount: Int,
    presetCount: Int,
    hasPresetIntroItem: Boolean,
): Int? {
    val introOffset = if (hasPresetIntroItem) 1 else 0
    if (itemIndex < introOffset) return null
    val messageIndex = presetCount + (itemIndex - introOffset)
    return messageIndex.takeIf { it in presetCount until messageCount }
}
