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
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.hugeicons.stroke.UserGroup
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.DiscussionMode
import me.rerere.rikkahub.data.model.Group
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel

/**
 * 群组中心页：右侧栏「AI 群组」单入口进入。
 * 顶部新建卡片 + 已建群组列表（点击进群组详情、编辑进编辑页、删除带确认）。
 */
@Composable
fun GroupDiscussionListPage(
    vm: GroupDiscussionListVM = koinViewModel(),
) {
    val navController = LocalNavController.current
    val groups by vm.groups.collectAsStateWithLifecycle()
    var groupToDelete by remember { mutableStateOf<Group?>(null) }

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
                    text = "群组中心",
                    style = MaterialTheme.typography.titleLarge,
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
            // 新建群组
            item {
                Surface(
                    onClick = { navController.navigate(Screen.GroupDiscussionCreate) },
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
                            imageVector = HugeIcons.PlusSign,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Column {
                            Text(
                                text = "新建群组讨论",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "把多个助手拉进群聊",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }

            if (groups.isEmpty()) {
                item {
                    Text(
                        text = "还没有群组，点上方新建一个。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }

            items(groups, key = { it.id.toString() }) { group ->
                DiscussionGroupCard(
                    group = group,
                    onClick = { navController.navigate(Screen.GroupDetail(group.id.toString())) },
                    onEdit = { navController.navigate(Screen.GroupDiscussionEdit(group.id.toString())) },
                    onDelete = { groupToDelete = group },
                )
            }
        }
    }

    groupToDelete?.let { group ->
        AlertDialog(
            onDismissRequest = { groupToDelete = null },
            title = { Text("删除群组") },
            text = { Text("确定删除「${group.name.ifEmpty { "未命名群组" }}」吗？其下所有对话记录将一并删除，不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteGroup(group)
                        groupToDelete = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { groupToDelete = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun DiscussionGroupCard(
    group: Group,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val config = group.config
    val members = config?.enabledMembers.orEmpty()
    val modeText = when (config?.mode) {
        DiscussionMode.ROUND_ROBIN -> "轮流"
        DiscussionMode.ROUND_ROBIN_THEN_SUMMARY -> "轮流+收束"
        DiscussionMode.SELECTOR -> "主持人调度"
        null -> ""
    }

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 成员头像（最多前 4 位）
            if (members.isEmpty()) {
                Icon(
                    imageVector = HugeIcons.UserGroup,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp),
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    members.take(4).forEach { member ->
                        UIAvatar(
                            name = member.name,
                            value = member.avatar,
                            loading = false,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.name.ifEmpty { "未命名群组" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        members.joinToString("、") { it.name }.take(20).ifBlank { null },
                        modeText.ifBlank { null },
                        if (config != null) "${members.size} 人 · ${config.rounds} 轮" else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = HugeIcons.Edit01,
                    contentDescription = "编辑群组",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = "删除群组",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
