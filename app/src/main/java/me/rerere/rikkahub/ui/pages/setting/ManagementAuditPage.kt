package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.management.ManagementAuditStore
import me.rerere.rikkahub.ui.components.ui.IosGroup
import me.rerere.rikkahub.ui.components.ui.SettingListScaffold
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 管理审计二级页：管理操作记录（成功 / 权限拦截 / 失败）筛选与全量展示，
 * 配套管理操作审计的完整追溯；从管理控制台抽出，避免占用控制台主界面。
 */
@Composable
fun ManagementAuditPage() {
    val auditStore: ManagementAuditStore = koinInject()
    val entries by auditStore.entries.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(AuditFilter.ALL) }
    var expandedTimestamp by remember { mutableStateOf<Long?>(null) }
    val filtered = remember(entries, filter) { entries.filter { filter.matches(it.result) } }
    val formatter = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    SettingListScaffold(
        title = stringResource(R.string.setting_page_console_audit),
        loading = false,
    ) {
        item("filters") {
            FlowRow(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
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
        }
        if (filtered.isEmpty()) {
            item("empty") {
                IosGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    item(headlineContent = { Text(stringResource(R.string.setting_page_console_no_audit)) })
                }
            }
        } else {
            // 每条记录独立为一个 LazyColumn item：条目上百后仍按需组合，避免整组一次性渲染
            filtered.forEach { entry ->
                item("entry_${entry.timestamp}") {
                    IosGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
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
        }
    }
}

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