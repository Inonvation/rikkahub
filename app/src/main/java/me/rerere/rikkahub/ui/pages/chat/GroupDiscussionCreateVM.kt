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

class GroupDiscussionCreateVM(
    private val settingsStore: SettingsStore,
    private val chatService: ChatService,
) : ViewModel() {

    // 可选成员（全部助手）
    private val _allAssistants = MutableStateFlow<List<Assistant>>(emptyList())
    val allAssistants: StateFlow<List<Assistant>> = _allAssistants.asStateFlow()

    // 已勾选成员（按勾选顺序）
    private val _selected = MutableStateFlow<List<Uuid>>(emptyList())
    val selected: StateFlow<List<Uuid>> = _selected.asStateFlow()

    // 发言模式
    private val _mode = MutableStateFlow(DiscussionMode.ROUND_ROBIN)
    val mode: StateFlow<DiscussionMode> = _mode.asStateFlow()

    // 轮数
    private val _rounds = MutableStateFlow(3)
    val rounds: StateFlow<Int> = _rounds.asStateFlow()

    // 群名
    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    // 是否正在创建
    private val _creating = MutableStateFlow(false)
    val creating: StateFlow<Boolean> = _creating.asStateFlow()

    // 创建结果回调（返回会话 id）
    var onCreated: ((Uuid) -> Unit)? = null

    init {
        viewModelScope.launch {
            _allAssistants.value = settingsStore.settingsFlow.value.assistants
        }
    }

    fun toggleMember(assistantId: Uuid) {
        _selected.value = if (_selected.value.contains(assistantId)) {
            _selected.value - assistantId
        } else {
            _selected.value + assistantId
        }
    }

    fun setMode(mode: DiscussionMode) {
        _mode.value = mode
    }

    fun setRounds(rounds: Int) {
        _rounds.value = rounds.coerceIn(1, 10)
    }

    fun setName(name: String) {
        _name.value = name
    }

    fun create() {
        val selectedIds = _selected.value
        val nameText = _name.value.trim()
        if (selectedIds.size < 2) return
        if (nameText.isEmpty()) return
        if (_creating.value) return

        _creating.value = true
        viewModelScope.launch {
            val all = _allAssistants.value
            val members = selectedIds.mapIndexed { index, id ->
                val assistant = all.find { it.id == id }
                DiscussionMember(
                    assistantId = id,
                    name = assistant?.name ?: "成员${index + 1}",
                    avatar = assistant?.avatar ?: me.rerere.rikkahub.data.model.Avatar.Dummy,
                    order = index,
                    enabled = true,
                )
            }
            val config = DiscussionConfig(
                members = members,
                mode = _mode.value,
                rounds = _rounds.value,
            )
            // createGroup 返回 groupId（== 首个会话 id），供导航进讨论页/后续编辑复用
            val groupId = chatService.createGroup(
                title = nameText,
                config = config,
            )
            _creating.value = false
            onCreated?.invoke(groupId)
        }
    }
}
