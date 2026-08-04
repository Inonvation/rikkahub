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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.stroke.ArrowLeft02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.DiscussionMode
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel

@Composable
fun GroupDiscussionCreatePage(
    vm: GroupDiscussionCreateVM = koinViewModel(),
) {
    val navController = LocalNavController.current
    val allAssistants by vm.allAssistants.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val rounds by vm.rounds.collectAsStateWithLifecycle()
    val name by vm.name.collectAsStateWithLifecycle()
    val creating by vm.creating.collectAsStateWithLifecycle()

    // 创建成功后跳转到独立讨论页（不自动开始，进页输入主题才启动）。
    // 创建表单用完即弃：从讨论页返回时直接回群组中心，不退回创建表单。
    vm.onCreated = { conversationId ->
        navController.navigate(Screen.GroupDiscussion(conversationId.toString())) {
            popUpTo(Screen.GroupDiscussionCreate) { inclusive = true }
        }
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
                    text = "新建群组讨论",
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 群名
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("群组名称", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = name,
                        onValueChange = vm::setName,
                        placeholder = { Text("给这个群取个名字") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }

            // 成员选择
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "选择成员（至少 2 个，勾选顺序即发言顺序）",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (allAssistants.isEmpty()) {
                        Text(
                            "还没有可用的助手，请先到助手页面创建一个。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(allAssistants, key = { it.id.toString() }) { assistant ->
                val isSelected = selected.contains(assistant.id)
                Surface(
                    onClick = { vm.toggleMember(assistant.id) },
                    shape = MaterialTheme.shapes.medium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        UIAvatar(
                            name = assistant.name,
                            modifier = Modifier.size(28.dp),
                            value = assistant.avatar,
                            loading = false,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = assistant.name,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = assistant.systemPrompt.take(40).ifEmpty { "无自定义提示词" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                        )
                    }
                }
            }

            // 发言模式
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("发言模式", style = MaterialTheme.typography.titleSmall)
                    listOf(
                        DiscussionMode.ROUND_ROBIN to "轮流发言",
                        DiscussionMode.ROUND_ROBIN_THEN_SUMMARY to "轮流发言 + 主持人收束",
                        DiscussionMode.SELECTOR to "AI 主持人调度",
                    ).forEach { (m, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(
                                selected = mode == m,
                                onClick = { vm.setMode(m) },
                            )
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            // 轮数
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("每名成员发言轮数：$rounds", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { vm.setRounds(rounds - 1) }, enabled = rounds > 1) {
                            Text("−")
                        }
                        TextButton(onClick = { vm.setRounds(rounds + 1) }) {
                            Text("＋")
                        }
                    }
                }
            }

            item {
                HorizontalDivider()
            }

            // 创建
            item {
                Button(
                    onClick = vm::create,
                    enabled = selected.size >= 2 && name.isNotBlank() && !creating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (creating) "创建中…" else "创建群组")
                }
            }
        }
    }
}
