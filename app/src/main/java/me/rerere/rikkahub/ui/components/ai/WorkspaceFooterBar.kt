package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import me.rerere.ai.core.TokenUsage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.Folder01
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
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import me.rerere.workspace.WorkspaceShellStatus
import kotlin.uuid.Uuid
import org.koin.compose.koinInject

/**
 * 聊天输入框下方的单行栏：左边当前工作目录（可点切换），右边平均缓存命中率 + 会话费用（可点配置）。
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
    val messages = conversation.currentMessages
    val cacheHitRate = remember(messages, subAgentUsages) {
        val usages = messages.map { it.usage } + subAgentUsages.map {
            TokenUsage(
                promptTokens = it.promptTokens.toInt(),
                completionTokens = it.completionTokens.toInt(),
                cachedTokens = it.cachedTokens.toInt(),
            )
        }
        CostCalculator.cacheHitRate(usages)
    }
    // 按显示货币计价：USD 走美元官方价，RMB 走人民币官方价（无则按汇率换算）
    val totalCost = remember(messages, subAgentUsages, settings.costCurrency, settings.costUsdCnyRate) {
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
            costFor(uuid, TokenUsage(u.promptTokens.toInt(), u.completionTokens.toInt(), u.cachedTokens.toInt()))
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
            if (ready) {
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
                            showCwdSheet = true
                        }
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

            val cacheText = cacheHitRate?.let { "平均缓存 ${"%.2f".format(it * 100)}%" } ?: "平均缓存 -"
            val costText = if (settings.costCurrency == CostCurrency.USD) {
                CostCalculator.formatUsd(totalCost)
            } else {
                CostCalculator.formatCny(totalCost)
            }
            Text(
                text = "$cacheText · $costText",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        hapticController.perform(HapticFeedbackType.KeyboardTap)
                        showCostSheet = true
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
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
}
