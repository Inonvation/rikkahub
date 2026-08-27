package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.config.AgentConfigArchive
import me.rerere.rikkahub.data.config.AgentConfigFileCategory
import me.rerere.rikkahub.data.config.AgentConfigRepository
import me.rerere.rikkahub.data.config.AgentConfigView
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.SettingListScaffold
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 配置文件二级页：agent/ 导出文件按分类（导出状态 / 提供商 / MCP / 助手 / 其他）分组展示，
 * 点击条目以工作区同款方式打开只读预览；支持刷新、导出与导入 zip。
 * 从管理控制台抽出，避免助手等配置文件过多时占满控制台主界面。
 */
@Composable
fun ConfigFilesPage(
    agentConfigVM: AgentConfigVM = koinViewModel(),
) {
    val settingsVM: SettingVM = koinViewModel()
    val settings by settingsVM.settings.collectAsStateWithLifecycle()
    val configView by agentConfigVM.view.collectAsStateWithLifecycle()
    val refreshing by agentConfigVM.refreshing.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val context = LocalContext.current
    val repository: AgentConfigRepository = koinInject()
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    val modelCount = settings.providers.sumOf { it.models.size }

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

    SettingListScaffold(
        title = stringResource(R.string.agent_config_page_files),
        loading = false,
    ) {
        item("files") {
            ConfigFilesGroup(
                view = configView,
                modelCount = modelCount,
                refreshing = refreshing,
                onOpenFile = { path, title ->
                    navController.navigate(Screen.AgentConfigFile(path = path, title = title))
                },
                onRefresh = { agentConfigVM.refresh() },
                onExportConfig = { exportLauncher.launch("agent-config-${System.currentTimeMillis()}.zip") },
                onImportConfig = {
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                },
            )
        }
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

    // 导出状态 + 操作（清单 / 刷新 / 导出 / 导入同一组，减少组数）
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

/** 行尾右向箭头：配置入口 / 文件行通用。 */
@Composable
internal fun FileRowChevron() {
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

private fun formatBytes(value: Int): String = when {
    value >= 1024 * 1024 -> "%.1f MB".format(value / 1024.0 / 1024.0)
    value >= 1024 -> "%.1f KB".format(value / 1024.0)
    else -> "$value B"
}

private fun formatEpochMillis(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))