package me.rerere.rikkahub.ui.pages.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.Group
import me.rerere.rikkahub.data.repository.GroupRepository
import me.rerere.rikkahub.service.ChatService

/**
 * 群组中心页 ViewModel：Group 列表 + 删除。
 * 数据源为 Room Flow（创建/编辑/删除后自动刷新，无需手动 refresh）。
 */
class GroupDiscussionListVM(
    private val chatService: ChatService,
    private val groupRepository: GroupRepository,
) : ViewModel() {

    val groups: StateFlow<List<Group>> = groupRepository.getAllGroupsFlow()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    /** 删除群组（停全部生成 + 级联清理所有会话 + 删 Group） */
    fun deleteGroup(group: Group) {
        viewModelScope.launch {
            chatService.deleteGroup(group.id)
        }
    }
}
