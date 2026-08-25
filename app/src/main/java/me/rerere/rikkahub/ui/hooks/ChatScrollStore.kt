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
 */
class ChatScrollStore {
    private val positions = mutableMapOf<Uuid, ChatScrollPosition>()

    fun save(conversationId: Uuid, index: Int, offset: Int) {
        positions[conversationId] = ChatScrollPosition(
            firstVisibleItemIndex = index.coerceAtLeast(0),
            firstVisibleItemScrollOffset = offset.coerceAtLeast(0),
        )
    }

    fun load(conversationId: Uuid): ChatScrollPosition? = positions[conversationId]

    fun remove(conversationId: Uuid) {
        positions.remove(conversationId)
    }
}

data class ChatScrollPosition(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
)
