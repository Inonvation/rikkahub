package me.rerere.rikkahub.ui.pages.backup.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import me.rerere.rikkahub.utils.toLocalDateTime
import java.time.Instant

/**
 * 云同步设置 Tab（增量同步）。
 * 绑定 settings.syncConfig 与 SyncStateStore 状态。
 */
@Composable
fun SyncTab(vm: BackupVM) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val syncState by vm.syncState.collectAsStateWithLifecycle()
    val syncRunning by vm.syncRunning.collectAsStateWithLifecycle()
    val syncMessage by vm.syncMessage.collectAsStateWithLifecycle()
    val config = settings.syncConfig

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                    trailingContent = {
                        Switch(
                            checked = config.includeDatabase,
                            onCheckedChange = { enabled -> vm.updateSyncConfig { it.copy(includeDatabase = enabled) } },
                        )
                    },
                    headlineContent = { Text("同步聊天数据库") },
                )

                item(
                    trailingContent = {
                        Switch(
                            checked = config.includeFiles,
                            onCheckedChange = { enabled -> vm.updateSyncConfig { it.copy(includeFiles = enabled) } },
                        )
                    },
                    headlineContent = { Text("同步附件（上传/技能/字体）") },
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

            item {
                Button(
                    onClick = { vm.syncNow() },
                    enabled = config.enabled && !syncRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (syncRunning) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    }
                    Text(if (syncRunning) "同步中…" else "立即同步")
                }
            }
        }
    }
}
