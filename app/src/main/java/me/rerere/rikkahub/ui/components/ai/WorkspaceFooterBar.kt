package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.TokenUsage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.SlidersVertical
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.cost.CostCalculator
import me.rerere.rikkahub.data.ai.cost.CostCurrency
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.model.AgentBehaviorProfile
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.resolveConversationPolicy
import me.rerere.rikkahub.data.model.resolveModeRef
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * 对话输入框下方单行栏：左侧模式 chip（点击切换），右侧统计指标。
 * 极简模式下只显示模式 chip。
 * 工作区/信任文件夹入口已迁入「+」菜单，不再在此展示。
 */
@Composable
fun WorkspaceFooterBar(
    assistant: Assistant,
    conversation: Conversation,
    settings: Settings,
    modeSwitchEnabled: Boolean,
    onSwitchMode: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trustedFolderRepository: TrustedFolderRepository = koinInject()
    val trustedSettings by trustedFolderRepository.settingsFlow.collectAsState(initial = TrustedFolderSettings())
    val activeTrustedProject = trustedSettings.projects.find { it.id == trustedSettings.activeProjectId }
    val navController = LocalNavController.current

    val scope = rememberCoroutineScope()
    val hapticController = rememberHaptic()
    val toaster = LocalToaster.current
    val lockedModeDesc = stringResource(R.string.chat_mode_locked_desc)
    var showModePicker by remember { mutableStateOf(false) }

    // 会话级统计：主模型消息 + 当前会话子代理用量（任务终态落库后并入）合并计算
    val conversationRepository: ConversationRepository = koinInject()
    val subAgentUsages by conversationRepository.observeSubAgentUsage(conversation.id.toString())
        .collectAsState(initial = emptyList())
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
    val totalCost = remember(
        conversation, subAgentUsages,
        settings.costCurrency, settings.costUsdCnyRate, settings.modelPricingOverrides,
    ) {
        val messages = conversation.currentMessages
        fun costFor(modelId: Uuid?, usage: TokenUsage?, timeMillis: Long?): Double {
            val resolved = modelId?.let { settings.findModelById(it) } ?: settings.getCurrentChatModel()
            return when (settings.costCurrency) {
                CostCurrency.USD -> CostCalculator.costUsd(
                    resolved?.modelId, usage, settings.modelPricingOverrides, timeMillis,
                )
                CostCurrency.RMB -> CostCalculator.costCny(
                    resolved?.modelId, usage, settings.modelPricingOverrides, settings.costUsdCnyRate, timeMillis,
                )
            }
        }
        val mainCost = messages.sumOf { message ->
            val timeMillis = message.createdAt
                .toInstant(TimeZone.currentSystemDefault())
                .toEpochMilliseconds()
            costFor(message.modelId, message.usage, timeMillis)
        }
        val subCost = subAgentUsages.sumOf { u ->
            val uuid = u.modelId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            costFor(
                uuid,
                TokenUsage(
                    promptTokens = u.promptTokens.toInt(),
                    completionTokens = u.completionTokens.toInt(),
                    cachedTokens = u.cachedTokens.toInt(),
                    cacheWriteTokens = u.cacheWriteTokens.toInt(),
                ),
                u.createdAt,
            )
        }
        mainCost + subCost
    }

    // 当前生效模式引用：旧会话未快照时按助手/全局现算
    val effectiveModeRef = conversation.mode
        ?: resolveModeRef(
            assistant = assistant,
            settings = settings,
            trustedFolderActive = activeTrustedProject != null,
        )
    val modeLabel = modeRefDisplayName(effectiveModeRef, settings.customModes, settings.builtinModeOverrides)
    val effectivePolicy = resolveConversationPolicy(
        conversation = conversation,
        assistant = assistant,
        settings = settings,
        trustedFolderActive = activeTrustedProject != null,
    )
    val minimal = effectivePolicy.behaviorProfile == AgentBehaviorProfile.MINIMAL

    Surface(color = Color.Transparent, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 模式 chip：未发送消息且未生成中时可点击切换；锁定后置灰仅展示
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            if (modeSwitchEnabled) {
                                hapticController.perform(HapticFeedbackType.KeyboardTap)
                                showModePicker = true
                            } else {
                                // 有消息/生成中：模式锁定，给出提示而不是无响应
                                toaster.show(
                                    message = lockedModeDesc,
                                    type = ToastType.Normal,
                                )
                            }
                        },
                        onLongClick = {
                            hapticController.perform(HapticFeedbackType.KeyboardTap)
                            navController.navigate(Screen.SettingModes)
                        }
                    )
                    .padding(vertical = 4.dp, horizontal = 6.dp)
                    .alpha(if (modeSwitchEnabled) 1f else 0.6f),
            ) {
                Icon(
                    imageVector = HugeIcons.SlidersVertical,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = modeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!minimal) {
                Spacer(Modifier.weight(1f))
                // 右侧指标：平均缓存命中率 / 会话费用
                var showCostSheet by remember { mutableStateOf(false) }
                val cacheStr = if (cacheHitRate != null) {
                    "平均缓存 " + "%05.2f".format(cacheHitRate * 100) + "%"
                } else {
                    "平均缓存 00.00%"
                }
                val costSymbol = if (settings.costCurrency == CostCurrency.USD) "$" else "¥"
                val costStr = costSymbol + "%05.2f".format(totalCost)
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
                if (showCostSheet) {
                    CostConfigSheet(
                        settings = settings,
                        currentModelId = settings.getCurrentChatModel()?.modelId,
                        onDismiss = { showCostSheet = false },
                    )
                }
            }
        }
    }

    if (showModePicker) {
        ModePickerSheet(
            selectedRef = conversation.mode,
            customModes = settings.customModes,
            builtinModeOverrides = settings.builtinModeOverrides,
            showFollowGlobal = true,
            onSelect = { ref ->
                showModePicker = false
                onSwitchMode(ref)
            },
            onDismiss = { showModePicker = false },
        )
    }
}

/**
 * footer 指标文本：与整体同字体（无自定义字体族），数字等宽（tnum）保证每位数字等宽，
 * 数字变化宽度不变、不推移相邻项。onClick 非空时可点击（费用段），否则纯展示。
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
            fontFeatureSettings = "tnum",
        ),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
