package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.data.datastore.FooterIndicator
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.MessageTokenStats
import me.rerere.rikkahub.data.db.dao.SubAgentUsageDAO
import me.rerere.rikkahub.data.db.dao.SubAgentTokenStats
import me.rerere.rikkahub.data.db.dao.getTokenStats
import me.rerere.rikkahub.data.management.ManagementAuditEntry
import me.rerere.rikkahub.data.management.ManagementAuditStore
import me.rerere.rikkahub.data.model.Capability
import me.rerere.rikkahub.data.model.ChatModePolicy
import me.rerere.rikkahub.data.model.resolveModePolicy
import me.rerere.rikkahub.data.model.restrictedCapabilities
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderSettings
import me.rerere.rikkahub.ui.components.ai.ProviderBalanceText
import me.rerere.rikkahub.ui.components.ai.modeRefDisplayName
import me.rerere.rikkahub.ui.components.ai.note
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.SettingListScaffold
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ManagementPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val trustedFolderRepository: TrustedFolderRepository = koinInject()
    val trustedSettings by trustedFolderRepository.settingsFlow
        .collectAsState(initial = TrustedFolderSettings())
    val conversationRepository: ConversationRepository = koinInject()
    val mcpManager: McpManager = koinInject()
    val mcpStatus by mcpManager.syncingStatus.collectAsStateWithLifecycle()
    val auditStore: ManagementAuditStore = koinInject()
    val auditEntries by auditStore.entries.collectAsStateWithLifecycle()
    val workspaceRepository: WorkspaceRepository = koinInject()
    val workspaces by workspaceRepository.listFlow().collectAsState(initial = emptyList())
    val messageNodeDAO: MessageNodeDAO = koinInject()
    val subAgentUsageDAO: SubAgentUsageDAO = koinInject()
    val navController = LocalNavController.current

    var conversationCount by remember { mutableStateOf<Int?>(null) }
    var tokenStats by remember { mutableStateOf(MessageTokenStats()) }
    var subTokenStats by remember { mutableStateOf(SubAgentTokenStats()) }

    LaunchedEffect(Unit) {
        conversationCount = conversationRepository.countConversations()
    }
    LaunchedEffect(Unit) {
        tokenStats = messageNodeDAO.getTokenStats()
        subTokenStats = subAgentUsageDAO.getTokenStats()
    }

    val currentAssistant = settings.getCurrentAssistant()
    val modelCount = settings.providers.sumOf { it.models.size }
    val knowledgeCount = settings.assistants.flatMap { it.knowledgeBaseIds }.distinct().size
    val workspaceCount = settings.assistants.count { it.workspaceId != null }
    val customModeCount = settings.customModes.size
    val builtinOverrideCount = settings.builtinModeOverrides.size
    val modeRef = currentAssistant.defaultMode ?: settings.defaultMode
    val modePolicy = resolveModePolicy(modeRef, settings) ?: ChatModePolicy.UNRESTRICTED
    val restricted = modePolicy.restrictedCapabilities(settings)
    val managementCapabilities = Capability.entries.filter {
        it.managementOnly && it in modePolicy.capabilities
    }
    val mcpConnected = settings.mcpServers.count { mcpStatus[it.id] is McpStatus.Connected }
    val mcpErrorCount = settings.mcpServers.count { mcpStatus[it.id] is McpStatus.Error }
    val mcpTotal = settings.mcpServers.size
    val activeTrusted = trustedSettings.activeProjectId != null
    val shellEnabledCount = workspaces.count { !it.shellStatus.equals("DISABLED", ignoreCase = true) }
    val totalPromptTokens = tokenStats.promptTokens + subTokenStats.promptTokens
    val totalCompletionTokens = tokenStats.completionTokens + subTokenStats.completionTokens
    val totalCachedTokens = tokenStats.cachedTokens + subTokenStats.cachedTokens
    val modeLabel = modeRefDisplayName(modeRef, settings.customModes, settings.builtinModeOverrides)
    val currentModel = settings.getCurrentChatModel()
    val currentProvider = currentModel?.findProvider(settings.providers)
    val balanceSupported = currentProvider?.balanceOption?.enabled == true &&
        currentProvider is ProviderSetting.OpenAI

    SettingListScaffold(
        title = stringResource(R.string.setting_page_management_console),
        loading = settings.init,
    ) {
        item("overview") {
            OverviewCard(
                currentModelLabel = currentModel?.displayName ?: "-",
                currentProviderForBalance = currentProvider,
                balanceSupported = balanceSupported,
                totalMessages = tokenStats.totalMessages,
                promptTokens = totalPromptTokens,
                completionTokens = totalCompletionTokens,
                cachedTokens = totalCachedTokens,
                onOpenStats = { navController.navigate(Screen.Stats) },
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        item("resources") {
            ResourcesGroup(
                providerCount = settings.providers.size,
                modelCount = modelCount,
                assistantCount = settings.assistants.size,
                customModeCount = customModeCount,
                builtinOverrideCount = builtinOverrideCount,
                mcpConnected = mcpConnected,
                mcpErrorCount = mcpErrorCount,
                mcpTotal = mcpTotal,
                searchCount = settings.searchServices.size,
                skillCount = currentAssistant.enabledSkills.size,
                knowledgeCount = knowledgeCount,
                workspaceCount = workspaceCount,
                shellEnabledCount = shellEnabledCount,
                activeTrusted = activeTrusted,
                trustedProjectCount = trustedSettings.projects.size,
                conversationCount = conversationCount,
                onNavigate = { screen -> navController.navigate(screen) },
            )
        }

        item("footer") {
            FooterDisplayGroup(
                settings = settings,
                onUpdateDisplaySetting = { display ->
                    vm.updateSettings(settings.copy(displaySetting = display))
                },
            )
        }

        item("permissions") {
            PermissionsGroup(
                modeLabel = modeLabel,
                managementCapabilities = managementCapabilities,
                restricted = restricted.sortedBy { it.name },
            )
        }

        item("audit") {
            AuditGroup(
                entries = auditEntries,
                onViewAll = { navController.navigate(Screen.SettingDeviceAudit) },
            )
        }
    }
}

// ---------- 顶部状态概要 ----------

@Composable
private fun OverviewCard(
    currentModelLabel: String,
    currentProviderForBalance: ProviderSetting?,
    balanceSupported: Boolean,
    totalMessages: Int,
    promptTokens: Long,
    completionTokens: Long,
    cachedTokens: Long,
    onOpenStats: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CustomColors.cardColorsOnSurfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.setting_page_console_overview),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.setting_page_console_current_model),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = currentModelLabel,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (balanceSupported && currentProviderForBalance != null) {
                    ProviderBalanceText(
                        providerSetting = currentProviderForBalance,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.setting_page_console_balance_unsupported),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Text(
                text = stringResource(R.string.setting_page_console_usage),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OverviewPill(
                    label = stringResource(R.string.setting_page_console_usage_messages),
                    value = formatCount(totalMessages),
                )
                OverviewPill(
                    label = stringResource(R.string.setting_page_console_usage_prompt),
                    value = formatTokens(promptTokens),
                )
                OverviewPill(
                    label = stringResource(R.string.setting_page_console_usage_completion),
                    value = formatTokens(completionTokens),
                )
                OverviewPill(
                    label = stringResource(R.string.setting_page_console_usage_cached),
                    value = formatTokens(cachedTokens),
                )
            }
            TextButton(onClick = onOpenStats) {
                Icon(
                    imageVector = HugeIcons.ArrowRight01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.setting_page_console_usage_detail))
            }
        }
    }
}

