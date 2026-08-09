package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.sync.SyncConfig
import me.rerere.rikkahub.data.sync.SyncProviderKind
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.pages.backup.BackupVM
import me.rerere.rikkahub.ui.pages.backup.components.SyncPreviewDialog
import me.rerere.rikkahub.ui.pages.backup.components.SyncProgressDialog
import me.rerere.rikkahub.utils.toLocalDateTime
import java.time.Instant

/**
 * 数据范围子项的层级缩进。
 * 只缩进卡片内部文字，卡片本身保持全宽，避免卡片左侧露白。
 */
private val SyncScopeItemIndent = 16.dp

/**
 * 云同步设置 Tab（增量同步）。
 * 绑定 settings.syncConfig 与 SyncStateStore 状态。
 * 布局：上部分可滚动设置/状态卡片，底部为固定「立即同步」冻结栏。
 */
@Composable
fun SyncTab(vm: BackupVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val syncState by vm.syncState.collectAsStateWithLifecycle()
    val syncRunning by vm.syncRunning.collectAsStateWithLifecycle()
    val syncMessage by vm.syncMessage.collectAsStateWithLifecycle()
    val syncPreview by vm.syncPreview.collectAsStateWithLifecycle()
    val syncProgress by vm.syncProgress.collectAsStateWithLifecycle()
    val config = settings.syncConfig

    val enabledScopeCount = listOf(
        config.includeSettings,
        config.includeDatabase,
        config.includeChatFiles,
        config.includeSkills,
        config.includeFonts,
        config.includeConversations,
    ).count { it }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CardGroup(modifier = Modifier.fillMaxWidth()) {
                item(
                    trailingContent = {
                        Switch(
                            checked = config.enabled,
                            onCheckedChange = { enabled -> vm.updateSyncConfig { it.copy(enabled = enabled) } },
                        )
                    },
                    headlineContent = { Text("启用增量同步") },
                    supportingContent = {
                        Text("跨设备同步设置、数据库与附件（与整包备份互不干扰）")
                    },
                )

                if (config.enabled) {
                    item(
                        headlineContent = { Text("后端类型") },
                        supportingContent = {
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                val kinds = listOf(SyncProviderKind.WEBDAV, SyncProviderKind.S3)
                                kinds.forEachIndexed { index, kind ->
                                    SegmentedButton(
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = kinds.size,
                                        ),
                                        onClick = { vm.updateSyncConfig { it.copy(provider = kind) } },
                                        selected = config.provider == kind,
                                    ) {
                                        Text(if (kind == SyncProviderKind.WEBDAV) "WebDAV" else "S3")
                                    }
                                }
                            }
                        },
                    )

                    item(
                        headlineContent = { Text("自动同步周期") },
                        supportingContent = {
                            val intervals = listOf(6, 12, 24)
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                intervals.forEachIndexed { index, hours ->
                                    SegmentedButton(
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = intervals.size,
                                        ),
                                        onClick = { vm.updateSyncConfig { it.copy(intervalHours = hours) } },
                                        selected = config.intervalHours == hours,
                                    ) {
                                        Text("$hours 小时")
                                    }
                                }
                            }
                        },
                    )

                    item(
                        headlineContent = { Text("同步数据范围") },
                        supportingContent = { Text("勾选参与同步的数据类型") },
                        trailingContent = {
                            Text("已选 $enabledScopeCount/6", style = MaterialTheme.typography.bodyMedium)
                        },
                    )

                    item(
                        trailingContent = {
                            Checkbox(
                                checked = config.includeSettings,
                                onCheckedChange = { enabled -> vm.updateSyncConfig { it.copy(includeSettings = enabled) } },
                            )
                        },
                        headlineContent = {
                            Text("设置", modifier = Modifier.padding(start = SyncScopeItemIndent))
                        },
                        supportingContent = {
                            Text(
                                text = "应用配置（白名单，不含密钥）",
                                modifier = Modifier.padding(start = SyncScopeItemIndent),
                            )
                        },
                    )

                    item(
                        trailingContent = {
                            Checkbox(
                                checked = config.includeDatabase,
                                enabled = !config.includeConversations,
                                onCheckedChange = { enabled -> vm.updateSyncConfig { it.copy(includeDatabase = enabled) } },
                            )
                        },
                        headlineContent = {
                            Text("聊天记录（整库）", modifier = Modifier.padding(start = SyncScopeItemIndent))
                        },
                        supportingContent = {
                            Text(
                                "聊天数据库，整库体积大；开启聊天增量后自动跳过",
                                modifier = Modifier.padding(start = SyncScopeItemIndent),
                            )
                        },
                    )

                    item(
                        trailingContent = {
                            Checkbox(
                                checked = config.includeConversations,
                                onCheckedChange = { enabled ->
                                    // 开启增量同步时自动接管聊天记录（关闭整库，避免重复全量重传）
                                    vm.updateSyncConfig {
                                        if (enabled) it.copy(includeConversations = true, includeDatabase = false)
                                        else it.copy(includeConversations = false)
                                    }
                                },
                            )
                        },
                        headlineContent = {
                            Text("聊天增量同步", modifier = Modifier.padding(start = SyncScopeItemIndent))
                        },
                        supportingContent = {
                            Text(
                                "按会话拆分，发消息只传该会话；开启后自动接管聊天记录",
                                modifier = Modifier.padding(start = SyncScopeItemIndent),
                            )
                        },
                    )

                    item(
                        trailingContent = {
                            Checkbox(
                                checked = config.includeChatFiles,
                                onCheckedChange = { enabled -> vm.updateSyncConfig { it.copy(includeChatFiles = enabled) } },
                            )
                        },
                        headlineContent = {
                            Text("聊天附件", modifier = Modifier.padding(start = SyncScopeItemIndent))
                        },
                        supportingContent = {
                            Text("上传的图片、文档等", modifier = Modifier.padding(start = SyncScopeItemIndent))
                        },
                    )

                    item(
                        trailingContent = {
                            Checkbox(
                                checked = config.includeSkills,
                                onCheckedChange = { enabled -> vm.updateSyncConfig { it.copy(includeSkills = enabled) } },
                            )
                        },
                        headlineContent = {
                            Text("技能文件", modifier = Modifier.padding(start = SyncScopeItemIndent))
                        },
                        supportingContent = {
                            Text("skills 目录（可能较大）", modifier = Modifier.padding(start = SyncScopeItemIndent))
                        },
                    )

                    item(
                        trailingContent = {
                            Checkbox(
                                checked = config.includeFonts,
                                onCheckedChange = { enabled -> vm.updateSyncConfig { it.copy(includeFonts = enabled) } },
                            )
                        },
                        headlineContent = {
                            Text("字体", modifier = Modifier.padding(start = SyncScopeItemIndent))
                        },
                        supportingContent = {
                            Text("fonts 目录", modifier = Modifier.padding(start = SyncScopeItemIndent))
                        },
                    )
                }
            }

            CardGroup(modifier = Modifier.fillMaxWidth()) {
                item(
                    headlineContent = {
                        Column {
                            Text("同步状态")
                            Text(
                                text = if (syncState.lastSyncTime == 0L) {
                                    "尚未同步"
                                } else {
                                    "上次同步：${Instant.ofEpochMilli(syncState.lastSyncTime).toLocalDateTime()}"
                                },
                            )
                        }
                    },
                    supportingContent = {
                        Column {
                            if (syncState.pendingSync) {
                                Text("存在待处理的同步（上次离线未完成）")
                            }
                            if (syncState.dbRestorePending != null) {
                                Text("数据库待应用，重启后生效")
                            }
                            syncMessage?.let { Text(it) }
                        }
                    },
                )
            }
        }

        // 底部冻结栏：无论内容区滚到哪，同步按钮始终可见
        HorizontalDivider()
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Button(
                onClick = { vm.syncNow() },
                enabled = config.enabled && !syncRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (syncRunning) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                }
                Text(if (syncRunning) "同步中…" else "立即同步")
            }
        }
    }

    syncPreview?.let { preview ->
        SyncPreviewDialog(
            preview = preview,
            onConfirm = { vm.confirmSync() },
            onDismiss = { vm.dismissSyncPreview() },
        )
    }
    syncProgress?.let { progress ->
        SyncProgressDialog(
            progress = progress,
            onCancel = { vm.cancelSync() },
            onDismiss = { vm.dismissSyncProgress() },
        )
    }
}
