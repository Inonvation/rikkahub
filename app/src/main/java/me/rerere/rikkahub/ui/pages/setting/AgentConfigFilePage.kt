package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.config.AgentBackupInfo
import me.rerere.rikkahub.data.config.AgentConfigImporter
import me.rerere.rikkahub.data.config.AgentConfigPaths
import me.rerere.rikkahub.data.config.AgentConfigRepository
import me.rerere.rikkahub.data.config.AgentConfigRevision
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.richtext.MarkdownPreviewSwitcher
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * agent/ 配置文件页（管理控制台）。
 *
 * 默认只读预览：全屏打开、复用 [MarkdownPreviewSwitcher]，JSON 文件额外提供「结构」树状预览。
 * 额外能力：
 * - 「编辑」切换源码可编辑态，「保存」走 [AgentConfigRepository.writeConfigFile]
 *   （原子写 + 快照到 backups/ + revisions.json 记录）；JSON 保存前做语法校验；
 * - 「应用到设置」把该配置文件合并回 DataStore（[AgentConfigImporter]，密钥保留本地）；
 * - 「历史」：查看 backups/ 快照与修订记录，一键回退到任意快照；
 * - 脏标记横幅：文件在最近一次导出后被手动修改时提示（设置变更会自动重新导出覆盖）。
 * 内容经 [AgentConfigRepository.readConfigFile] 白名单读取，杜绝越权/穿越。
 *
 * @param path 相对 agent/ 的配置文件路径（如 config/providers.json）
 * @param title 顶栏展示名；为 null 时回退为文件名（助手配置文件由入口传助手名）
 */
