package me.rerere.rikkahub.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.github.DeviceFlowPhase
import me.rerere.rikkahub.data.github.GitHubAuthManager

class GitHubAuthVM(
    private val githubAuthManager: GitHubAuthManager,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    val authState = githubAuthManager.state
    val rateLimit = githubAuthManager.rateLimit
    val isConfigured = githubAuthManager.isConfigured

    /** Device Flow 轮询遇网络失败自动重试中（授权弹窗提示用） */
    val deviceRetrying = githubAuthManager.retrying

    /** 上次绑定的账号元数据（Settings 同步镜像，未绑定时引导重绑） */
    val lastAccount = settingsStore.settingsFlow
        .map { it.githubAccount }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 向工作区 shell 注入凭据的总闸（默认关） */
    val tokenInjectionEnabled = settingsStore.settingsFlow
        .map { it.workspaceGithubTokenEnabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _devicePhase = MutableStateFlow<DeviceFlowPhase?>(null)
    val devicePhase = _devicePhase.asStateFlow()

    private var signInJob: Job? = null

    fun signIn() {
        if (signInJob?.isActive == true) return
        _devicePhase.value = null
        signInJob = viewModelScope.launch {
            githubAuthManager.signInWithDeviceFlow { phase ->
                _devicePhase.value = phase
            }
        }
    }

    /** 关闭授权弹窗并终止轮询 */
    fun dismissDeviceDialog() {
        signInJob?.cancel()
        signInJob = null
        _devicePhase.value = null
    }

    fun bindWithPat(token: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val error = githubAuthManager.bindWithPat(token)
            withContext(Dispatchers.Main) { onResult(error) }
        }
    }

    fun unbind() {
        viewModelScope.launch {
            githubAuthManager.unbind()
        }
    }

    fun setTokenInjection(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.update { it.copy(workspaceGithubTokenEnabled = enabled) }
        }
    }
}