@Composable
private fun OverviewPill(
    label: String,
    value: String,
    type: TagType = TagType.DEFAULT,
) {
    Tag(type = type) {
        Text("$label $value")
    }
}

// ---------- 资源与配置 ----------

@Composable
private fun ResourcesGroup(
    providerCount: Int,
    modelCount: Int,
    assistantCount: Int,
    customModeCount: Int,
    builtinOverrideCount: Int,
    mcpConnected: Int,
    mcpErrorCount: Int,
    mcpTotal: Int,
    searchCount: Int,
    skillCount: Int,
    knowledgeCount: Int,
    workspaceCount: Int,
    shellEnabledCount: Int,
    activeTrusted: Boolean,
    trustedProjectCount: Int,
    conversationCount: Int?,
    onNavigate: (Screen) -> Unit,
) {
    IosGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = stringResource(R.string.setting_page_console_resources),
    ) {
        item(
            headlineContent = { Text(stringResource(R.string.setting_page_providers)) },
            supportingContent = { Text("${providerCount} 个 · $modelCount 个模型") },
            onClick = { onNavigate(Screen.SettingProvider) },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_page_assistant)) },
            supportingContent = { Text("$assistantCount 个助手") },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_page_default_mode)) },
            supportingContent = {
                Text("自定义 $customModeCount · 覆盖 $builtinOverrideCount")
            },
            onClick = { onNavigate(Screen.SettingModes) },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_page_mcp)) },
            supportingContent = {
                Text(
                    if (mcpTotal == 0) {
                        "未配置"
                    } else if (mcpErrorCount > 0) {
                        "$mcpConnected/$mcpTotal 已连接 · $mcpErrorCount 个异常"
                    } else {
                        "$mcpConnected/$mcpTotal 已连接"
                    }
                )
            },
            onClick = { onNavigate(Screen.SettingMcp) },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_page_search_service)) },
            supportingContent = { Text("$searchCount 个服务") },
            onClick = { onNavigate(Screen.SettingSearch) },
        )
        item(
            headlineContent = { Text(stringResource(R.string.extensions_page_agent_skills)) },
            supportingContent = { Text("$skillCount 个已启用") },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_page_knowledge_bases)) },
            supportingContent = { Text("$knowledgeCount 个已绑定") },
        )
        item(
            headlineContent = { Text(stringResource(R.string.extensions_page_workspace)) },
            supportingContent = {
                Text("$workspaceCount 个已绑定 · 启用 shell $shellEnabledCount")
            },
            onClick = { onNavigate(Screen.SettingFiles) },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_page_trusted_folders)) },
            supportingContent = {
                Text(
                    "$trustedProjectCount 个项目" +
                        if (activeTrusted) " · 已激活" else ""
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_page_conversation_history)) },
            supportingContent = { Text((conversationCount?.toString() ?: "-") + " 个会话") },
            onClick = { onNavigate(Screen.Stats) },
        )
    }
}

