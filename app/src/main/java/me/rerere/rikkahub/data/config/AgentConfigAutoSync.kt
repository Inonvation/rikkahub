package me.rerere.rikkahub.data.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * 设置变更自动导出：监听 [SettingsStore.settingsFlow]，变化停顿 5 秒后把最新配置
 * 导出到 agent/（与 AI 的 config_refresh、管理页刷新走同一条 [AgentConfigExporter] 路径）。
 *
 * 只读旁路：不写回 DataStore，不阻塞设置流程；首次导出由用户/AI 显式触发，
 * 这里只保证"设置改了 → agent/ 自动跟上"。
 */
@OptIn(FlowPreview::class)
class AgentConfigAutoSync(
    private val settingsStore: SettingsStore,
    private val repository: AgentConfigRepository,
    private val appScope: AppScope,
) {
    fun start() {
        appScope.launch {
            settingsStore.settingsFlow
                .drop(1) // 跳过初始值，避免启动即全量导出
                .debounce(5_000)
                .collectLatest { settings ->
                    withContext(Dispatchers.IO) {
                        AgentConfigExporter.export(settings, repository.root)
                    }
                }
        }
    }
}
