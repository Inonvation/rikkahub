package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.management.ManagementAuditEntry
import me.rerere.rikkahub.data.management.ManagementAuditStore
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.SettingScaffold
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

@Composable
private fun resultColor(result: String) = when {
    result.startsWith("success") -> MaterialTheme.colorScheme.primary
    result == "forbidden" || result.startsWith("blocked") -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}

/**
 * 设备操作审计：展示设备工具的写操作记录（冻结/清理），用于安全追溯。
 */
@Composable
fun SettingDeviceAuditPage() {
    val auditStore: ManagementAuditStore = koinInject()
    val entries by auditStore.entries.collectAsStateWithLifecycle()

    SettingScaffold(title = "操作记录") { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (entries.isEmpty()) {
                item {
                    IosGroup(title = "记录") {
                        item(headlineContent = { Text("暂无设备操作记录") })
                    }
                }
            } else {
                entries.forEach { entry: ManagementAuditEntry ->
                    item {
                        IosGroup(title = formatTime(entry.timestamp)) {
                            item(
                                supportingContent = { Text(entry.detail.ifBlank { "无详情" }) },
                                headlineContent = { Text(entry.tool) },
                            )
                            item(
                                leadingContent = {
                                    Text(
                                        text = entry.result,
                                        color = resultColor(entry.result),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                },
                                headlineContent = { Text(entry.target.ifBlank { "-" }) },
                            )
                        }
                    }
                }
            }
        }
    }
}