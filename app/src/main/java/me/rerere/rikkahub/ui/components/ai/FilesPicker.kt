package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.BuiltInTools
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bookshelf01
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderLocked
import me.rerere.hugeicons.stroke.GlobalSearch
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.McpServer
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.Settings03
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.resolveConversationPolicy
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderSettings
import me.rerere.rikkahub.ui.components.ui.ExtensionSelector
import me.rerere.rikkahub.ui.components.ui.permission.PermissionCamera
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import me.rerere.workspace.WorkspaceShellStatus
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
internal fun FilesPicker(
    conversation: Conversation,
    assistant: Assistant,
    state: ChatInputState,
    onCompressContext: (additionalPrompt: String, targetTokens: Int, keepRecentTokens: Int) -> Job,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    /** 网络搜索状态/服务更新：与输入栏搜索按钮共用同一组回调 */
    enableSearch: Boolean,
    onUpdateSearchMode: (SearchMode) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    showInjectionSheet: Boolean,
    onShowInjectionSheetChange: (Boolean) -> Unit,
    showCompressDialog: Boolean,
    onShowCompressDialogChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onTakePic: () -> Unit,
    onPickImage: () -> Unit,
    onPickVideo: () -> Unit,
    onPickAudio: () -> Unit,
    onPickFile: () -> Unit,
) {
    val settings = LocalSettings.current
    val chatModel = settings.getCurrentChatModel()
    val provider = chatModel?.findProvider(providers = settings.providers)
    val navController = LocalNavController.current
    val workspaceRepository: WorkspaceRepository = koinInject()
    val workspaces by workspaceRepository.listFlow().collectAsState(initial = emptyList())
    val hapticController = rememberHaptic()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TakePicButton(onLaunchCamera = onTakePic)

            ImagePickButton(onClick = onPickImage)

            if (provider != null && provider is ProviderSetting.Google) {
                VideoPickButton(onClick = onPickVideo)

                AudioPickButton(onClick = onPickAudio)
            }

            FilePickButton(onClick = onPickFile)
        }

        HorizontalDivider(
            modifier = Modifier.fillMaxWidth()
        )

        // ===== 能力入口：统一为「主标题 + 次标题」宽卡片
        // 排序：MCP 服务 / 网络搜索服务 / 扩展管理 / 工作区 / 信任文件夹 / 知识库 =====

        // 共享依赖：信任文件夹状态（助手级绑定）+ 会话能力策略（MCP / 知识库卡片用）
        val trustedFolderRepository: TrustedFolderRepository = koinInject()
        val trustedSettings by trustedFolderRepository.settingsFlow.collectAsState(initial = TrustedFolderSettings())
        val boundTrustedProject = assistant.trustedFolderProjectId
            ?.let { pid -> trustedSettings.projects.find { it.id == pid } }
        val modePolicy = resolveConversationPolicy(
            conversation = conversation,
            assistant = assistant,
            settings = settings,
        )

        // 1. MCP 服务
        val mcpManager: McpManager = koinInject()
        val mcpStatus by mcpManager.syncingStatus.collectAsStateWithLifecycle()
        val mcpLoading = mcpStatus.values.any { it == McpStatus.Connecting }
        val enabledMcpCount = settings.mcpServers.count {
            it.commonOptions.enable && it.id in assistant.mcpServers
        }
        var showMcpSheet by remember { mutableStateOf(false) }
        var showKbSheet by remember { mutableStateOf(false) }
        ListItem(
            leadingContent = { Icon(HugeIcons.McpServer, contentDescription = null) },
            headlineContent = { Text("MCP 服务") },
            supportingContent = {
                Text(
                    text = when {
                        settings.mcpServers.isEmpty() -> "未配置 MCP 服务"
                        enabledMcpCount > 0 -> "已启用 $enabledMcpCount 个服务"
                        else -> "未启用服务"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                if (mcpLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable(enabled = settings.mcpServers.isNotEmpty() && modePolicy.allowMcpUse) {
                    hapticController.lightTap()
                    showMcpSheet = true
                },
        )

        // 2. 网络搜索服务（新增卡片，与输入栏搜索按钮同源）
        var showSearchSheet by remember { mutableStateOf(false) }
        val enabledSearchServices = settings.searchServices.filter { it.id in settings.enabledSearchServiceIds }
        val builtInSearchEnabled = chatModel?.tools?.contains(BuiltInTools.Search) == true
        ListItem(
            leadingContent = { Icon(HugeIcons.GlobalSearch, contentDescription = null) },
            headlineContent = { Text("网络搜索服务") },
            supportingContent = {
                Text(
                    text = when {
                        builtInSearchEnabled -> "使用模型内置搜索"
                        enableSearch && enabledSearchServices.isNotEmpty() ->
                            "已开启 · " + enabledSearchServices.joinToString("、") { it.displayName }
                        enableSearch -> "已开启"
                        else -> "未开启"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                if (enableSearch && settings.enabledSearchServiceIds.isNotEmpty()) {
                    Text(
                        text = settings.enabledSearchServiceIds.size.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable {
                    hapticController.lightTap()
                    showSearchSheet = true
                },
        )

        // 3. 扩展管理：次标题展示各类已开数量（技能 / 快捷消息 / 模式注入 / 世界书）
        val skillCount = assistant.enabledSkills.size
        val quickMessageCount = assistant.quickMessageIds.size
        val modeInjectionCount =
            if (assistant.allowConversationPromptInjection) conversation.modeInjectionIds.size
            else assistant.modeInjectionIds.size
        val lorebookCount =
            if (assistant.allowConversationPromptInjection) conversation.lorebookIds.size
            else assistant.lorebookIds.size
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = HugeIcons.Package,
                    contentDescription = stringResource(R.string.assistant_page_tab_extensions),
                )
            },
            headlineContent = {
                Text(stringResource(R.string.assistant_page_tab_extensions))
            },
            supportingContent = {
                Text(
                    text = "技能 $skillCount · 快捷消息 $quickMessageCount · 模式注入 $modeInjectionCount · 世界书 $lorebookCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable {
                    hapticController.lightTap()
                    onShowInjectionSheetChange(true)
                },
        )

        // 4. 工作区：点击卡片切换工作目录（未绑定/未就绪时引导选择工作区）；右侧设置图标进入工作区文件目录
        var showWorkspaceSheet by remember { mutableStateOf(false) }
        var showCwdSheet by remember { mutableStateOf(false) }
        val boundWorkspace = remember(workspaces, assistant.workspaceId) {
            workspaces.find { it.id == assistant.workspaceId?.toString() }
        }
        val workspaceReady = boundWorkspace != null && boundWorkspace.shellStatus == WorkspaceShellStatus.READY.name
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = HugeIcons.Codesandbox,
                    contentDescription = stringResource(R.string.assistant_page_workspace),
                )
            },
            headlineContent = {
                Text(stringResource(R.string.assistant_page_workspace))
            },
            supportingContent = {
                Text(
                    text = when {
                        boundWorkspace == null -> stringResource(R.string.assistant_page_workspace_unbound)
                        workspaceReady -> conversation.workspaceCwd ?: "/workspace"
                        else -> "工作区环境未就绪"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                if (boundWorkspace != null) {
                    IconButton(
                        onClick = {
                            hapticController.lightTap()
                            onDismiss()
                            navController.navigate(Screen.WorkspaceDetail(boundWorkspace.id))
                        },
                    ) {
                        Icon(
                            imageVector = HugeIcons.Settings03,
                            contentDescription = stringResource(R.string.assistant_page_workspace_settings),
                        )
                    }
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable {
                    hapticController.lightTap()
                    if (workspaceReady) showCwdSheet = true else showWorkspaceSheet = true
                },
        )

        // 5. 信任文件夹：激活项目名入主标题下的次标题
        ListItem(
            leadingContent = {
                Icon(
                    imageVector = HugeIcons.FolderLocked,
                    contentDescription = "信任文件夹",
                )
            },
            headlineContent = {
                Text("信任文件夹")
            },
            supportingContent = {
                Text(
                    text = boundTrustedProject?.name ?: "未绑定",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable {
                    hapticController.lightTap()
                    onDismiss()
                    navController.navigate(Screen.TrustedFolders)
                },
        )

        // 6. 知识库
        ListItem(
            leadingContent = { Icon(HugeIcons.Bookshelf01, contentDescription = null) },
            headlineContent = { Text("知识库") },
            supportingContent = {
                Text(
                    text = if (assistant.knowledgeBaseIds.isEmpty()) "未选择知识库"
                    else "已选择 ${assistant.knowledgeBaseIds.size} 个知识库",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable(enabled = modePolicy.allowKnowledge) {
                    hapticController.lightTap()
                    showKbSheet = true
                },
        )

        if (showMcpSheet) {
            ModalBottomSheet(
                onDismissRequest = { showMcpSheet = false },
                sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.7f)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.mcp_picker_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    )
                    if (mcpLoading) {
                        LinearWavyProgressIndicator()
                    }
                    McpPicker(
                        assistant = assistant,
                        servers = settings.mcpServers,
                        onUpdateAssistant = onUpdateAssistant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                }
            }
        }

        if (showKbSheet) {
            ModalBottomSheet(
                onDismissRequest = { showKbSheet = false },
                sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
            ) {
                KnowledgeBasePicker(
                    selectedIds = assistant.knowledgeBaseIds,
                    onSelectionChange = { newIds ->
                        onUpdateAssistant(assistant.copy(knowledgeBaseIds = newIds))
                    },
                    onDismiss = { showKbSheet = false },
                )
            }
        }

        // 网络搜索服务弹层：复用输入栏搜索按钮的同一套配置面板
        if (showSearchSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSearchSheet = false },
                sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.7f)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.search_picker_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    )
                    SearchPicker(
                        enableSearch = enableSearch,
                        settings = settings,
                        model = chatModel,
                        onUpdateSearchMode = onUpdateSearchMode,
                        onUpdateSearchService = onUpdateSearchService,
                        onDismiss = { showSearchSheet = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                }
            }
        }

        if (showWorkspaceSheet) {
            WorkspaceSelectSheet(
                assistant = assistant,
                workspaces = workspaces,
                onSelect = { workspaceId ->
                    val newId = workspaceId?.let { Uuid.parse(it) }
                    if (newId != assistant.workspaceId) {
                        // 换工作区：会话 cwd 与助手默认 cwd 一并重置（旧路径在新工作区可能不存在）
                        onUpdateAssistant(assistant.copy(workspaceId = newId, defaultWorkspaceCwd = null))
                        if (conversation.workspaceCwd != null) {
                            onUpdateConversation(conversation.copy(workspaceCwd = null))
                        }
                    }
                    showWorkspaceSheet = false
                },
                onManage = {
                    showWorkspaceSheet = false
                    navController.navigate(Screen.Workspaces)
                },
                onDismiss = { showWorkspaceSheet = false },
                onSettings = { workspaceId ->
                    showWorkspaceSheet = false
                    // 关闭外层「更多选项」弹窗，避免从设置页返回后弹窗残留（延迟退出 bug）
                    onDismiss()
                    navController.navigate(Screen.WorkspaceDetail(workspaceId))
                },
            )
        }
        if (showCwdSheet && workspaceReady) {
            WorkspaceCwdPickerSheet(
                workspaceId = boundWorkspace!!.id,
                currentCwd = conversation.workspaceCwd,
                onSelectCwd = { newCwd ->
                    onUpdateConversation(conversation.copy(workspaceCwd = newCwd))
                    // 同步保存为助手的默认工作目录，新对话沿用
                    onUpdateAssistant(assistant.copy(defaultWorkspaceCwd = newCwd))
                },
                onDismiss = { showCwdSheet = false },
            )
        }
    }

    // Injection Bottom Sheet
    if (showInjectionSheet) {
        InjectionQuickConfigSheet(
            conversation = conversation,
            assistant = assistant,
            settings = settings,
            onUpdateAssistant = onUpdateAssistant,
            onUpdateConversation = onUpdateConversation,
            onDismiss = { onShowInjectionSheetChange(false) },
            onDismissAll = onDismiss,
        )
    }
}

@Composable
private fun InjectionQuickConfigSheet(
    conversation: Conversation,
    assistant: Assistant,
    settings: Settings,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    onDismiss: () -> Unit,
    onDismissAll: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
    val navController = LocalNavController.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = 16.dp),
        ) {
            ExtensionSelector(
                assistant = assistant,
                settings = settings,
                onUpdate = onUpdateAssistant,
                conversation = conversation,
                onUpdateConversation = onUpdateConversation,
                modifier = Modifier.weight(1f),
                onNavigateToQuickMessages = {
                    onDismissAll()
                    navController.navigate(Screen.QuickMessages)
                },
                onNavigateToPrompts = {
                    onDismissAll()
                    navController.navigate(Screen.Prompts)
                },
                onNavigateToSkills = {
                    onDismissAll()
                    navController.navigate(Screen.Skills)
                })

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun ImagePickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Image02, null)
    }, text = {
        Text(stringResource(R.string.photo))
    }) {
        onClick()
    }
}

@Composable
fun TakePicButton(onLaunchCamera: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Camera01, null)
    }, text = {
        Text(stringResource(R.string.take_picture))
    }) {
        onLaunchCamera()
    }
}

@Composable
fun VideoPickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Video01, null)
    }, text = {
        Text(stringResource(R.string.video))
    }) {
        onClick()
    }
}

@Composable
fun AudioPickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.MusicNote03, null)
    }, text = {
        Text(stringResource(R.string.audio))
    }) {
        onClick()
    }
}

@Composable
fun FilePickButton(onClick: () -> Unit = {}) {
    BigIconTextButton(icon = {
        Icon(HugeIcons.Files02, null)
    }, text = {
        Text(stringResource(R.string.upload_file))
    }) {
        onClick()
    }
}

@Composable
private fun BigIconTextButton(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    text: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hapticController = rememberHaptic()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource, indication = LocalIndication.current, onClick = {
                    hapticController.lightTap()
                    onClick()
                }
            )
            .semantics {
                role = Role.Button
            }
            .wrapContentWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp)
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)
            ) {
                icon()
            }
        }
        ProvideTextStyle(MaterialTheme.typography.bodySmall) {
            text()
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BigIconTextButtonPreview() {
    Row(
        modifier = Modifier.padding(16.dp)
    ) {
        BigIconTextButton(icon = {
            Icon(HugeIcons.Image02, null)
        }, text = {
            Text(stringResource(R.string.photo))
        }) {}
    }
}
