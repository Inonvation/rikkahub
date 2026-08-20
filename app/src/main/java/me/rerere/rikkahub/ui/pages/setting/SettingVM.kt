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
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.model.CustomModeConfig
import me.rerere.rikkahub.data.model.ModeRefs
import me.rerere.rikkahub.data.repository.ConversationRepository
import kotlin.uuid.Uuid

data class CustomModeDeleteRequest(
    val mode: CustomModeConfig,
    val conversationCount: Int,
)

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager,
    private val conversationRepository: ConversationRepository,
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
            settingsStore.update { settings ->
                val updated = if (settings.customModes.any { it.id == mode.id }) {
                    settings.customModes.map { if (it.id == mode.id) mode else it }
                } else {
                    settings.customModes + mode
                }
                settings.copy(customModes = updated)
            }
        }
    }

    fun duplicateCustomMode(mode: CustomModeConfig) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                val baseName = mode.name.ifBlank { "自定义模式" }
                var name = "${baseName} 副本"
                var index = 2
                while (settings.customModes.any { it.name == name }) {
                    name = "${baseName} 副本 $index"
                    index++
                }
                val copy = mode.copy(
                    id = Uuid.random().toString(),
                    name = name,
                )
                settings.copy(customModes = settings.customModes + copy)
            }
        }
    }

    fun importCustomMode(mode: CustomModeConfig) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                val baseName = mode.name.ifBlank { "自定义模式" }
                var name = baseName
                var index = 2
                while (settings.customModes.any { it.name == name }) {
                    name = "$baseName $index"
                    index++
                }
                val imported = mode.copy(
                    id = Uuid.random().toString(),
                    name = name,
                )
                settings.copy(customModes = settings.customModes + imported)
            }
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
            val ref = ModeRefs.custom(request.mode.id)
            settingsStore.update { settings ->
                settings.copy(
                    customModes = settings.customModes.filterNot { it.id == request.mode.id },
                    defaultMode = settings.defaultMode?.takeUnless { it == ref },
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.defaultMode == ref) assistant.copy(defaultMode = null) else assistant
                    },
                )
            }
            _pendingDelete.value = null
        }
    }

    fun cancelDeleteCustomMode() {
        _pendingDelete.value = null
    }
}
