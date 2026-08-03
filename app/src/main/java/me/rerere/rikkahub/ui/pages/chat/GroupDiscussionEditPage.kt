package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown02
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel02
import me.rerere.hugeicons.stroke.MinusSign
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.DiscussionMember
import me.rerere.rikkahub.data.model.DiscussionMode
import me.rerere.rikkahub.data.model.MemberStyle
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * 群组编辑页：右上角「编辑」或群组中心页「编辑」进入。
 * 可改群名 / 增删成员 / 调整顺序 / 每成员风格+参数 / 模式 / 轮数 / 总结指令。
 * 保存后写回 DiscussionConfig；运行中保存会先自动暂停。
 */
@Composable
fun GroupDiscussionEditPage(
    id: String,
    vm: GroupDiscussionEditVM = koinViewModel(
        parameters = { parametersOf(id) }
    ),
) {
    val navController = LocalNavController.current
    val name by vm.name.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val rounds by vm.rounds.collectAsStateWithLifecycle()
    val summaryPrompt by vm.summaryPrompt.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val allAssistants by vm.allAssistants.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()

    var showAddSheet by remember { mutableStateOf(false) }
    var editingMemberIndex by remember { mutableStateOf<Int?>(null) }

    vm.onSaved = { navController.popBackStack() }

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
                    text = "编辑群组",
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

            // 成员
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "成员（点成员卡片可调风格与参数）",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showAddSheet = true }) {
                        Icon(HugeIcons.PlusSign, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加成员")
                    }
                }
            }
            itemsIndexed(members) { index, member ->
                EditMemberCard(
                    index = index,
                    member = member,
                    total = members.size,
                    onMoveUp = { vm.moveMember(index, -1) },
                    onMoveDown = { vm.moveMember(index, 1) },
                    onRemove = { vm.removeMember(index) },
                    onClick = { editingMemberIndex = index },
                )
            }
            if (members.size < 2) {
                item {
                    Text(
                        "至少保留 2 名成员",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
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
                        IconButton(
                            onClick = { vm.setRounds(rounds - 1) },
                            enabled = rounds > 1,
                        ) {
                            Icon(
                                imageVector = HugeIcons.MinusSign,
                                contentDescription = "减少轮数",
                                tint = if (rounds > 1) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                        }
                        IconButton(
                            onClick = { vm.setRounds(rounds + 1) },
                        ) {
                            Icon(
                                imageVector = HugeIcons.PlusSign,
                                contentDescription = "增加轮数",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            // 收束模式总结指令
            if (mode == DiscussionMode.ROUND_ROBIN_THEN_SUMMARY) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("总结指令（可选）", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = summaryPrompt,
                            onValueChange = vm::setSummaryPrompt,
                            placeholder = { Text("给收束主持人的额外要求") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                        )
                    }
                }
            }

            item {
                HorizontalDivider()
            }

            // 保存
            item {
                Button(
                    onClick = vm::save,
                    enabled = members.size >= 2 && name.isNotBlank() && !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (saving) "保存中…" else "保存")
                }
            }
            item {
                Text(
                    "讨论运行中保存会先自动暂停，点继续后按新配置跑。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // 添加成员 Bottom Sheet
    if (showAddSheet) {
        ModalBottomSheet(onDismissRequest = { showAddSheet = false }) {
            val existingIds = members.map { it.assistantId }.toSet()
            val candidates = allAssistants.filterNot { existingIds.contains(it.id) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "添加成员",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                if (candidates.isEmpty()) {
                    Text(
                        text = "没有可添加的助手了（所有助手都已在群组中）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
                candidates.forEach { assistant ->
                    Surface(
                        onClick = {
                            vm.addMember(assistant)
                            showAddSheet = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            UIAvatar(
                                name = assistant.name,
                                modifier = Modifier.size(28.dp),
                                value = assistant.avatar,
                                loading = false,
                            )
                            Column {
                                Text(assistant.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = assistant.systemPrompt.take(40).ifEmpty { "无自定义提示词" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 成员详情 Bottom Sheet（风格 + 温度 + maxTokens）
    val editingIndex = editingMemberIndex
    if (editingIndex != null && editingIndex in members.indices) {
        val member = members[editingIndex]
        MemberSettingsSheet(
            member = member,
            onDismiss = { editingMemberIndex = null },
            onUpdateStyle = { style ->
                vm.updateMember(editingIndex) { it.copy(style = style) }
            },
            onUpdateTemperature = { t ->
                vm.updateMember(editingIndex) { it.copy(temperature = if (t < 0f) null else t) }
            },
            onUpdateMaxTokens = { maxTokens ->
                vm.updateMember(editingIndex) { it.copy(maxTokens = if (maxTokens <= 0) null else maxTokens) }
            },
        )
    }
}

@Composable
private fun EditMemberCard(
    index: Int,
    member: DiscussionMember,
    total: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(16.dp),
            )
            UIAvatar(
                name = member.name,
                modifier = Modifier.size(28.dp),
                value = member.avatar,
                loading = false,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(member.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = styleLabel(member.style),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onMoveUp, enabled = index > 0) {
                Icon(
                    HugeIcons.ArrowUp02,
                    contentDescription = "上移",
                    tint = if (index > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onMoveDown, enabled = index < total - 1) {
                Icon(
                    HugeIcons.ArrowDown02,
                    contentDescription = "下移",
                    tint = if (index < total - 1) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    HugeIcons.Cancel02,
                    contentDescription = "移除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun MemberSettingsSheet(
    member: DiscussionMember,
    onDismiss: () -> Unit,
    onUpdateStyle: (MemberStyle) -> Unit,
    onUpdateTemperature: (Float) -> Unit,
    onUpdateMaxTokens: (Int) -> Unit,
) {
    // 值直接派生自 member（VM 实时更新），不做本地缓存——
    // 若 remember 本地状态，切换编辑对象时弹窗会沿用上一个成员的值。
    val temperature = member.temperature ?: -1f
    val maxTokens = member.maxTokens ?: 0

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UIAvatar(name = member.name, modifier = Modifier.size(28.dp), value = member.avatar, loading = false)
                Text("「${member.name}」的设置", style = MaterialTheme.typography.titleSmall)
            }

            Text("发言风格", style = MaterialTheme.typography.titleSmall)
            listOf(
                MemberStyle.COMPACT to "精简",
                MemberStyle.BALANCED to "标准",
                MemberStyle.DETAILED to "详细",
            ).forEach { (style, label) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(
                        selected = member.style == style,
                        onClick = { onUpdateStyle(style) },
                    )
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }

            HorizontalDivider()

            // 温度：null 表示用助手默认
            Text(
                text = if (temperature < 0f) "温度：使用助手默认" else "温度：%.1f".format(temperature),
                style = MaterialTheme.typography.titleSmall,
            )
            Slider(
                value = if (temperature < 0f) 0.0f else temperature,
                onValueChange = { onUpdateTemperature(it) },
                valueRange = 0f..2f,
            )
            TextButton(onClick = { onUpdateTemperature(-1f) }) {
                Text("恢复助手默认")
            }

            HorizontalDivider()

            // maxTokens：0 表示用助手默认
            Text(
                text = if (maxTokens <= 0) "最大输出 token：使用助手默认" else "最大输出 token：$maxTokens",
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onUpdateMaxTokens((maxTokens - 500).coerceAtLeast(0)) },
                    enabled = maxTokens > 0,
                ) {
                    Text("−500")
                }
                TextButton(onClick = { onUpdateMaxTokens(maxTokens + 500) }) {
                    Text("＋500")
                }
            }
            TextButton(onClick = { onUpdateMaxTokens(0) }) {
                Text("恢复助手默认")
            }
        }
    }
}

private fun styleLabel(style: MemberStyle): String = when (style) {
    MemberStyle.COMPACT -> "精简"
    MemberStyle.BALANCED -> "标准"
    MemberStyle.DETAILED -> "详细"
}
