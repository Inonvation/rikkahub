package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.math.roundToInt
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.CompositionCategory
import me.rerere.rikkahub.data.ai.ContextComposition
import me.rerere.rikkahub.data.ai.ContextCompositionStore
import me.rerere.rikkahub.data.ai.estimateFallbackComposition
import me.rerere.rikkahub.data.ai.hasRealMessages
import me.rerere.rikkahub.data.ai.lastRealPromptTokens
import me.rerere.rikkahub.data.ai.cost.CostCalculator
import me.rerere.rikkahub.data.ai.cost.CostCurrency
import me.rerere.rikkahub.data.datastore.FooterIndicator
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.dropPresetMessages
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.ui.components.message.getSectionExpanded
import me.rerere.rikkahub.ui.components.message.setSectionExpanded
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * 上下文状态浮窗：锚定在顶栏上下文圆圈图标处，从图标位置缩放展开、右缘对齐窗口右缘。
 * 展示上下文占用（含可折叠的「构成详情」：系统提示/系统工具/MCP/技能/消息 token 占比）、
 * 可勾选的会话指标（平均缓存/余额/费用等），并提供「压缩历史」与「管理控制台」入口。
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
    // 会话轮数：以助手完成回复的消息数计（一次问答 = 一轮）；预设消息（开场展示）不计
    val sessionRounds = remember(conversation, settings) {
        conversation.currentMessages
            .dropPresetMessages(
                settings.getAssistantById(conversation.assistantId)?.presetMessages.orEmpty(),
            )
            .count { it.role == MessageRole.ASSISTANT }
    }
    // 会话中使用过的模型（按出现顺序去重；modelName 为生成时快照，缺失回退配置解析）
    val modelHistory = remember(conversation, settings) {
        conversation.currentMessages.mapNotNull { msg ->
            msg.modelName?.takeIf { it.isNotBlank() }
                ?: msg.modelId?.let { settings.findModelById(it)?.displayName }
        }.distinct().joinToString(" → ")
    }

    var showCostSheet by remember { mutableStateOf(false) }
    val costSymbol = if (settings.costCurrency == CostCurrency.USD) "$" else "¥"
    val costStr = costSymbol + "%05.2f".format(totalCost)

    // 上下文构成：优先最近一次生成的快照（有 provider 实测输入量时按实测校准总量，
    // 比例保持估算口径）；无快照时仅对已开始的会话（有真实消息）做兜底估算——
    // 系统提示 + 消息历史的字符估算；未开始的会话没有任何请求发生过，构成置空，
    // 浮窗给出「发送消息后统计」的空态引导，而不是把系统提示配置当占用
    val storeSnapshot = ContextCompositionStore.get(conversation.id.toString())
    val hasCompositionSnapshot = storeSnapshot != null
    val assistantForPreset = settings.getCurrentAssistant()
    val composition = storeSnapshot
        ?.calibratedWith(conversation.effectiveMessages().lastRealPromptTokens())
        ?: if (conversation.hasRealMessages(assistantForPreset.presetMessages)) {
            estimateFallbackComposition(conversation, settings)
        } else {
            null
        }

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
            // 上下文占用 + 构成详情 + 压缩入口
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
                    Spacer(Modifier.width(6.dp))
                    // 上下文占用为字符估算口径（无本地 tokenizer），明示估算避免误解
                    EstimateTag()
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
                        text = "${formatCount(contextTotalTokens)} / $contextLimitLabel" +
                            " (${(contextUsagePercent * 100).roundToInt()}%)",
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                // 构成详情：默认折叠，展开动画复用 chat 消息区块的 AnimatedVisibility 模式
                CompositionBreakdownSection(
                    conversationId = conversation.id.toString(),
                    composition = composition,
                    hasSnapshot = hasCompositionSnapshot,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // 指标区（行式）：模型历史 / 输入输出 / 平均缓存各占一行，由管理控制台
            // 「上下文浮窗显示」勾选项驱动；全部关闭时给一行轻提示，避免浮窗只剩
            // 进度条与控制台入口时被误认为异常
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
                SessionIndicators(
                    indicators = visibleIndicators,
                    modelHistory = modelHistory,
                    promptTokens = sessionPromptTokens,
                    completionTokens = sessionCompletionTokens,
                    cachedTokens = sessionCachedTokens,
                    cacheHitRate = cacheHitRate,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // 底部行：会话轮数 / 消耗金额 / 余额（受勾选控制，按序排列）+ 管理控制台入口居右
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                if (FooterIndicator.MESSAGES in visibleIndicators) {
                    Text(
                        text = "$sessionRounds 轮",
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (FooterIndicator.COST in visibleIndicators) {
                    Text(
                        text = costStr,
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { showCostSheet = true },
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (FooterIndicator.PROVIDER_BALANCE in visibleIndicators) {
                    // balanceSupported 蕴含当前 provider 非空且为 OpenAI（余额接口支持）
                    if (balanceSupported) {
                        ProviderBalanceText(
                            providerSetting = currentProviderForBalance,
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    } else {
                        Text(
                            text = "余额 -",
                            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Spacer(Modifier.weight(1f))
                // 管理控制台入口（右侧）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onOpenConsole() }
                        .padding(start = 8.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.ServerStack01,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.setting_page_management_console),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

/** 「估算」轻量标签：上下文占用为字符估算而非 provider 实测，避免数值被误解为官方计费。 */
@Composable
private fun EstimateTag() {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
    ) {
        Text(
            text = stringResource(R.string.chat_page_context_estimated),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            maxLines = 1,
        )
    }
}

/** 展开/折叠的构成详情区。折叠态记忆复用 SectionExpandStore（key 前缀 `popover:`）。 */
@Composable
private fun CompositionBreakdownSection(
    conversationId: String,
    composition: ContextComposition?,
    hasSnapshot: Boolean,
) {
    val expandedKey = "popover:$conversationId:composition"
    var expanded by remember(expandedKey) {
        mutableStateOf(getSectionExpanded(expandedKey) ?: false)
    }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing),
        label = "composition-chevron",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                expanded = !expanded
                setSectionExpanded(expandedKey, expanded)
            }
            .padding(vertical = 2.dp),
    ) {
        Icon(
            imageVector = HugeIcons.ArrowDown01,
            contentDescription = null,
            modifier = Modifier
                .size(14.dp)
                .graphicsLayer { rotationZ = rotation },
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.chat_page_context_composition_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
    }
    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically(
            animationSpec = tween(200, easing = FastOutSlowInEasing),
        ) + fadeIn(animationSpec = tween(150)),
        exit = shrinkVertically(animationSpec = tween(150)) + fadeOut(animationSpec = tween(100)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (composition == null || composition.totalTokens <= 0) {
                // 未开始的会话（或构成全空）：占用为 0，给空态引导而非展示 0% 行
                Text(
                    text = stringResource(R.string.chat_page_context_composition_none),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            } else {
                // 概要：按占比由大到小列出非零项，一眼可见「上下文花在哪」
                Text(
                    text = CompositionSummaryText(composition),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
                // 按消耗从高到低排列（稳定排序，等值保持原有类别顺序）；
                // 无快照时工具/MCP/技能尚未采样，只列非零项，避免「占 0%」误导
                val rows = if (hasSnapshot) {
                    CompositionCategory.entries
                } else {
                    CompositionCategory.entries.filter { composition.tokensOf(it) > 0 }
                }
                rows
                    .sortedByDescending { composition.tokensOf(it) }
                    .forEach { category ->
                        CompositionRow(
                            category = category,
                            tokens = composition.tokensOf(category),
                            totalTokens = composition.totalTokens,
                        )
                    }
                Text(
                    text = stringResource(
                        if (hasSnapshot) {
                            R.string.chat_page_context_composition_note
                        } else {
                            R.string.chat_page_context_composition_no_snapshot_note
                        }
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun CompositionRow(
    category: CompositionCategory,
    tokens: Int,
    totalTokens: Int,
) {
    val fraction = if (totalTokens > 0) tokens / totalTokens.toFloat() else 0f
    // 占比条平滑过渡：数据随生成刷新时数值变化有动画，避免跳变
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(300),
        label = "composition-bar",
    )
    val enabled = tokens > 0
    val dimAlpha = if (enabled) 1f else 0.4f
    val label = stringResource(category.labelRes())
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 类别标记：小圆点（与占比条同色），取代图标保持行内简洁
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = dimAlpha),
                    shape = RoundedCornerShape(50),
                ),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dimAlpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(60.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedFraction)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Text(
            text = formatK(tokens.toLong()),
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dimAlpha),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(40.dp),
        )
        Text(
            text = "${(fraction * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dimAlpha),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(34.dp),
        )
    }
}

/** 折叠态概要文案：按 token 数由大到小列出非零构成项（如「消息 14.2k · 系统 6.8k」）。 */
@Composable
private fun CompositionSummaryText(composition: ContextComposition): String {
    val labels = listOf(
        stringResource(R.string.chat_page_context_composition_system) to composition.systemTokens,
        stringResource(R.string.chat_page_context_composition_builtin_tools) to composition.builtinToolTokens,
        stringResource(R.string.chat_page_context_composition_mcp) to composition.mcpToolTokens,
        stringResource(R.string.chat_page_context_composition_skills) to composition.skillToolTokens,
        stringResource(R.string.chat_page_context_composition_messages) to composition.messageTokens,
    ).filter { it.second > 0 }.sortedByDescending { it.second }
    return labels.joinToString(" · ") { (label, value) -> "$label ${formatK(value.toLong())}" }
}

private fun CompositionCategory.labelRes(): Int = when (this) {
    CompositionCategory.SYSTEM -> R.string.chat_page_context_composition_system
    CompositionCategory.BUILTIN_TOOLS -> R.string.chat_page_context_composition_builtin_tools
    CompositionCategory.MCP_TOOLS -> R.string.chat_page_context_composition_mcp
    CompositionCategory.SKILLS -> R.string.chat_page_context_composition_skills
    CompositionCategory.MESSAGES -> R.string.chat_page_context_composition_messages
}

/**
 * 指标区（行式）：模型历史 / 输入输出 / 平均缓存各占一行，由管理控制台
 * 「上下文浮窗显示」勾选项驱动。行式布局保证各指标纵向对齐、一目了然。
 */
@Composable
private fun SessionIndicators(
    indicators: List<FooterIndicator>,
    modelHistory: String,
    promptTokens: Long,
    completionTokens: Long,
    cachedTokens: Long,
    cacheHitRate: Double?,
) {
    Column(
        modifier = Modifier.padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (FooterIndicator.CURRENT_MODEL in indicators) {
            // 模型历史：会话中途切换模型时按出现顺序全部列出（modelName 为生成时快照）
            Text(
                text = modelHistory,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (FooterIndicator.TOKENS in indicators) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TokensItem(
                    icon = HugeIcons.Upload02,
                    text = formatK(promptTokens) +
                        if (cachedTokens > 0) " (${formatK(cachedTokens)} cached)" else "",
                )
                TokensItem(
                    icon = HugeIcons.Download04,
                    text = formatK(completionTokens),
                )
            }
        }
        if (FooterIndicator.CACHE_HIT_RATE in indicators) {
            val cacheStr = if (cacheHitRate != null) {
                "平均缓存 " + "%05.2f".format(cacheHitRate * 100) + "%"
            } else {
                "平均缓存 -"
            }
            Text(
                text = cacheStr,
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 输入/输出单项：图标 + 数值（缓存以括号形式挂在输入项内，与消息下方 NerdLine 同款）。 */
@Composable
private fun TokensItem(
    icon: ImageVector,
    text: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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