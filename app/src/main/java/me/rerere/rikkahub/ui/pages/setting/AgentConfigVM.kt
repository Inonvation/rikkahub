package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.config.AgentConfigExporter
import me.rerere.rikkahub.data.config.AgentConfigRepository
import me.rerere.rikkahub.data.config.AgentConfigView
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * Agent 统一配置视图模型（管理控制台使用）：
 * - [view]：agent/ 目录各配置文件的脱敏视图（含分类与助手显示名）；
 * - [refresh]：从 DataStore 重新导出后再刷新视图；
 * - 全程不展示任何明文密钥（仅 keystore:* 引用占位）。
 */
class AgentConfigVM(
    private val repository: AgentConfigRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _view = MutableStateFlow(AgentConfigView())
    val view: StateFlow<AgentConfigView> = _view.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        // 首次加载放到 IO 线程，避免在 ViewModel 构造（主线程）同步读文件
        viewModelScope.launch(Dispatchers.IO) {
            _view.value = repository.view()
        }
    }

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                AgentConfigExporter.export(
                    settings = settingsStore.settingsFlow.value,
                    agentRoot = repository.root,
                )
            } finally {
                _view.value = repository.view()
                _refreshing.value = false
            }
        }
    }
}
