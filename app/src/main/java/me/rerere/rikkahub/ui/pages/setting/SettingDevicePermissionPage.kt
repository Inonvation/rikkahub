package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.device.DEVICE_TOOL_LABELS
import me.rerere.rikkahub.data.device.DEVICE_TOOL_NAMES
import me.rerere.rikkahub.data.device.DeviceToolPermission
import me.rerere.rikkahub.data.device.PermissionLevel
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.SettingScaffold
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

private fun PermissionLevel.label(): String = when (this) {
    PermissionLevel.ALLOW -> "自动允许"
    PermissionLevel.ASK -> "每次询问"
    PermissionLevel.FORBID -> "禁止"
}

private fun PermissionLevel.next(): PermissionLevel = when (this) {
    PermissionLevel.ALLOW -> PermissionLevel.ASK
    PermissionLevel.ASK -> PermissionLevel.FORBID
    PermissionLevel.FORBID -> PermissionLevel.ALLOW
}

/**
 * 工具审批：全局默认 + 每个写工具单独设置（自动允许 / 每次询问 / 禁止）。
 */
@Composable
fun SettingDevicePermissionPage() {
    val permission: DeviceToolPermission = koinInject()
    val scope = rememberCoroutineScope()

    val master by permission.masterFlow.collectAsStateWithLifecycle(initialValue = PermissionLevel.ASK)
    val allLevels by permission.allLevels.collectAsStateWithLifecycle(initialValue = emptyMap())

    SettingScaffold(title = "工具审批") { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                IosGroup(title = "全局默认") {
                    item(
                        onClick = { scope.launch { permission.setMaster(master.next()) } },
                        supportingContent = { Text("未单独设置的工具使用此级别，点击切换") },
                        headlineContent = { Text("默认：${master.label()}") },
                        trailingContent = {
                            Text(
                                text = master.label(),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                    )
                }
            }
            item {
                IosGroup(title = "各工具设置") {
                    DEVICE_TOOL_NAMES.forEach { name ->
                        val level = allLevels[name] ?: master
                        item(
                            onClick = { scope.launch { permission.setTool(name, level.next()) } },
                            supportingContent = { Text("点击切换：自动允许 / 每次询问 / 禁止") },
                            headlineContent = { Text(DEVICE_TOOL_LABELS[name] ?: name) },
                            trailingContent = {
                                Text(
                                    text = level.label(),
                                    color = if (level == PermissionLevel.FORBID) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                )
                            },
                        )
                    }
                }
            }
            item {
                IosGroup(title = "说明") {
                    item(
                        headlineContent = { Text("自动允许：AI 直接执行，不再弹确认。每次询问：每次执行都弹确认（推荐）。禁止：AI 无法使用该工具。冻结、清理等写操作默认每次询问。") },
                    )
                }
            }
        }
    }
}