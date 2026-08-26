package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.ai.mcp.McpDescriptionGenerator
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.model.ChatMode
import me.rerere.rikkahub.data.model.ChatModePolicy
import me.rerere.rikkahub.data.model.CustomModeConfig
import me.rerere.rikkahub.data.model.ModeRefs
import me.rerere.rikkahub.data.repository.ConversationRepository

data class CustomModeDeleteRequest(
    val mode: CustomModeConfig,
    val conversationCount: Int,
)

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager,
    private val conversationRepository: ConversationRepository,
    private val mcpDescriptionGenerator: McpDescriptionGenerator,
) :
    ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings(init = true, providers = emptyList()))

    private val _pendingDelete = MutableStateFlow<CustomModeDeleteRequest?>(null)
    val pendingDelete: StateFlow<CustomModeDeleteRequest?> = _pendingDelete.asStateFlow()

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    /**
     * 仅更新 providers 列表，供供应商详情页自动保存使用。
     */
    fun updateProviders(providers: List<ProviderSetting>) {
        viewModelScope.launch {
            settingsStore.updateProviders(providers)
        }
    }

    fun upsertCustomMode(mode: CustomModeConfig) {
        viewModelScope.launch {
            settingsStore.upsertCustomMode(mode)
        }
    }

    fun upsertBuiltinMode(mode: ChatMode, policy: ChatModePolicy) {
        viewModelScope.launch {
            settingsStore.upsertBuiltinMode(mode, policy)
        }
    }

    fun resetBuiltinMode(mode: ChatMode) {
        viewModelScope.launch {
            settingsStore.resetBuiltinMode(mode)
        }
    }

    fun duplicateCustomMode(mode: CustomModeConfig) {
        viewModelScope.launch {
            settingsStore.duplicateCustomMode(mode)
        }
    }

    fun importCustomMode(mode: CustomModeConfig) {
        viewModelScope.launch {
            settingsStore.importCustomMode(mode)
        }
    }

    fun requestDeleteCustomMode(mode: CustomModeConfig) {
        viewModelScope.launch {
            val count = conversationRepository.countConversationsByMode(ModeRefs.custom(mode.id))
            _pendingDelete.value = CustomModeDeleteRequest(mode = mode, conversationCount = count)
        }
    }

    fun confirmDeleteCustomMode() {
        viewModelScope.launch {
            val request = _pendingDelete.value ?: return@launch
            settingsStore.deleteCustomMode(request.mode.id)
            _pendingDelete.value = null
        }
    }

    fun cancelDeleteCustomMode() {
        _pendingDelete.value = null
    }

    /**
     * 用默认快速模型为 MCP 服务器提炼一句话描述（基于已发现的工具）。
     * 仅当 [tools] 非空（连接完成）时才可调用；失败抛出异常。
     */
    suspend fun generateMcpDescription(
        server: McpServerConfig,
        tools: List<McpTool> = server.commonOptions.tools,
    ): String =
        mcpDescriptionGenerator.generateDescription(
            serverName = server.commonOptions.name,
            tools = tools,
        )
}
