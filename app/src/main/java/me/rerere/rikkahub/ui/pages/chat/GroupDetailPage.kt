package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.MessageAdd01
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.hugeicons.stroke.UserGroup
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.DiscussionMode
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.utils.toMessageTimeString
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.Instant

/** 历史对话的最近更新时间：当天显示时间，非当天显示日期时间 */
private fun formatRelativeTime(instant: Instant): String {
    return instant.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toMessageTimeString()
}

/**
 * 群组详情页：群组信息头部 + 「新建对话」+ 会话历史列表。
 * 从群组中心点击群组进入；点击历史会话进入对应讨论页。
 */
@Composable
fun GroupDetailPage(
    id: String,
    vm: GroupDetailVM = koinViewModel(
        parameters = { parametersOf(id) }
    ),
) {
    val navController = LocalNavController.current
    val group by vm.group.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    var convToDelete by remember { mutableStateOf<Conversation?>(null) }

    val config = group?.config
    val members = config?.enabledMembers.orEmpty()
    val modeText = when (config?.mode) {
        DiscussionMode.ROUND_ROBIN -> "轮流"
        DiscussionMode.ROUND_ROBIN_THEN_SUMMARY -> "轮流+收束"
        DiscussionMode.SELECTOR -> "主持人调度"
        null -> ""
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BackButton()
                Text(
                    text = group?.name?.ifEmpty { "群组" } ?: "群组",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 群组信息卡片
            item {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (members.isEmpty()) {
                            Icon(
                                imageVector = HugeIcons.UserGroup,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(36.dp),
                            )
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                members.take(4).forEach { member ->
                                    UIAvatar(
                                        name = member.name,
                                        value = member.avatar,
                                        loading = false,
                                        modifier = Modifier.size(30.dp),
                                    )
                                }
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = group?.name?.ifEmpty { "未命名群组" } ?: "未命名群组",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = listOfNotNull(
                                    members.joinToString("、") { it.name }.take(24).ifBlank { null },
                                    modeText.ifBlank { null },
                                    if (config != null) "${members.size} 人 · ${config.rounds} 轮" else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = {
                            navController.navigate(Screen.GroupDiscussionEdit(group!!.id.toString()))
                        }) {
                            Icon(
                                imageVector = HugeIcons.Edit01,
                                contentDescription = "编辑群组",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            // 新建对话按钮
            item {
                Surface(
                    onClick = {
                        vm.createNewConversation { convId ->
                            navController.navigate(Screen.GroupDiscussion(convId.toString()))
                        }
                    },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.MessageAdd01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Column {
                            Text("新建对话", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "开一场新的讨论，不打断现有历史",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }

            item {
                HorizontalDivider()
            }

            item {
                Text(
                    text = "历史对话（${conversations.size}）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (conversations.isEmpty()) {
                item {
                    Text(
                        text = "还没有对话，点上方「新建对话」开一场。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            items(conversations, key = { it.id.toString() }) { conv ->
                Surface(
                    onClick = { navController.navigate(Screen.GroupDiscussion(conv.id.toString())) },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = conv.title.ifEmpty { "未命名对话" },
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // 标题为空 = 尚未发过首个话题（空会话）；否则按最近更新时间展示
                            Text(
                                text = if (conv.title.isBlank()) {
                                    "尚未开始"
                                } else {
                                    "最近更新 ${formatRelativeTime(conv.updateAt)}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { convToDelete = conv }) {
                            Icon(
                                imageVector = HugeIcons.Delete01,
                                contentDescription = "删除对话",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    convToDelete?.let { conv ->
        AlertDialog(
            onDismissRequest = { convToDelete = null },
            title = { Text("删除对话") },
            text = { Text("确定删除「${conv.title.ifEmpty { "未命名对话" }}」吗？该对话记录不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteConversation(conv)
                        convToDelete = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { convToDelete = null }) {
                    Text("取消")
                }
            },
        )
    }
}
