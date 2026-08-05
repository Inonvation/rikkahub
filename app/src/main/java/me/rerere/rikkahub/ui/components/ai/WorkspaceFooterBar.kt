package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import me.rerere.ai.core.TokenUsage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderLocked
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.cost.CostCalculator
import me.rerere.rikkahub.data.ai.cost.CostCurrency
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderSettings
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import me.rerere.rikkahub.ui.pages.trustedfolders.TrustedFolderSelectSheet
import me.rerere.workspace.WorkspaceShellStatus
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 聊天输入框下方的单行栏：左边当前工作目录（可点切换），右侧两个指标——平均缓存命中率、
 * 会话费用（可点配置）。数字等宽特性（tnum）+ 0 占位固定位数，数字变化不互相推挤。
 *
 * 放在 ChatInput 的 imePadding 作用域内，键盘弹起时随输入框一起上移、不被遮挡。
 */
@Composable
fun WorkspaceFooterBar(
    assistant: Assistant,
    conversation: Conversation,
    settings: Settings,
    onUpdateAssistant: (Assistant) -> Unit,
    onUpdateConversation: (Conversation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val workspaceRepository: WorkspaceRepository = koinInject()
    val workspaces by workspaceRepository.listFlow().collectAsState(initial = emptyList())
    val trustedFolderRepository: TrustedFolderRepository = koinInject()
    val trustedSettings by trustedFolderRepository.settingsFlow.collectAsState(initial = TrustedFolderSettings())
    val activeTrustedProject = trustedSettings.projects.find { it.id == trustedSettings.activeProjectId }
    // 激活项目授权状态（失效时 chip 变红警示）；进入页面/回到前台时检查，
    // 这样在信任文件夹页重新授权后返回聊天页能及时恢复，不会一直显示失效
    var trustedAuthorized by remember(activeTrustedProject?.id) { mutableStateOf(true) }
    var showTrustedSelect by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LifecycleResumeEffect(activeTrustedProject?.id) {
        scope.launch { trustedAuthorized = trustedFolderRepository.isActiveAuthorized() }
        onPauseOrDispose { }
    }
    val navController = LocalNavController.current
    val hapticController = rememberHaptic()
    val boundWorkspace = remember(workspaces, assistant.workspaceId) {
        workspaces.find { it.id == assistant.workspaceId?.toString() }
    }
    var showCwdSheet by remember { mutableStateOf(false) }
    var showCostSheet by remember { mutableStateOf(false) }

    // 会话级统计：主模型消息 + 当前会话子代理用量（任务终态落库后并入）合并计算
    val conversationRepository: ConversationRepository = koinInject()
    val subAgentUsages by conversationRepository.observeSubAgentUsage(conversation.id.toString())
        .collectAsState(initial = emptyList())
    // remember 的 key 用 conversation 而非 currentMessages（后者每次 get 返回新 List，
    // 用作 key 会让缓存每帧失效、命中率与费用重复计算）。
    // 子代理 usage 补上 cacheWriteTokens：命中率分母按「全部输入 - 写缓存」剔除写缓存，
    // 缺了它，子代理写缓存那部分会留在分母里把命中率系统性拉低。
    val cacheHitRate = remember(conversation, subAgentUsages) {
        val usages = conversation.currentMessages.map { it.usage } + subAgentUsages.map {
            TokenUsage(
                promptTokens = it.promptTokens.toInt(),
                completionTokens = it.completionTokens.toInt(),
                cachedTokens = it.cachedTokens.toInt(),
                cacheWriteTokens = it.cacheWriteTokens.toInt(),
            )
        }
        CostCalculator.cacheHitRate(usages)
    }
    // 按显示货币计价：USD 走美元官方价，RMB 走人民币官方价（无则按汇率换算）。
    // remember 依赖补 modelPricingOverrides：费用配置窗改单价/倍率后这里才能刷新。
    val totalCost = remember(
        conversation, subAgentUsages,
        settings.costCurrency, settings.costUsdCnyRate, settings.modelPricingOverrides,
    ) {
        val messages = conversation.currentMessages
        fun costFor(modelId: Uuid?, usage: TokenUsage?): Double {
            val resolved = modelId?.let { settings.findModelById(it) } ?: settings.getCurrentChatModel()
            return when (settings.costCurrency) {
                CostCurrency.USD -> CostCalculator.costUsd(resolved?.modelId, usage, settings.modelPricingOverrides)
                CostCurrency.RMB -> CostCalculator.costCny(resolved?.modelId, usage, settings.modelPricingOverrides, settings.costUsdCnyRate)
            }
        }
        val mainCost = messages.sumOf { costFor(it.modelId, it.usage) }
        val subCost = subAgentUsages.sumOf { u ->
            val uuid = u.modelId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            costFor(
                uuid,
                TokenUsage(
                    promptTokens = u.promptTokens.toInt(),
                    completionTokens = u.completionTokens.toInt(),
                    cachedTokens = u.cachedTokens.toInt(),
                    cacheWriteTokens = u.cacheWriteTokens.toInt(),
                )
            )
        }
        mainCost + subCost
    }

    val currentModelStr = settings.getCurrentChatModel()?.modelId
    val ready = boundWorkspace != null && boundWorkspace.shellStatus == WorkspaceShellStatus.READY.name
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    Surface(color = Color.Transparent, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 信任文件夹状态：激活项目时显示。点击 = 直达该项目文件浏览器（授权失效则去重新信任）；
            // 长按 = 弹项目切换。未激活不渲染，保持原布局。
            if (activeTrustedProject != null) {
                val tint = if (trustedAuthorized) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .combinedClickable(
                            onClick = {
                                hapticController.perform(HapticFeedbackType.KeyboardTap)
                                if (trustedAuthorized) {
                                    navController.navigate(Screen.TrustedFolderDetail(activeTrustedProject.id, ""))
                                } else {
                                    navController.navigate(Screen.TrustedFolders)
                                }
                            },
                            onLongClick = {
                                hapticController.perform(HapticFeedbackType.KeyboardTap)
                                showTrustedSelect = true
                            },
                        )
                        .padding(vertical = 4.dp, horizontal = 6.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.FolderLocked,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = tint,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (trustedAuthorized) activeTrustedProject.name else "${activeTrustedProject.name} · 授权失效",
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (ready) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.small)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                // 单击：进入工作区文件界面
                                hapticController.perform(HapticFeedbackType.KeyboardTap)
                                // ready 分支已保证 boundWorkspace 非空
                                navController.navigate(Screen.WorkspaceDetail(boundWorkspace!!.id))
                            },
                            onLongClick = {
                                // 长按：切换工作目录
                                hapticController.perform(HapticFeedbackType.KeyboardTap)
                                showCwdSheet = true
                            },
                        )
                        .padding(vertical = 4.dp, horizontal = 6.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Folder01,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = conversation.workspaceCwd ?: "/workspace",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else if (boundWorkspace != null) {
                Text(
                    text = "工作区环境未就绪",
                    style = MaterialTheme.typography.labelSmall,
                    color = hintColor,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            hapticController.perform(HapticFeedbackType.KeyboardTap)
                            navController.navigate(Screen.Workspaces)
                        }
                        .padding(vertical = 4.dp, horizontal = 6.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.Codesandbox,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = hintColor,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "未绑定工作区 · 点击绑定",
                        style = MaterialTheme.typography.labelSmall,
                        color = hintColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 右侧指标：平均缓存 / 费用。与整体同字体，数字开等宽特性（tnum）+ 0 占位固定位数，
            // 数字变化宽度不变（骨架屏式稳定），不推挤相邻项
            val cacheStr = if (cacheHitRate != null) {
                "平均缓存 ${"%05.2f".format(cacheHitRate * 100)}%"
            } else {
                "平均缓存 00.00%" // 骨架屏占位：数据未到先以 0 显示，宽度与真实值一致
            }
            val costSymbol = if (settings.costCurrency == CostCurrency.USD) "$" else "¥"
            val costStr = "$costSymbol${"%05.2f".format(totalCost)}"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IndicatorText(text = cacheStr)
                IndicatorText(
                    text = costStr,
                    onClick = {
                        hapticController.perform(HapticFeedbackType.KeyboardTap)
                        showCostSheet = true
                    },
                )
            }
        }
    }

    if (showCwdSheet && ready) {
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

    if (showCostSheet) {
        CostConfigSheet(
            settings = settings,
            currentModelId = currentModelStr,
            onDismiss = { showCostSheet = false },
        )
    }

    if (showTrustedSelect) {
        TrustedFolderSelectSheet(
            projects = trustedSettings.projects,
            activeProjectId = trustedSettings.activeProjectId,
            onSelect = { id ->
                showTrustedSelect = false
                scope.launch { trustedFolderRepository.setActiveProject(id) }
            },
            onManage = {
                showTrustedSelect = false
                navController.navigate(Screen.TrustedFolders)
            },
            onDismiss = { showTrustedSelect = false },
        )
    }
}

/**
 * footer 指标文本：与整体同字体（无自定义字体族），数字开等宽特性 tnum（tabular figures）保证
 * 每位数字等宽，配合调用方用 0 占位固定位数，数字变化宽度不变、不推挤相邻项。
 * onClick 非空时可点击（费用段），否则纯展示。
 */
@Composable
private fun IndicatorText(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
    onClick: (() -> Unit)? = null,
) {
    val modifier = Modifier
        .clip(MaterialTheme.shapes.small)
        .let { if (onClick != null) it.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onClick() } else it }
        .padding(vertical = 4.dp)
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFeatureSettings = "tnum", // 数字等宽：00.00 与 32.50 宽度一致
        ),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
