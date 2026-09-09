package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Edit03
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.effectiveCategory
import me.rerere.rikkahub.ui.components.ai.ASSISTANT_GROUP_OTHER
import me.rerere.rikkahub.ui.components.ai.ASSISTANT_GROUP_CATEGORY_PREFIX
import me.rerere.rikkahub.ui.components.ai.AssistantCollapsibleGroup
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.HapticController
import me.rerere.rikkahub.ui.hooks.rememberAssistantState
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import me.rerere.rikkahub.ui.modifier.onClick

@Composable
fun AssistantPicker(
    settings: Settings,
    onUpdateSettings: (Settings) -> Unit,
    modifier: Modifier = Modifier,
    onClickSetting: () -> Unit,
) {
    val state = rememberAssistantState(settings, onUpdateSettings)
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    var showPicker by remember { mutableStateOf(false) }
    val hapticController = rememberHaptic()

    ListItem(
        headlineContent = {
            Text(
                text = state.currentAssistant.name.ifEmpty { defaultAssistantName },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            if (state.currentAssistant.enabledStudyTools.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    state.currentAssistant.enabledStudyTools.mapNotNull { toolName ->
                        when (toolName) {
                            "save_vocabulary" -> "生词本"
                            "save_note" -> "笔记"
                            "save_wrong_question" -> "错题本"
                            "save_knowledge_card" -> "知识点"
                            "quiz_user" -> "抽背"
                            else -> null
                        }
                    }.forEach { label ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
        leadingContent = {
            UIAvatar(
                name = state.currentAssistant.name.ifEmpty { defaultAssistantName },
                value = state.currentAssistant.avatar,
                onClick = onClickSetting,
                modifier = Modifier.size(32.dp),
            )
        },
        trailingContent = {
            IconButton(
                onClick = {
                    hapticController.lightTap()
                    onClickSetting()
                }
            ) {
                Icon(
                    imageVector = HugeIcons.Edit03,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.onClick {
            showPicker = true
        },
    )

    if (showPicker) {
        AssistantPickerSheet(
            settings = settings,
            currentAssistant = state.currentAssistant,
            onAssistantSelected = { assistant ->
                showPicker = false
                state.setSelectAssistant(assistant)
            },
            onDismiss = {
                showPicker = false
            }
        )
    }
}

@Composable
internal fun AssistantPickerSheet(
    settings: Settings,
    currentAssistant: Assistant,
    onAssistantSelected: (Assistant) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val scope = rememberCoroutineScope()
    val defaultAssistantName = stringResource(R.string.assistant_page_default_assistant)
    val navController = LocalNavController.current

    // 折叠的分组 key：空集 = 全部展开；与助手设置页共用同一套 key 规则与折叠动画组件
    var collapsedGroups by remember { mutableStateOf(emptySet<String>()) }
    fun isCollapsed(key: String) = key in collapsedGroups
    fun toggle(key: String) {
        collapsedGroups = if (key in collapsedGroups) collapsedGroups - key else collapsedGroups + key
    }
    // 分类 -> 成员预分组（单归属，保持全局顺序），供组头计数与展开列表复用
    val membersByTag = remember(settings.assistants, settings.assistantTags) {
        settings.assistantTags.associate { tag ->
            tag.id to settings.assistants.filter { it.effectiveCategory == tag.id }
        }
    }
    // 未分类助手：归入「其他」组
    val otherMembers = remember(settings.assistants) {
        settings.assistants.filter { it.effectiveCategory == null }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        val hapticController = rememberHaptic()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 标题行 + 快捷进入完整助手设置页（含分类折叠浏览与分类管理）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.assistant_page_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        hapticController.lightTap()
                        scope.launch {
                            sheetState.hide()
                            onDismiss()
                            navController.navigate(Screen.Assistant)
                        }
                    },
                ) {
                    Icon(
                        imageVector = HugeIcons.LookTop,
                        contentDescription = stringResource(R.string.assistant_page_title),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // 折叠分组选择列表：各分类组 + 「其他」组，组内助手即点即选
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 分类组们（默认全展开，展开态与设置页同构）
                settings.assistantTags.forEach { tag ->
                    val groupKey = "$ASSISTANT_GROUP_CATEGORY_PREFIX${tag.id}"
                    val members = membersByTag[tag.id].orEmpty()
                    item(key = groupKey) {
                        AssistantCollapsibleGroup(
                            title = tag.name,
                            count = members.size,
                            collapsed = isCollapsed(groupKey),
                            onToggle = { toggle(groupKey) },
                        ) {
                            members.forEach { assistant ->
                                AssistantSelectableRow(
                                    assistant = assistant,
                                    checked = assistant.id == currentAssistant.id,
                                    defaultAssistantName = defaultAssistantName,
                                    hapticController = hapticController,
                                    onSelect = { onAssistantSelected(assistant) },
                                    onEdit = {
                                        scope.launch {
                                            sheetState.hide()
                                            onDismiss()
                                            navController.navigate(Screen.AssistantDetail(assistant.id.toString()))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                // 「其他」组：未分类助手（单归属下 category == null）
                item(key = ASSISTANT_GROUP_OTHER) {
                    AssistantCollapsibleGroup(
                        title = stringResource(R.string.assistant_category_other),
                        count = otherMembers.size,
                        collapsed = isCollapsed(ASSISTANT_GROUP_OTHER),
                        onToggle = { toggle(ASSISTANT_GROUP_OTHER) },
                    ) {
                        otherMembers.forEach { assistant ->
                            AssistantSelectableRow(
                                assistant = assistant,
                                checked = assistant.id == currentAssistant.id,
                                defaultAssistantName = defaultAssistantName,
                                hapticController = hapticController,
                                onSelect = { onAssistantSelected(assistant) },
                                onEdit = {
                                    scope.launch {
                                        sheetState.hide()
                                        onDismiss()
                                        navController.navigate(Screen.AssistantDetail(assistant.id.toString()))
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 选择弹层内的单行助手卡：点卡片即选中，行尾编辑进入该助手详情 */
@Composable
private fun AssistantSelectableRow(
    assistant: Assistant,
    checked: Boolean,
    defaultAssistantName: String,
    hapticController: HapticController,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        onClick = { hapticController.lightTap(); onSelect() },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        AssistantItem(
            assistant = assistant,
            defaultAssistantName = defaultAssistantName,
            onEdit = onEdit,
        )
    }
}

@Composable
private fun AssistantItem(
    assistant: Assistant,
    defaultAssistantName: String,
    onEdit: () -> Unit
) {
    val hapticController = rememberHaptic()
    ListItem(
        headlineContent = {
            Text(
                text = assistant.name.ifEmpty { defaultAssistantName },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            if (assistant.enabledStudyTools.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    assistant.enabledStudyTools.mapNotNull { toolName ->
                        when (toolName) {
                            "save_vocabulary" -> "生词本"
                            "save_note" -> "笔记"
                            "save_wrong_question" -> "错题本"
                            "save_knowledge_card" -> "知识点"
                            "quiz_user" -> "抽背"
                            else -> null
                        }
                    }.forEach { label ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
        leadingContent = {
            UIAvatar(
                name = assistant.name.ifEmpty { defaultAssistantName },
                value = assistant.avatar,
                modifier = Modifier.size(32.dp)
            )
        },
        trailingContent = {
            IconButton(
                onClick = {
                    hapticController.lightTap()
                    onEdit()
                }
            ) {
                Icon(
                    imageVector = HugeIcons.Edit03,
                    contentDescription = null
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
