package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.config.AgentConfigArchive
import me.rerere.rikkahub.data.config.AgentConfigFileCategory
import me.rerere.rikkahub.data.config.AgentConfigRepository
import me.rerere.rikkahub.data.config.AgentConfigView
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
import me.rerere.rikkahub.ui.components.ai.ProviderBalanceText
import me.rerere.rikkahub.ui.components.ai.modeRefDisplayName
import me.rerere.rikkahub.ui.components.ai.note
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.SettingListScaffold
import me.rerere.rikkahub.ui.components.ui.Switch
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 管理控制台：
 * - 运行概览：当前模型 / 余额 / 会话用量（纯展示，不做跳转）；
 * - 配置文件：agent/ 导出文件按分类（导出状态 / 提供商 / MCP / 助手 / 其他）分组，
 *   点击条目以工作区同款方式打开只读预览；
 * - 当前模式权限、管理审计、输入框下方显示设置。
 */
@Composable
fun ManagementPage(
    vm: SettingVM = koinViewModel(),
    agentConfigVM: AgentConfigVM = koinViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val auditStore: ManagementAuditStore = koinInject()
    val auditEntries by auditStore.entries.collectAsStateWithLifecycle()
    val messageNodeDAO: MessageNodeDAO = koinInject()
    val subAgentUsageDAO: SubAgentUsageDAO = koinInject()
    val navController = LocalNavController.current
    val context = LocalContext.current
    val repository: AgentConfigRepository = koinInject()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }

    // 导出 agent/ 配置为 zip（SAF）
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null || exporting) return@rememberLauncherForActivityResult
        exporting = true
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    AgentConfigArchive.exportZip(repository.root, out)
                } ?: false
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                toaster.show(
                    if (ok) {
                        context.getString(R.string.agent_config_page_export_success)
                    } else {
                        context.getString(R.string.agent_config_page_export_failed)
                    },
                    type = if (ok) ToastType.Success else ToastType.Error,
                )
                exporting = false
            }
        }
    }

    // 导入 agent/ 配置 zip（SAF）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val count = runCatching {
                val tmp = File(context.cacheDir, "agent-import-${System.currentTimeMillis()}.zip")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                val imported = AgentConfigArchive.importZip(repository.root, tmp)
                tmp.delete()
                imported
            }.getOrDefault(-1)
            withContext(Dispatchers.Main) {
                if (count >= 0) {
                    toaster.show(
                        context.getString(R.string.agent_config_page_import_count, count),
                        type = ToastType.Success,
                    )
                    agentConfigVM.refresh()
                } else {
                    toaster.show(
                        context.getString(R.string.agent_config_page_import_failed),
                        type = ToastType.Error,
                    )
                }
            }
        }
    }

    val configView by agentConfigVM.view.collectAsStateWithLifecycle()
    val refreshing by agentConfigVM.refreshing.collectAsStateWithLifecycle()

    var tokenStats by remember { mutableStateOf(MessageTokenStats()) }
    var subTokenStats by remember { mutableStateOf(SubAgentTokenStats()) }

    LaunchedEffect(Unit) {
        tokenStats = messageNodeDAO.getTokenStats()
        subTokenStats = subAgentUsageDAO.getTokenStats()
    }

    val currentAssistant = settings.getCurrentAssistant()
    val modelCount = settings.providers.sumOf { it.models.size }
    val modeRef = currentAssistant.defaultMode ?: settings.defaultMode
    val modePolicy = resolveModePolicy(modeRef, settings) ?: ChatModePolicy.UNRESTRICTED
    val restricted = modePolicy.restrictedCapabilities(settings)
    val managementCapabilities = Capability.entries.filter {
        it.managementOnly && it in modePolicy.capabilities
    }
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
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        item("config") {
            ConfigFilesGroup(
                view = configView,
                modelCount = modelCount,
                refreshing = refreshing,
                onOpenFile = { path, title ->
                    navController.navigate(Screen.AgentConfigFile(path = path, title = title))
                },
                onRefresh = { agentConfigVM.refresh() },
                onExportConfig = {
                    exportLauncher.launch("agent-config-${System.currentTimeMillis()}.zip")
                },
                onImportConfig = {
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
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

        item("footer") {
            FooterDisplayGroup(
                settings = settings,
                onUpdateDisplaySetting = { display ->
                    vm.updateSettings(settings.copy(displaySetting = display))
                },
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

// ---------- 配置文件（按分类分组展示） ----------

@Composable
private fun ConfigFilesGroup(
    view: AgentConfigView,
    modelCount: Int,
    refreshing: Boolean,
    onOpenFile: (path: String, title: String?) -> Unit,
    onRefresh: () -> Unit,
    onExportConfig: () -> Unit,
    onImportConfig: () -> Unit,
) {
    val files = view.files

    // 尚未导出：给出空态 + 刷新入口
    if (files.isEmpty()) {
        IosGroup(
            modifier = Modifier.padding(horizontal = 8.dp),
            title = stringResource(R.string.agent_config_page_files),
        ) {
            item(
                headlineContent = { Text(stringResource(R.string.agent_config_page_not_exported)) },
                supportingContent = { Text(stringResource(R.string.agent_config_page_refresh_hint)) },
            )
            item(
                headlineContent = { Text(stringResource(R.string.agent_config_page_refresh)) },
                onClick = onRefresh,
                trailingContent = {
                    if (refreshing) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                },
            )
            item(
                headlineContent = { Text(stringResource(R.string.agent_config_page_import)) },
                supportingContent = { Text(stringResource(R.string.agent_config_page_import_desc)) },
                onClick = onImportConfig,
                trailingContent = { FileRowChevron() },
            )
        }
        return
    }

    val manifest = files.firstOrNull { it.category == AgentConfigFileCategory.MANIFEST }
    val providers = files.firstOrNull { it.category == AgentConfigFileCategory.PROVIDERS }
    val mcp = files.firstOrNull { it.category == AgentConfigFileCategory.MCP }
    val assistants = files
        .filter { it.category == AgentConfigFileCategory.ASSISTANT }
        // UUID 文件名排序无意义，按助手显示名排序（无名回退路径）
        .sortedBy { it.displayName?.takeIf { name -> name.isNotBlank() } ?: it.path }
    val others = files.filter { info ->
        info.category != AgentConfigFileCategory.MANIFEST &&
            info.category != AgentConfigFileCategory.PROVIDERS &&
            info.category != AgentConfigFileCategory.MCP &&
            info.category != AgentConfigFileCategory.ASSISTANT
    }

    // 导出状态 + 操作（清单 / 刷新 / 导出 / 导入 同一组，减少组数）
    IosGroup(
        modifier = Modifier.padding(horizontal = 8.dp),
        title = stringResource(R.string.agent_config_page_status),
    ) {
        if (manifest != null) {
            item(
                headlineContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FileStatusDot(manifest.status, manifest.dirty)
                        Text(stringResource(R.string.management_page_config_manifest))
                    }
                },
                supportingContent = {
                    Text(
                        text = buildString {
                            append("schema v${view.schemaVersion}")
                            view.source?.let { append(" · 来源 $it") }
                            view.settingsDataVersion?.let { append(" · 设置 v$it") }
                            view.exportedAt?.let { append(" · ${formatEpochMillis(it)}") }
                        }
                    )
                },
                onClick = { onOpenFile(manifest.path, null) },
                trailingContent = { FileRowChevron() },
            )
        }
        item(
            headlineContent = { Text(stringResource(R.string.agent_config_page_refresh)) },
            supportingContent = { Text(stringResource(R.string.agent_config_page_refresh_hint)) },
            onClick = onRefresh,
            trailingContent = {
                if (refreshing) CircularProgressIndicator(modifier = Modifier.size(20.dp))
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.agent_config_page_export)) },
            supportingContent = { Text(stringResource(R.string.agent_config_page_export_desc)) },
            onClick = onExportConfig,
            trailingContent = { FileRowChevron() },
        )
        item(
            headlineContent = { Text(stringResource(R.string.agent_config_page_import)) },
            supportingContent = { Text(stringResource(R.string.agent_config_page_import_desc)) },
            onClick = onImportConfig,
            trailingContent = { FileRowChevron() },
        )
    }

    // 提供商配置
    if (providers != null) {
        IosGroup(
            modifier = Modifier.padding(horizontal = 8.dp),
            title = stringResource(R.string.management_page_config_providers),
        ) {
            item(
                headlineContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FileStatusDot(providers.status, providers.dirty)
                        Text(
                            text = providers.path,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.management_page_config_providers_desc,
                            view.providerCount,
                            modelCount,
                            formatBytes(providers.bytes),
                        )
                    )
                },
                onClick = { onOpenFile(providers.path, null) },
                trailingContent = { FileRowChevron() },
            )
        }
    }

    // MCP 配置
    if (mcp != null) {
        IosGroup(
            modifier = Modifier.padding(horizontal = 8.dp),
            title = stringResource(R.string.management_page_config_mcp),
        ) {
            item(
                headlineContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FileStatusDot(mcp.status, mcp.dirty)
                        Text(
                            text = mcp.path,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                supportingContent = {
                    Text(
                        stringResource(
                            R.string.management_page_config_mcp_desc,
                            view.mcpServerCount,
                            formatBytes(mcp.bytes),
                        )
                    )
                },
                onClick = { onOpenFile(mcp.path, null) },
                trailingContent = { FileRowChevron() },
            )
        }
    }

    // 助手配置（文件名是 UUID，展示助手名）
    if (assistants.isNotEmpty()) {
        IosGroup(
            modifier = Modifier.padding(horizontal = 8.dp),
            title = stringResource(R.string.management_page_config_assistants, assistants.size),
        ) {
            assistants.forEach { info ->
                item(
                    headlineContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FileStatusDot(info.status, info.dirty)
                            Text(
                                text = info.displayName?.takeIf { it.isNotBlank() } ?: info.path,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    supportingContent = {
                        Text("${info.path} · ${formatBytes(info.bytes)}")
                    },
                    onClick = { onOpenFile(info.path, info.displayName) },
                    trailingContent = { FileRowChevron() },
                )
            }
        }
    }

    // 其他配置（policies / state / 未归类；backups 快照已在 Repository.view 排除）
    if (others.isNotEmpty()) {
        IosGroup(
            modifier = Modifier.padding(horizontal = 8.dp),
            title = stringResource(R.string.management_page_config_others, others.size),
        ) {
            others.forEach { info ->
                item(
                    headlineContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FileStatusDot(info.status, info.dirty)
                            Text(
                                text = info.path,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    supportingContent = { Text(formatBytes(info.bytes)) },
                    onClick = { onOpenFile(info.path, null) },
                    trailingContent = { FileRowChevron() },
                )
            }
        }
    }

    Text(
        text = stringResource(R.string.agent_config_page_masked_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun FileRowChevron() {
    Icon(
        imageVector = HugeIcons.ArrowRight01,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(16.dp),
    )
}

/** 文件状态圆点：ok=主色、error=错误色、untracked=弱化灰、dirty（导出后被手动改）=警示色。 */
@Composable
private fun FileStatusDot(status: String, dirty: Boolean = false) {
    val color = when {
        dirty -> MaterialTheme.colorScheme.tertiary
        status == "ok" -> MaterialTheme.colorScheme.primary
        status.startsWith("error") -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color = color, shape = CircleShape),
    )
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

private fun formatBytes(value: Int): String = when {
    value >= 1024 * 1024 -> "%.1f MB".format(value / 1024.0 / 1024.0)
    value >= 1024 -> "%.1f KB".format(value / 1024.0)
    else -> "$value B"
}

private fun formatEpochMillis(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
