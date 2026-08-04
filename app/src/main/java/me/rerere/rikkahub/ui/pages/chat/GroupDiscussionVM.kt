package me.rerere.rikkahub.ui.pages.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.data.ai.discussion.DiscussionState
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Group
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

/**
 * 独立群组讨论页 ViewModel。
 * 订阅会话/讨论状态/生成任务，暴露控制方法（发送、暂停/继续、指定下一位）。
 * 群组配置从 Group 流读取（conversation.discussion 已废弃）。
 */
class GroupDiscussionVM(
    id: String,
    private val chatService: ChatService,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)

    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    val discussionState: StateFlow<DiscussionState> = chatService.getDiscussionStateFlow(_conversationId)
    val generationJob = chatService.getGenerationJobStateFlow(_conversationId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 所属群组（配置来源）。会话尚未初始化 groupId 时为 null */
    val group: StateFlow<Group?> = conversation
        // 关键修复：conversation 在流式生成期间每个 chunk 更新一次，若直接 flatMapLatest，
        // 每个 chunk 都会重启 getGroupFlow（其 stateIn 初始值 null），group 在 null↔Group 间
        // 高频跳变，顶栏标题在「群名↔会话标题」、头像区在「空↔全量」间闪烁。
        // 按 groupId 去重后，流式期间 groupId 恒定 → 内层流只订阅一次。
        .distinctUntilChangedBy { it.groupId }
        .flatMapLatest { conv ->
            conv.groupId?.let { gid -> chatService.getGroupFlow(gid) } ?: flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        chatService.addConversationReference(_conversationId)
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId)
        }
    }

    override fun onCleared() {
        super.onCleared()
        chatService.removeConversationReference(_conversationId)
    }

    /** 发送主题/插话（走 sendMessage 讨论分支：开题启动，插话续跑）。支持文本+附件 parts。
     *  @param nextSpeakerId 指定下一位发言人（"@成员名"流程：设 hint，runDiscussion 时该成员先发言） */
    fun send(parts: List<UIMessagePart>, nextSpeakerId: Uuid? = null) {
        if (parts.isEmptyInputMessage()) return
        // 会话初始化完成前（groupId 未就绪）不发，防止走普通聊天路径存成孤儿
        if (!conversation.value.isGroupDiscussion) return
        if (nextSpeakerId != null) {
            chatService.setNextSpeakerHintOnly(_conversationId, nextSpeakerId)
        }
        chatService.sendMessage(_conversationId, parts, answer = true)
    }

    /** 暂停当前发言 */
    fun pause() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    /** 继续讨论（从 transcript 推导下一位，不重复已完成的轮次） */
    fun resume() {
        if (!conversation.value.isGroupDiscussion) return
        chatService.resumeDiscussion(_conversationId)
    }

    /** 指定下一位发言者 */
    fun setNextSpeaker(memberId: Uuid) {
        chatService.setNextSpeaker(_conversationId, memberId)
    }

    /** 在本群组下新建一场空对话，返回新会话 id（供跳转） */
    fun createNewConversation(onCreated: (Uuid) -> Unit) {
        val groupId = conversation.value.groupId ?: return
        viewModelScope.launch {
            val convId = chatService.createGroupConversation(groupId)
            onCreated(convId)
        }
    }

    /** 是否正在生成（驱动暂停/继续按钮图标） */
    val isGenerating: Boolean get() = generationJob.value?.isActive == true
}
