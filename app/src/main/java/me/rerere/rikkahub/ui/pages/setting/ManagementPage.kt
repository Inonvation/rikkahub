package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.FooterIndicator
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.SettingListScaffold
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel

/**
 * 管理控制台（轻量版）：
 * - 配置文件：二级页查看/导入导出 agent 配置文件（提供商 / MCP / 助手等），避免长列表占用主界面；
 * - 上下文浮窗显示：控制点击上下文圆圈后浮窗中展示的指标项；
 * - 管理审计：二级页查看管理操作记录与权限拦截记录。
 */
@Composable
fun ManagementPage(
    vm: SettingVM = koinViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    SettingListScaffold(
        title = stringResource(R.string.setting_page_management_console),
        loading = settings.init,
    ) {
        item("config_files") {
            IosGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.agent_config_page_files)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                R.string.setting_page_console_config_files_summary,
                                settings.providers.size,
                                settings.assistants.size,
                                settings.mcpServers.size,
                            )
                        )
                    },
                    onClick = { navController.navigate(Screen.SettingConfigFiles) },
                    trailingContent = { FileRowChevron() },
                )
            }
        }

        item("footer") {
            FooterDisplayGroup(
                settings = settings,
                onUpdateDisplaySetting = { display ->
                    vm.updateSettings(settings.copy(displaySetting = display))
                },
            )
        }

        item("audit") {
            IosGroup(
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                item(
                    headlineContent = { Text(stringResource(R.string.setting_page_console_audit)) },
                    supportingContent = { Text(stringResource(R.string.setting_page_console_audit_desc)) },
                    onClick = { navController.navigate(Screen.SettingManagementAudit) },
                    trailingContent = { FileRowChevron() },
                )
            }
        }
    }
}

// ---------- 上下文浮窗显示 ----------

@Composable
private fun FooterDisplayGroup(
    settings: Settings,
    onUpdateDisplaySetting: (DisplaySetting) -> Unit,
) {
    val display = settings.displaySetting
    IosGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = stringResource(R.string.setting_page_console_footer),
        subtitle = stringResource(R.string.setting_page_console_footer_desc),
    ) {
        // GLOBAL_USAGE 已下线（浮窗不再展示会话用量）：枚举条目保留仅为兼容旧存档反序列化，
        // 开关列表不再暴露该选项
        FooterIndicator.entries
            .filterNot { it == FooterIndicator.GLOBAL_USAGE }
            .forEach { indicator ->
                item(
                    headlineContent = { Text(footerIndicatorLabel(indicator)) },
                    supportingContent = { Text(footerIndicatorDesc(indicator)) },
                    trailingContent = {
                        Switch(
                            checked = indicator in display.footerIndicators,
                            onCheckedChange = { checked ->
                                val updated = if (checked) {
                                    display.footerIndicators + indicator
                                } else {
                                    display.footerIndicators.filterNot { it == indicator }
                                }
                                onUpdateDisplaySetting(display.copy(footerIndicators = updated))
                            },
                        )
                    },
                )
            }
    }
}

@Composable
private fun footerIndicatorLabel(indicator: FooterIndicator): String = stringResource(
    when (indicator) {
        FooterIndicator.CURRENT_MODEL -> R.string.setting_page_console_footer_model
        FooterIndicator.PROVIDER_BALANCE -> R.string.setting_page_console_footer_balance
        FooterIndicator.CACHE_HIT_RATE -> R.string.setting_page_console_footer_cache
        FooterIndicator.COST -> R.string.setting_page_console_footer_cost
        FooterIndicator.TOKENS -> R.string.setting_page_console_footer_tokens
        FooterIndicator.MESSAGES -> R.string.setting_page_console_footer_messages
        FooterIndicator.GLOBAL_USAGE -> R.string.setting_page_console_footer_usage
    }
)

@Composable
private fun footerIndicatorDesc(indicator: FooterIndicator): String = stringResource(
    when (indicator) {
        FooterIndicator.CURRENT_MODEL -> R.string.setting_page_console_footer_model_desc
        FooterIndicator.PROVIDER_BALANCE -> R.string.setting_page_console_footer_balance_desc
        FooterIndicator.CACHE_HIT_RATE -> R.string.setting_page_console_footer_cache_desc
        FooterIndicator.COST -> R.string.setting_page_console_footer_cost_desc
        FooterIndicator.TOKENS -> R.string.setting_page_console_footer_tokens_desc
        FooterIndicator.MESSAGES -> R.string.setting_page_console_footer_messages_desc
        FooterIndicator.GLOBAL_USAGE -> R.string.setting_page_console_footer_usage_desc
    }
)