// ---------- 模式与权限 ----------

@Composable
private fun PermissionsGroup(
    modeLabel: String,
    managementCapabilities: List<Capability>,
    restricted: List<Capability>,
) {
    IosGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = stringResource(R.string.setting_page_console_permissions),
    ) {
        item(
            headlineContent = { Text(stringResource(R.string.setting_page_default_mode)) },
            supportingContent = { Text(modeLabel) },
        )
        item(
            headlineContent = { Text(stringResource(R.string.setting_page_console_management_capabilities)) },
            supportingContent = {
                if (managementCapabilities.isEmpty()) {
                    Text(stringResource(R.string.setting_page_console_no_permission))
                } else {
                    CapabilityChips(capabilities = managementCapabilities, type = TagType.INFO)
                }
            },
        )
        if (restricted.isNotEmpty()) {
            item(
                headlineContent = { Text(stringResource(R.string.setting_page_console_restricted)) },
                supportingContent = {
                    CapabilityChips(capabilities = restricted, type = TagType.WARNING)
                },
            )
        }
    }
}

@Composable
private fun CapabilityChips(
    capabilities: List<Capability>,
    type: TagType,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        capabilities.forEach { cap ->
            Tag(type = type) {
                Text(cap.note())
            }
        }
    }
}

// ---------- 管理审计 ----------

