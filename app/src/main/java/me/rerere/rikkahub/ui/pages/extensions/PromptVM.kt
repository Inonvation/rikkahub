package me.rerere.rikkahub.ui.pages.extensions

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore

class PromptVM(
    private val settingsStore: SettingsStore
) : ViewModel() {
    val settings = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    /** 模式注入批量选择状态（收进 VM，随页面生命周期释放，避免模块级全局变量跨页面残留） */
    val selectedModeInjections = mutableStateListOf<Uuid>()

    /** 世界书批量选择状态 */
    val selectedLorebooks = mutableStateListOf<Uuid>()

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }
}
