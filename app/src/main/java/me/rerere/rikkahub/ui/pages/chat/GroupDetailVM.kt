package me.rerere.rikkahub.ui.pages.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Group
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

/**
 * 群组详情页 ViewModel：群组信息 + 会话历史 + 新建对话。
 * `id` 为 groupId。
 */
class GroupDetailVM(
    id: String,
    private val chatService: ChatService,
    private val conversationRepo: ConversationRepository,
) : ViewModel() {
    private val _groupId: Uuid = Uuid.parse(id)

    val group: StateFlow<Group?> = chatService.getGroupFlow(_groupId)

    val conversations: StateFlow<List<Conversation>> = conversationRepo
        .getConversationsOfGroup(_groupId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 新建一场空对话，返回会话 id（供导航进讨论页） */
    fun createNewConversation(onCreated: (Uuid) -> Unit) {
        viewModelScope.launch {
            val convId = chatService.createGroupConversation(_groupId)
            onCreated(convId)
        }
    }

    /** 删除单场对话（先停生成，清内存态，再级联清理） */
    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            runCatching { chatService.stopGeneration(conversation.id) }
            chatService.clearDiscussionState(conversation.id)
            conversationRepo.deleteConversation(conversation)
        }
    }
}
