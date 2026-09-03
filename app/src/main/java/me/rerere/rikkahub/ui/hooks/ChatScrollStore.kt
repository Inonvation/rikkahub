package me.rerere.rikkahub.ui.hooks

import kotlin.uuid.Uuid

/**
 * 会话级消息列表滚动位置缓存（纯内存，生命周期 = 进程存活）。
 *
 * 背景：切换会话时走导航栈（navigateToChatPage 压入新 Chat 页，随后
 * cleanupChatPages 清掉旧页），旧 ChatVM/LazyListState 随之销毁；重新进入
 * 该会话时统一定位到"最后一条消息开头"。此 store 在页面存活期间持续保存
 * 滚动位置，重新进入该会话时恢复，满足"会话切换短暂记忆"（与 ChatDraftStore
 * 同款内存作用域，进程被杀即失效，不持久化到磁盘）。
 *
 * 位置描述 = item index + offset + 锚点消息 id（离开时视口首条真实消息）：
 * - index/offset 是恢复的兜底（无锚点 / 锚点已被删除时用）；
 * - 锚点消息用于会话离开期间列表头部发生增删（上下文压缩、远端同步等）时，
 *   仍能把视口钉回"用户离开时正在看的那条消息"，而不是漂到同序号的另一条。
 * 生命周期：与展开折叠记忆同口径，由 ChatPage 治理入口调用 [prune] 只保留
 * 最近 N 个会话（见 trackRecentConversation / recentConversationIds）。
 */
class ChatScrollStore {
    private val positions = mutableMapOf<Uuid, ChatScrollPosition>()

    fun save(conversationId: Uuid, index: Int, offset: Int, anchorMessageId: Uuid? = null) {
        positions[conversationId] = ChatScrollPosition(
            firstVisibleItemIndex = index.coerceAtLeast(0),
            firstVisibleItemScrollOffset = offset.coerceAtLeast(0),
            anchorMessageId = anchorMessageId,
        )
    }

    fun load(conversationId: Uuid): ChatScrollPosition? = positions[conversationId]

    fun remove(conversationId: Uuid) {
        positions.remove(conversationId)
    }

    /**
     * 生命周期治理：仅保留 [keep] 中的会话记录，删除其余会话的滚动存档，返回删除条数。
     * 调用方必须把当前会话放进 [keep]，否则正在显示的页面存档被清后，下次进入会落回
     * "最后一条消息开头"默认位（与 section 折叠记忆的 prune 同风险，见 pruneSectionExpanded）。
     */
    fun prune(keep: Set<Uuid>): Int {
        val before = positions.size
        positions.keys.retainAll(keep)
        return before - positions.size
    }
}

data class ChatScrollPosition(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
    /** 离开时视口首条真实消息的 MessageNode id；视口内无消息（空列表等）为 null */
    val anchorMessageId: Uuid? = null,
)
