package me.rerere.rikkahub.ui.pages.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.DiscussionConfig
import me.rerere.rikkahub.data.model.DiscussionMember
import me.rerere.rikkahub.data.model.DiscussionMode
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

/**
 * 群组编辑页 ViewModel。`id` 为 **groupId**。
 *
 * 进入后把 Group 配置快照进可编辑状态；编辑过程不落盘，
 * 点保存才写入（群下正在生成则先自动暂停）。返回 onSaved 让页面导航回去。
 */
class GroupDiscussionEditVM(
    id: String,
    private val chatService: ChatService,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val _groupId: Uuid = Uuid.parse(id)

    val group = chatService.getGroupFlow(_groupId)

    private val _allAssistants = MutableStateFlow<List<Assistant>>(emptyList())
    val allAssistants: StateFlow<List<Assistant>> = _allAssistants.asStateFlow()

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _mode = MutableStateFlow(DiscussionMode.ROUND_ROBIN)
    val mode: StateFlow<DiscussionMode> = _mode.asStateFlow()

    private val _rounds = MutableStateFlow(3)
    val rounds: StateFlow<Int> = _rounds.asStateFlow()

    private val _summaryPrompt = MutableStateFlow("")
    val summaryPrompt: StateFlow<String> = _summaryPrompt.asStateFlow()

    /** 当前成员列表（含 enabled=false 的已移除成员，编辑页只展示 enabled 的） */
    private val _members = MutableStateFlow<List<DiscussionMember>>(emptyList())
    val members: StateFlow<List<DiscussionMember>> = _members.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    var onSaved: (() -> Unit)? = null

    init {
        viewModelScope.launch {
            _allAssistants.value = settingsStore.settingsFlow.value.assistants
            // 首次拿到 Group 后快照配置进可编辑状态
            val g = chatService.getGroupFlow(_groupId).value
            val config = g?.config
            if (g != null && config != null) {
                _name.value = g.name
                _mode.value = config.mode
                _rounds.value = config.rounds
                _summaryPrompt.value = config.summaryPrompt ?: ""
                _members.value = config.members.sortedBy { it.order }
            }
        }
    }

    /** 有效成员 = enabled 且非占位，UI 校验用 */
    val enabledMembers: List<DiscussionMember>
        get() = _members.value.filter { it.enabled }

    fun setName(value: String) {
        _name.value = value
    }

    fun setMode(value: DiscussionMode) {
        _mode.value = value
    }

    fun setRounds(value: Int) {
        _rounds.value = value.coerceIn(1, 10)
    }

    fun setSummaryPrompt(value: String) {
        _summaryPrompt.value = value
    }

    /** 添加助手为成员（按当前 Assistant 快照） */
    fun addMember(assistant: Assistant) {
        if (_members.value.any { it.assistantId == assistant.id }) return
        val newMember = DiscussionMember(
            assistantId = assistant.id,
            name = assistant.name,
            avatar = assistant.avatar,
            order = _members.value.size,
            enabled = true,
        )
        _members.value = _members.value + newMember
    }

    fun removeMember(index: Int) {
        val list = _members.value.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        _members.value = list.mapIndexed { i, m -> m.copy(order = i) }
    }

    fun moveMember(index: Int, delta: Int) {
        val list = _members.value.toMutableList()
        val target = index + delta
        if (index !in list.indices || target !in list.indices) return
        val tmp = list[index]
        list[index] = list[target]
        list[target] = tmp
        _members.value = list.mapIndexed { i, m -> m.copy(order = i) }
    }

    /** 更新单个成员（风格/温度/maxTokens 等） */
    fun updateMember(index: Int, transform: (DiscussionMember) -> DiscussionMember) {
        val list = _members.value.toMutableList()
        if (index !in list.indices) return
        list[index] = transform(list[index])
        _members.value = list
    }

    /** 保存：群下正在生成先自动暂停，再写回 Group 配置 */
    fun save() {
        val nameText = _name.value.trim()
        val active = enabledMembers
        if (active.size < 2) return
        if (nameText.isEmpty()) return
        if (_saving.value) return

        _saving.value = true
        viewModelScope.launch {
            chatService.stopGroupGeneration(_groupId)
            val config = DiscussionConfig(
                members = active,
                mode = _mode.value,
                rounds = _rounds.value,
                summaryPrompt = _summaryPrompt.value.trim().ifBlank { null },
                createdAt = group.value?.config?.createdAt ?: java.time.Instant.now(),
            )
            chatService.updateGroupConfig(_groupId, nameText, config)
            _saving.value = false
            onSaved?.invoke()
        }
    }
}
