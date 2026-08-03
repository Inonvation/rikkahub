package me.rerere.rikkahub.ui.pages.assistant

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Cancel01
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANTS_IDS
import me.rerere.rikkahub.data.ai.prompts.ENGLISH_TUTOR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.MATH_TUTOR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.POLITICS_TUTOR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.MECHANICS_TUTOR_PROMPT
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.EditState
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.heroAnimation
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.modifier.onClick
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantImporter
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid
import androidx.compose.foundation.lazy.items as lazyItems

@Composable
fun AssistantPage(vm: AssistantVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val createState = useEditState<Assistant> {
        vm.addAssistant(it)
    }
    val navController = LocalNavController.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 搜索关键词状态
    var searchQuery by remember { mutableStateOf("") }
    // 标签过滤状态
    var selectedTagIds by remember { mutableStateOf(emptySet<Uuid>()) }
    // 操作菜单状态
    var actionSheetAssistant by remember { mutableStateOf<Assistant?>(null) }
    // 模板选择对话框
    var showTemplateDialog by remember { mutableStateOf(false) }

    // 根据搜索关键词和选中的标签过滤助手
    val filteredAssistants = remember(settings.assistants, selectedTagIds, searchQuery) {
        settings.assistants.filter { assistant ->
            val matchesSearch = searchQuery.isBlank() ||
                assistant.name.contains(searchQuery, ignoreCase = true)
            val matchesTags = selectedTagIds.isEmpty() ||
                assistant.tags.any { tagId -> tagId in selectedTagIds }
            matchesSearch && matchesTags
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_title))
                },
                navigationIcon = {
                    BackButton()
                },
                actions = {
                    IconButton(
                        onClick = {
                            showTemplateDialog = true
                        }) {
                        Icon(HugeIcons.Add01, stringResource(R.string.assistant_page_add))
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(it)
                .padding(top = 16.dp)
                .consumeWindowInsets(it),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val lazyListState = rememberLazyListState()
            val isFiltering = selectedTagIds.isNotEmpty() || searchQuery.isNotBlank()
            val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
                if (!isFiltering) {
                    val newAssistants = settings.assistants.toMutableList().apply {
                        add(to.index, removeAt(from.index))
                    }
                    vm.updateSettings(settings.copy(assistants = newAssistants))
                }
            }
            val hapticController = rememberHaptic()

            // 搜索框
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.assistant_page_search_placeholder)) },
                leadingIcon = {
                    Icon(HugeIcons.Search01, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(HugeIcons.Cancel01, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // 标签过滤器
            AssistantTagsFilterRow(
                settings = settings,
                vm = vm,
                selectedTagIds = selectedTagIds,
                onUpdateSelectedTagIds = { ids ->
                    selectedTagIds = ids
                }
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
                contentPadding = PaddingValues(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                state = lazyListState,
            ) {
                lazyItems(filteredAssistants, key = { assistant -> assistant.id }) { assistant ->
                    ReorderableItem(
                        state = reorderableState,
                        key = assistant.id,
                    ) { isDragging ->
                        val memories by vm.getMemories(assistant).collectAsStateWithLifecycle(
                            initialValue = emptyList(),
                        )
                        AssistantItem(
                            assistant = assistant,
                            settings = settings,
                            memories = memories,
                            onEdit = {
                                navController.navigate(Screen.AssistantDetail(id = assistant.id.toString()))
                            },
                            onShowActions = {
                                actionSheetAssistant = assistant
                            },
                            modifier = Modifier
                                .scale(if (isDragging) 0.95f else 1f)
                                .fillMaxWidth()
                                .then(
                                    if (!isFiltering) {
                                        Modifier.longPressDraggableHandle(
                                            onDragStarted = {
                                                hapticController.perform(HapticFeedbackType.GestureThresholdActivate)
                                            },
                                            onDragStopped = {
                                                hapticController.perform(HapticFeedbackType.GestureEnd)
                                            }
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                        )
                    }
                }
            }
        }
    }

    // 模板选择对话框
    if (showTemplateDialog) {
        TemplateSelectionDialog(
            onSelect = { templateAssistant ->
                showTemplateDialog = false
                createState.open(templateAssistant)
            },
            onDismiss = {
                showTemplateDialog = false
            }
        )
    }

    AssistantCreationSheet(createState)

    // 操作菜单 Bottom Sheet
    actionSheetAssistant?.let { assistant ->
        AssistantActionSheet(
            assistant = assistant,
            onDismiss = { actionSheetAssistant = null },
            onCopy = {
                vm.copyAssistant(assistant)
                actionSheetAssistant = null
            },
            onDelete = {
                vm.removeAssistant(assistant)
                actionSheetAssistant = null
            }
        )
    }
}

@Composable
private fun AssistantTagsFilterRow(
    settings: Settings,
    vm: AssistantVM,
    selectedTagIds: Set<Uuid>,
    onUpdateSelectedTagIds: (Set<Uuid>) -> Unit
) {
    val hapticController = rememberHaptic()
    if (settings.assistantTags.isNotEmpty()) {
        val tagsListState = rememberLazyListState()
        val tagsReorderableState = rememberReorderableLazyListState(tagsListState) { from, to ->
            val newTags = settings.assistantTags.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
            vm.updateSettings(settings.copy(assistantTags = newTags))
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
            state = tagsListState
        ) {
            lazyItems(items = settings.assistantTags, key = { tag -> tag.id }) { tag ->
                ReorderableItem(
                    state = tagsReorderableState, key = tag.id
                ) { isDragging ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        FilterChip(
                            onClick = {
                                onUpdateSelectedTagIds(
                                    if (tag.id in selectedTagIds) {
                                        selectedTagIds - tag.id
                                    } else {
                                        selectedTagIds + tag.id
                                    }
                                )
                            },
                            label = {
                                Text(tag.name)
                            },
                            selected = tag.id in selectedTagIds,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .scale(if (isDragging) 0.95f else 1f)
                                .longPressDraggableHandle(
                                    onDragStarted = {
                                        hapticController.perform(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        hapticController.perform(HapticFeedbackType.GestureEnd)
                                    },
                                )
                        )
                    }
                }
            }
        }
    }
}

private data class AssistantTemplate(
    val name: String,
    val description: String,
    val create: () -> Assistant,
)

private val ASSISTANT_TEMPLATES = listOf(
    AssistantTemplate(
        name = "空白助手",
        description = "完全自定义配置",
        create = { Assistant() }
    ),
    AssistantTemplate(
        name = "英语导师",
        description = "单词查询、翻译、作文模板、题目指导",
        create = {
            Assistant(
                systemPrompt = ENGLISH_TUTOR_PROMPT,
                temperature = 0.3f,
                contextMessageLimit = 20,
                enableTimeReminder = false,
                localTools = emptyList(),
                enabledStudyTools = listOf("save_vocabulary", "save_note"),
                studySubject = "english",
            )
        }
    ),
    AssistantTemplate(
        name = "数学导师",
        description = "考点定位、分步推导、定理引用",
        create = {
            Assistant(
                systemPrompt = MATH_TUTOR_PROMPT,
                temperature = 0.3f,
                reasoningLevel = me.rerere.ai.core.ReasoningLevel.HIGH,
                contextMessageLimit = 20,
                enableTimeReminder = false,
                localTools = emptyList(),
                enabledStudyTools = listOf("save_wrong_question", "save_note"),
                studySubject = "math",
            )
        }
    ),
    AssistantTemplate(
        name = "政治导师",
        description = "知识点精讲、论述框架、助记口诀、抽背提问",
        create = {
            Assistant(
                systemPrompt = POLITICS_TUTOR_PROMPT,
                temperature = 0.3f,
                contextMessageLimit = 20,
                enableTimeReminder = false,
                localTools = emptyList(),
                enabledStudyTools = listOf("save_note", "save_knowledge_card", "quiz_user"),
                studySubject = "politics",
            )
        }
    ),
    AssistantTemplate(
        name = "机械原理导师",
        description = "概念解析、机构分析、公式推导、真题演练",
        create = {
            Assistant(
                systemPrompt = MECHANICS_TUTOR_PROMPT,
                temperature = 0.3f,
                reasoningLevel = me.rerere.ai.core.ReasoningLevel.HIGH,
                contextMessageLimit = 20,
                enableTimeReminder = false,
                localTools = emptyList(),
                enabledStudyTools = listOf("save_wrong_question", "save_note", "save_knowledge_card", "quiz_user"),
                studySubject = "mechanics",
            )
        }
    ),
    AssistantTemplate(
        name = "日常聊天",
        description = "轻松闲聊，温暖陪伴",
        create = {
            Assistant(
                systemPrompt = "You are a friendly and supportive companion. Chat naturally with the user in Chinese. Be encouraging, warm, and understanding. Keep responses concise (2-4 sentences).",
                temperature = 0.8f,
                enableMemory = true,
                enableTimeReminder = false,
                localTools = emptyList(),
            )
        }
    ),
)

@Composable
private fun TemplateSelectionDialog(
    onSelect: (Assistant) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择助手模板") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ASSISTANT_TEMPLATES.forEach { template ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onClick { onSelect(template.create()) }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = template.name,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = template.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun AssistantCreationSheet(
    state: EditState<Assistant>,
) {
    state.EditStateContent { assistant, update ->
        ModalBottomSheet(
            onDismissRequest = {
                state.dismiss()
            },
            sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
            dragHandle = {},
            sheetGesturesEnabled = false
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    FormItem(
                        label = {
                            Text(stringResource(R.string.assistant_page_name))
                        },
                    ) {
                        OutlinedTextField(
                            value = assistant.name, onValueChange = {
                                update(
                                    assistant.copy(
                                        name = it
                                    )
                                )
                            }, modifier = Modifier.fillMaxWidth()
                        )
                    }

                    AssistantImporter(
                        onUpdate = {
                            update(it)
                            state.confirm()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = {
                            state.dismiss()
                        }) {
                        Text(stringResource(R.string.assistant_page_cancel))
                    }
                    TextButton(
                        onClick = {
                            state.confirm()
                        }) {
                        Text(stringResource(R.string.assistant_page_save))
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantItem(
    assistant: Assistant,
    settings: Settings,
    modifier: Modifier = Modifier,
    memories: List<AssistantMemory>,
    onEdit: () -> Unit,
    onShowActions: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        onClick = onEdit,
        colors = CardDefaults.cardColors(
            containerColor = CustomColors.listItemColors.containerColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UIAvatar(
                name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                value = assistant.avatar,
                modifier = Modifier
                    .size(40.dp)
                    .heroAnimation("assistant_${assistant.id}")
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {

                Text(
                    text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (assistant.enableMemory) {
                        Tag(type = TagType.SUCCESS) {
                            Text(stringResource(R.string.assistant_page_memory_count, memories.size))
                        }
                    }

                    if (assistant.tags.isNotEmpty()) {
                        assistant.tags.take(2).fastForEach { tagId ->
                            val tag = settings.assistantTags.find { it.id == tagId }
                                ?: return@fastForEach
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                            ) {
                                Text(
                                    text = tag.name,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        if (assistant.tags.size > 2) {
                            Text(
                                text = "+${assistant.tags.size - 2}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 学习面板标签
                if (assistant.enabledStudyTools.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
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
            }

            IconButton(
                onClick = onShowActions
            ) {
                Icon(
                    imageVector = HugeIcons.MoreVertical,
                    contentDescription = stringResource(R.string.assistant_page_actions)
                )
            }
        }
    }
}

@Composable
private fun AssistantActionSheet(
    assistant: Assistant,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // 助手信息头部
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                UIAvatar(
                    name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    value = assistant.avatar,
                    modifier = Modifier.size(40.dp)
                )
                Text(
                    text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                    style = MaterialTheme.typography.titleMedium
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 克隆选项
            ListItem(
                headlineContent = { Text(stringResource(R.string.assistant_page_clone)) },
                leadingContent = {
                    Icon(
                        imageVector = HugeIcons.Copy01,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                modifier = Modifier.onClick { onCopy() },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            // 删除选项（仅非默认助手显示）
            if (assistant.id !in DEFAULT_ASSISTANTS_IDS) {
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.assistant_page_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    modifier = Modifier.onClick { showDeleteDialog = true },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.assistant_page_delete)) },
            text = { Text(stringResource(R.string.assistant_page_delete_dialog_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