private enum class AuditFilter {
    ALL, SUCCESS, RESTRICTED, ERROR;

    fun matches(result: String): Boolean = when (this) {
        ALL -> true
        SUCCESS -> result.startsWith("success")
        RESTRICTED -> result == "forbidden" || result.startsWith("blocked")
        ERROR -> !result.startsWith("success") && result != "forbidden" && !result.startsWith("blocked")
    }

    @Composable
    fun label(): String = stringResource(
        when (this) {
            ALL -> R.string.setting_page_console_audit_filter_all
            SUCCESS -> R.string.setting_page_console_audit_filter_success
            RESTRICTED -> R.string.setting_page_console_audit_filter_restricted
            ERROR -> R.string.setting_page_console_audit_filter_error
        }
    )
}

@Composable
private fun auditResultColor(result: String): Color = when {
    result.startsWith("success") -> MaterialTheme.colorScheme.primary
    result == "forbidden" || result.startsWith("blocked") -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

@Composable
private fun AuditGroup(
    entries: List<ManagementAuditEntry>,
    onViewAll: () -> Unit,
) {
    var filter by remember { mutableStateOf(AuditFilter.ALL) }
    var expandedTimestamp by remember { mutableStateOf<Long?>(null) }
    val filtered = remember(entries, filter) { entries.filter { filter.matches(it.result) } }
    val formatter = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier.padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.setting_page_console_audit),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AuditFilter.entries.forEach { f ->
                Tag(
                    type = if (filter == f) TagType.INFO else TagType.DEFAULT,
                    onClick = { filter = f },
                ) {
                    Text(f.label())
                }
            }
        }
        if (filtered.isEmpty()) {
            IosGroup {
                item(headlineContent = { Text(stringResource(R.string.setting_page_console_no_audit)) })
            }
        } else {
            IosGroup {
                filtered.take(8).forEach { entry ->
                    val expanded = expandedTimestamp == entry.timestamp
                    item(
                        headlineContent = { Text(entry.tool) },
                        supportingContent = {
                            Column {
                                Text(
                                    text = "[${formatter.format(Date(entry.timestamp))}] " +
                                        "${entry.target} · ${entry.result}",
                                    color = auditResultColor(entry.result),
                                )
                                if (expanded && entry.detail.isNotBlank()) {
                                    Text(
                                        text = entry.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            if (entry.detail.isNotBlank()) {
                                Icon(
                                    imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        },
                        onClick = {
                            if (entry.detail.isNotBlank()) {
                                expandedTimestamp = if (expanded) null else entry.timestamp
                            }
                        },
                    )
                }
            }
        }
        TextButton(onClick = onViewAll) {
            Icon(
                imageVector = HugeIcons.ArrowRight01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.setting_page_console_view_all_audit))
        }
    }
}

// ---------- 输入框下方显示 ----------

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
        FooterIndicator.entries.forEach { indicator ->
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
        item(
            headlineContent = { Text(stringResource(R.string.setting_page_console_footer_limit)) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.setting_page_console_footer_limit_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = display.footerIndicatorLimit.toFloat(),
                            onValueChange = {
                                onUpdateDisplaySetting(
                                    display.copy(footerIndicatorLimit = it.roundToInt())
                                )
                            },
                            valueRange = 1f..FooterIndicator.entries.size.toFloat(),
                            steps = FooterIndicator.entries.size - 2,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${display.footerIndicatorLimit} 项",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            },
        )
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
    }
)

// ---------- 数字格式化 ----------

private fun formatCount(value: Int): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 1_000 -> "%.1fk".format(value / 1_000.0)
    else -> value.toString()
}

private fun formatTokens(value: Long): String = when {
    value >= 1_000_000 -> "%.2fM".format(value / 1_000_000.0)
    value >= 1_000 -> "%.1fk".format(value / 1_000.0)
    else -> value.toString()
}
