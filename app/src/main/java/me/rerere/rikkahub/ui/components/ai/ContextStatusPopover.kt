package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.cost.CostCalculator
import me.rerere.rikkahub.data.ai.cost.CostCurrency
import me.rerere.rikkahub.data.datastore.FooterIndicator
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * 上下文状态浮窗：锚定在顶栏上下文圆圈图标处，从图标位置缩放展开、右缘对齐窗口右缘。
 * 展示上下文占用与可勾选的会话指标（平均缓存/余额/费用等），
 * 并提供「压缩历史」与「管理控制台」入口。
 *
 * 动画采用 CompletionPopup 同款非持久 MutableTransitionState 模式（而非常驻
 * Popup + scale 动画），规避 MIUI 上常驻动画 Popup 导致键盘僵死的问题。
 */
@Composable
fun ContextStatusPopover(
    expanded: Boolean,
    onDismiss: () -> Unit,
    settings: Settings,
    conversation: Conversation,
    contextTotalTokens: Int,
    contextUsagePercent: Float,
    contextLimitLabel: String,
    onCompressClick: () -> Unit,
    onOpenConsole: () -> Unit,
    modifier: Modifier = Modifier,
    anchor: @Composable () -> Unit,
) {
    var anchorHeight by remember { mutableIntStateOf(0) }
    // 锚点右缘在窗口坐标系中的 x 坐标：浮窗右缘固定对齐窗口右缘（留边距），
    // 避免不同顶栏内边距/设备圆角 inset 下浮窗与右侧「错位」
    var anchorRight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val view = LocalView.current
    val edgeMarginPx = with(density) { 8.dp.toPx() }.toInt()
    val transition = remember { MutableTransitionState(expanded) }
    transition.targetState = expanded

    Box(
        modifier
            .onSizeChanged { anchorHeight = it.height }
            .onGloballyPositioned { coords ->
                anchorRight = (coords.positionInWindow().x + coords.size.width).toInt()
            }
    ) {
        anchor()
        if (transition.currentState || transition.targetState) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(
                    // 让浮窗右缘 = 窗口右缘 - 边距：x 偏移 = 目标右缘 - 锚点右缘（可为负，即向左让位）
                    x = (view.width - edgeMarginPx) - anchorRight,
                    y = anchorHeight,
                ),
                onDismissRequest = onDismiss,
                properties = PopupProperties(focusable = true),
            ) {
                AnimatedVisibility(
                    visibleState = transition,
                    enter = scaleIn(
                        animationSpec = tween(200, easing = FastOutSlowInEasing),
                        // 从图标（右上）方向缩放展开
                        transformOrigin = TransformOrigin(1f, 0f),
                        initialScale = 0.85f,
                    ) + fadeIn(animationSpec = tween(150)),
                    exit = scaleOut(
                        animationSpec = tween(150),
                        transformOrigin = TransformOrigin(1f, 0f),
                    ) + fadeOut(animationSpec = tween(100)),
                ) {
                    ContextStatusPanel(
                        settings = settings,
                        conversation = conversation,
                        contextTotalTokens = contextTotalTokens,
                        contextUsagePercent = contextUsagePercent,
                        contextLimitLabel = contextLimitLabel,
                        onCompressClick = onCompressClick,
                        onOpenConsole = onOpenConsole,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextStatusPanel(
    settings: Settings,
    conversation: Conversation,
    contextTotalTokens: Int,
    contextUsagePercent: Float,
    contextLimitLabel: String,
    onCompressClick: () -> Unit,
    onOpenConsole: () -> Unit,
) {
    val conversationRepository: ConversationRepository = koinInject()

    // 会话级统计：主模型消息 + 当前会话子代理用量（任务终态落库后并入）合并计算
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

    // 指标数据：当前模型 / 供应商余额 / 本会话用量
    val currentModel = settings.getCurrentChatModel()
    val currentProviderForBalance = currentModel?.findProvider(settings.providers)
    val balanceSupported = currentProviderForBalance?.balanceOption?.enabled == true &&
        currentProviderForBalance is ProviderSetting.OpenAI
    val sessionTokenUsages = remember(conversation, subAgentUsages) {
        conversation.currentMessages.map { it.usage } + subAgentUsages.map {
            TokenUsage(
                promptTokens = it.promptTokens.toInt(),
                completionTokens = it.completionTokens.toInt(),
                cachedTokens = it.cachedTokens.toInt(),
                cacheWriteTokens = it.cacheWriteTokens.toInt(),
            )
        }
    }
    val sessionPromptTokens = sessionTokenUsages.sumOf { (it?.promptTokens ?: 0).toLong() }
    val sessionCompletionTokens = sessionTokenUsages.sumOf { (it?.completionTokens ?: 0).toLong() }
    val sessionCachedTokens = sessionTokenUsages.sumOf { (it?.cachedTokens ?: 0).toLong() }
    val sessionMessageCount = conversation.currentMessages.size

    var showCostSheet by remember { mutableStateOf(false) }
    val costSymbol = if (settings.costCurrency == CostCurrency.USD) "$" else "¥"
    val costStr = costSymbol + "%05.2f".format(totalCost)
    val tokensStr = "P ${formatK(sessionPromptTokens)} · O ${formatK(sessionCompletionTokens)}" +
        if (sessionCachedTokens > 0) " · C ${formatK(sessionCachedTokens)}" else ""
    val messagesStr = "${formatCount(sessionMessageCount)} 条"

    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .width(300.dp)
            .heightIn(max = 480.dp),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 上下文占用 + 压缩入口
            Column(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.chat_page_context_usage_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = onCompressClick,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.chat_page_compress_context),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { contextUsagePercent.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                    Text(
                        text = "${formatCount(contextTotalTokens)} / $contextLimitLabel",
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // 指标区：由管理控制台「上下文浮窗显示」勾选项驱动；全部关闭时给一行轻提示，
            // 避免浮窗只剩进度条与控制台入口时被误认为异常
            val visibleIndicators = settings.displaySetting.footerIndicators
                .distinct()
                .filterNot { it == FooterIndicator.GLOBAL_USAGE }
            if (visibleIndicators.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_page_context_popover_indicators_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            } else {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    visibleIndicators.forEach { indicator ->
                        FooterIndicatorView(
                            indicator = indicator,
                            modelLabel = currentModel?.displayName ?: "-",
                            providerForBalance = currentProviderForBalance,
                            balanceSupported = balanceSupported,
                            cacheHitRate = cacheHitRate,
                            costStr = costStr,
                            tokensStr = tokensStr,
                            messagesStr = messagesStr,
                            onOpenCostSheet = { showCostSheet = true },
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // 管理控制台入口
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onOpenConsole() }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.ServerStack01,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.setting_page_management_console),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showCostSheet) {
        CostConfigSheet(
            settings = settings,
            currentModelId = currentModel?.modelId,
            onDismiss = { showCostSheet = false },
        )
    }
}

/**
 * 浮窗指标文本：数字等宽（tnum）保证每位数字等宽，数字变化宽度不变、不推移相邻项。
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
        .padding(vertical = 2.dp)
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

@Composable
private fun FooterIndicatorView(
    indicator: FooterIndicator,
    modelLabel: String,
    providerForBalance: ProviderSetting?,
    balanceSupported: Boolean,
    cacheHitRate: Double?,
    costStr: String,
    tokensStr: String,
    messagesStr: String,
    onOpenCostSheet: () -> Unit,
) {
    when (indicator) {
        FooterIndicator.CURRENT_MODEL -> IndicatorText(text = modelLabel)
        FooterIndicator.PROVIDER_BALANCE -> {
            if (balanceSupported && providerForBalance != null) {
                ProviderBalanceText(
                    providerSetting = providerForBalance,
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            } else {
                IndicatorText(text = "余额 -")
            }
        }
        FooterIndicator.CACHE_HIT_RATE -> {
            val cacheStr = if (cacheHitRate != null) {
                "平均缓存 " + "%05.2f".format(cacheHitRate * 100) + "%"
            } else {
                "平均缓存 -"
            }
            IndicatorText(text = cacheStr)
        }
        FooterIndicator.COST -> IndicatorText(
            text = costStr,
            onClick = onOpenCostSheet,
        )
        FooterIndicator.TOKENS -> IndicatorText(text = tokensStr)
        FooterIndicator.MESSAGES -> IndicatorText(text = messagesStr)
        // 会话用量已下线（旧存档中可能残留该枚举值）：不再展示，仅保留条目兼容反序列化
        FooterIndicator.GLOBAL_USAGE -> {}
    }
}

private fun formatK(value: Long): String = when {
    value >= 1_000_000 -> "%.2fM".format(value / 1_000_000.0)
    value >= 1_000 -> "%.1fk".format(value / 1_000.0)
    else -> value.toString()
}

private fun formatCount(value: Int): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 1_000 -> "%.1fk".format(value / 1_000.0)
    else -> value.toString()
}