@Composable
fun AgentConfigFilePage(
    path: String,
    title: String? = null,
) {
    val repository = koinInject<AgentConfigRepository>()
    val settingsStore = koinInject<SettingsStore>()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fileName = path.substringAfterLast('/').ifBlank { path }
    // JSON 文件启用「结构」树状预览模式（与工作区文件编辑页一致）
    val isJson = fileName.substringAfterLast('.', "").lowercase() == "json"
    // 是否为可「应用到设置」的配置类文件
    val isAppliable = path == AgentConfigPaths.PROVIDERS_FILE ||
        path == AgentConfigPaths.MCP_FILE ||
        path.startsWith("${AgentConfigPaths.ASSISTANTS_DIR}/")

    val textState = rememberTextFieldState()
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var editable by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<AgentBackupInfo?>(null) }
    var backups by remember { mutableStateOf<List<AgentBackupInfo>>(emptyList()) }
    var revisions by remember { mutableStateOf<List<AgentConfigRevision>>(emptyList()) }

    fun loadBackupMeta() {
        backups = repository.listBackups(path)
        revisions = repository.revisions().filter { it.path == path }
        dirty = repository.isFileDirty(path)
    }

    LaunchedEffect(path) {
        loading = true
        loadError = null
        runCatching { repository.readConfigFile(path) }
            .onSuccess { content ->
                if (content != null) {
                    textState.setTextAndPlaceCursorAtEnd(content)
                    loadBackupMeta()
                } else {
                    loadError = "文件不存在或无权访问"
                }
                loading = false
            }
            .onFailure {
                loadError = it.message ?: "读取文件失败"
                loading = false
            }
    }

    fun save() {
        if (saving) return
        val content = textState.text.toString()
        // JSON 文件保存前做语法校验，避免手滑保存坏文件（会破坏 config_validate 一致性）
        if (isJson) {
            runCatching { Json.parseToJsonElement(content) }
                .onFailure {
                    toaster.show("JSON 格式错误：${it.message}", type = ToastType.Error)
                    return
                }
        }
        saving = true
        scope.launch {
            runCatching { repository.writeConfigFile(path, content) }
                .onSuccess { error ->
                    if (error == null) {
                        toaster.show("已保存", type = ToastType.Success)
                        loadBackupMeta()
                    } else {
                        toaster.show(error, type = ToastType.Error)
                    }
                }
                .onFailure {
                    toaster.show(it.message ?: "保存失败", type = ToastType.Error)
                }
            saving = false
        }
    }

    fun applyToSettings() {
        if (applying || !isAppliable) return
        applying = true
        scope.launch {
            runCatching {
                val current = settingsStore.settingsFlow.value
                val updated = when {
                    path == AgentConfigPaths.PROVIDERS_FILE ->
                        AgentConfigImporter.applyProviders(current, repository.root)
                    path == AgentConfigPaths.MCP_FILE ->
                        AgentConfigImporter.applyMcpServers(current, repository.root)
                    else -> {
                        // 单文件语义：只应用当前助手文件，避免同目录其他助手被整目录覆盖
                        val assistantId = path.substringAfterLast('/').removeSuffix(".json")
                        AgentConfigImporter.applyAssistants(
                            current,
                            repository.root,
                            onlyAssistantId = assistantId,
                        )
                    }
                }
                if (updated !== current) {
                    settingsStore.update(updated)
                    "已应用到设置"
                } else {
                    "此文件不支持应用到设置"
                }
            }.onSuccess {
                toaster.show(it, type = ToastType.Success)
                // 应用后设置变更会触发 AutoSync 重新导出，脏标记随之清除
                loadBackupMeta()
            }.onFailure { toaster.show(it.message ?: "应用失败", type = ToastType.Error) }
            applying = false
        }
    }

    fun restoreBackup(backup: AgentBackupInfo) {
        scope.launch {
            val content = withContext(Dispatchers.IO) { repository.readBackup(backup.name) }
            if (content == null) {
                toaster.show(
                    context.getString(R.string.agent_config_page_restore_failed),
                    type = ToastType.Error,
                )
                return@launch
            }
            val error = repository.writeConfigFile(path, content)
            if (error == null) {
                toaster.show(
                    context.getString(R.string.agent_config_page_restored),
                    type = ToastType.Success,
                )
                textState.setTextAndPlaceCursorAtEnd(content)
                loadBackupMeta()
            } else {
                toaster.show(error, type = ToastType.Error)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = title ?: fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    if (isAppliable && !editable) {
                        TextButton(onClick = { applyToSettings() }, enabled = !applying) {
                            Text(stringResource(R.string.agent_config_page_apply))
                        }
                    }
                    if (!editable) {
                        TextButton(onClick = { historyOpen = true }) {
                            Text(stringResource(R.string.agent_config_page_history))
                        }
                    }
                    if (editable) {
                        TextButton(onClick = { save() }, enabled = !saving) {
                            Text(
                                if (saving) {
                                    stringResource(R.string.agent_config_page_saving)
                                } else {
                                    stringResource(R.string.agent_config_page_save)
                                }
                            )
                        }
                    }
                    TextButton(onClick = { editable = !editable }) {
                        Text(
                            if (editable) {
                                stringResource(R.string.agent_config_page_done)
                            } else {
                                stringResource(R.string.agent_config_page_edit)
                            }
                        )
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        when {
            loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            loadError != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
            ) {
                Text(
                    text = loadError ?: "",
                    color = MaterialTheme.colorScheme.error,
                )
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                // 脏标记横幅：文件在导出后被手动修改，设置变更会自动重新导出覆盖
                if (dirty) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                text = stringResource(R.string.agent_config_page_dirty_banner),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isAppliable) {
                                    TextButton(onClick = { applyToSettings() }, enabled = !applying) {
                                        Text(stringResource(R.string.agent_config_page_apply))
                                    }
                                }
                                TextButton(onClick = { historyOpen = true }) {
                                    Text(stringResource(R.string.agent_config_page_restore))
                                }
                            }
                        }
                    }
                }
                MarkdownPreviewSwitcher(
                    state = textState,
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding(),
                    sourceEditable = editable,
                    jsonStructure = isJson,
                )
            }
        }
    }

    // 修订历史 / 备份快照对话框
    if (historyOpen) {
        AlertDialog(
            onDismissRequest = { historyOpen = false },
            title = { Text(stringResource(R.string.agent_config_page_history)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.agent_config_page_backups),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (backups.isEmpty()) {
                        Text(
                            text = stringResource(R.string.agent_config_page_no_backups),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        backups.forEach { backup ->
                            TextButton(
                                onClick = {
                                    historyOpen = false
                                    pendingRestore = backup
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = "${formatEpochMillis(backup.at)} · ${backup.size} B",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = stringResource(R.string.agent_config_page_revisions),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (revisions.isEmpty()) {
                        Text(
                            text = stringResource(R.string.agent_config_page_no_revisions),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        revisions.forEach { rev ->
                            Text(
                                text = "${formatEpochMillis(rev.at)} · ${rev.size} B",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { historyOpen = false }) {
                    Text(stringResource(R.string.agent_config_page_close))
                }
            },
        )
    }

    // 回退确认对话框
    pendingRestore?.let { backup ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(stringResource(R.string.agent_config_page_restore_confirm_title)) },
            text = { Text(stringResource(R.string.agent_config_page_restore_confirm_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    restoreBackup(backup)
                    pendingRestore = null
                }) {
                    Text(stringResource(R.string.agent_config_page_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) {
                    Text(stringResource(R.string.agent_config_page_cancel))
                }
            },
        )
    }
}

private fun formatEpochMillis(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
