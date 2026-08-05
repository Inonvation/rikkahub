package me.rerere.rikkahub.ui.hooks

import kotlin.uuid.Uuid

/**
 * 会话级输入草稿缓存（纯内存，生命周期 = 进程存活）。
 *
 * 背景：切换会话/助手时，旧会话的 ChatVM 会被 `cleanupChatPages` 清出导航栈并销毁，
 * 输入框草稿（ChatInputState）随 VM 一并丢失。此 store 在 VM 销毁前保存草稿文本，
 * 重新进入该会话时由新 ChatVM 恢复，满足"至少会话期间保留"。
 * 发送成功后输入框被清空，保存/恢复自然失效；无需持久化到磁盘。
 */
class ChatDraftStore {
    private val drafts = mutableMapOf<Uuid, String>()

    /** 保存草稿；文本为空白时视为无草稿并移除，避免残留空 key */
    fun save(conversationId: Uuid, text: String) {
        if (text.isBlank()) {
            drafts.remove(conversationId)
        } else {
            drafts[conversationId] = text
        }
    }

    fun load(conversationId: Uuid): String? = drafts[conversationId]

    fun remove(conversationId: Uuid) {
        drafts.remove(conversationId)
    }
}
