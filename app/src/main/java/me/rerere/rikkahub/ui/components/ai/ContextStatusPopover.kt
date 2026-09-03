package me.rerere.rikkahub.ui.components.ai

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.math.roundToInt
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Codesandbox
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.FolderLocked
import me.rerere.hugeicons.stroke.ServerStack01
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.CompositionCategory
import me.rerere.rikkahub.data.ai.ContextComposition
import me.rerere.rikkahub.data.ai.ContextCompositionStore
import me.rerere.rikkahub.data.ai.estimateFallbackComposition
import me.rerere.rikkahub.data.ai.hasRealMessages
import me.rerere.rikkahub.data.ai.hasStaleCalibrationAnchor
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
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderSettings
import me.rerere.workspace.WorkspaceShellStatus
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * 上下文状态浮窗：锚定在顶栏上下文圆圈图标处，从图标位置缩放展开、右缘对齐窗口右缘。
 * 展示上下文占用（含「构成详情」：系统提示/系统工具/MCP/技能/消息 token 占比，默认全部展示）、
 * 可勾选的会话指标（平均缓存/余额/费用等），并提供「压缩历史」与「管理控制台」入口。
 *
 * 实现为「页内覆盖层」而非独立 Popup 窗口，核心原因是消抖：
 * Popup 模式下点击锚点圆圈会同时触发 Popup dismiss 与图标 toggle（点击在浮窗边界外），
 * 旧实现靠协程延迟写回错位，快速连点时延迟写回会落在「重新打开」之后把浮窗又关掉 → 闪烁。
 * 页内覆盖层只有一个 toggle 入口（图标）+ 全窗点击拦截层，toggle 由 300ms 消抖门闩拦截连点，
 * 从根上消除闪烁。
 *
 * 面板背景沿用实色 surfaceContainerHigh（毛玻璃方案曾在 blur 分支加 hazeBlur，观感不佳已回退）；
 * 动画仍走非持久 MutableTransitionState 模式（同 CompletionPopup），无常驻动画窗口。
 */
@Composable
fun ContextStatusOverlay(
    transition: MutableTransitionState<Boolean>,
    onDismiss: () -> Unit,
    settings: Settings,
    conversation: Conversation,
    contextTotalTokens: Int,
    contextUsagePercent: Float,
    contextLimitLabel: String,
    onCompressClick: () -> Unit,
    onOpenConsole: () -> Unit,
    /** 锚点圆圈底边在窗口坐标系的 y（px）：面板顶部对齐图标底边 */
    anchorBottomPx: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // 展开期间不可交互：targetState 为 false（退出动画中）时点击穿透，不拦截
    val dismissEnabled = transition.targetState
    // 覆盖层自身（=页面根 Box）顶边在窗口坐标系的 y：面板偏移 = 锚点底边 - 覆盖层顶边，
    // 用窗口绝对坐标求差，避免外层容器 inset/padding 差异导致面板落点偏移
    var overlayTopPx by remember { mutableIntStateOf(0) }

    // 全窗点击拦截层：浮窗打开时任意外部点击 → 收起（原 Popup(focusable=true) 的模态语义）
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayTopPx = it.positionInWindow().y.roundToInt() }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = dismissEnabled,
            ) { onDismiss() },
    ) {
        // 面板：锚定在图标正下方、右缘贴窗口右缘（8dp 边距，与既有 Popup 版对齐口径一致）
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(
                    top = with(density) { (anchorBottomPx - overlayTopPx).coerceAtLeast(0).toDp() },
                    end = 8.dp,
                ),
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
    // 返回键收起
    BackHandler(enabled = dismissEnabled, onBack = onDismiss)
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
    val workspaceRepository: WorkspaceRepository = koinInject()
    val workspaces by workspaceRepository.listFlow().collectAsState(initial = emptyList())
    val trustedFolderRepository: TrustedFolderRepository = koinInject()
    val trustedSettings by trustedFolderRepository.settingsFlow.collectAsState(initial = TrustedFolderSettings())

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
    // 预设剔除/兜底必须用会话绑定的助手（getCurrentAssistant 是全局当前助手，切换后与旧会话不一致）
    val storeSnapshot = ContextCompositionStore.get(conversation.id.toString())
    val hasCompositionSnapshot = storeSnapshot != null
    val assistantForPreset = settings.getAssistantById(conversation.assistantId)
        ?: settings.getCurrentAssistant()
    // 绑定环境（与「＋」面板工作区/信任文件夹卡片同源）：助手级工作区绑定 + 信任文件夹项目绑定
    val boundWorkspace = assistantForPreset.workspaceId?.let { wid ->
        workspaces.find { it.id == wid.toString() }
    }
    val boundTrustedProject = assistantForPreset.trustedFolderProjectId?.let { pid ->
        trustedSettings.projects.find { it.id == pid }
    }
    val composition = storeSnapshot
        ?.let { s ->
            // 与顶栏同口径：压缩后且 usage 锚点仍为压缩前旧请求时跳过实测校准（见 hasStaleCalibrationAnchor）
            if (conversation.hasStaleCalibrationAnchor()) {
                s
            } else {
                s.calibratedWith(conversation.effectiveMessages().lastRealPromptTokens())
            }
        }
        ?: if (conversation.hasRealMessages(assistantForPreset.presetMessages)) {
            estimateFallbackComposition(conversation, settings)
        } else {
            null
        }
    // 实测口径（与顶栏 computeTokenStats 同源）：快照存在 + 校准锚未过期 + 有 provider 实测
    // 输入量。有实测锚时总量=实测值，标注「实测」；否则（新会话首轮/锚过期）标注「估算」。
    val measured = storeSnapshot != null &&
        !conversation.hasStaleCalibrationAnchor() &&
        conversation.effectiveMessages().lastRealPromptTokens() != null

    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .width(300.dp)
            .heightIn(max = 480.dp)
            // 消费面板空白处的点击：否则会穿透到全窗拦截层，点内部留白也会收起浮窗
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {},
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
                    // 口径标注：总量已被 provider 实测校准（usage 锚有效）时标「实测」，
                    // 否则标「估算」（无本地 tokenizer，字符估算口径明示避免误解）
                    if (measured) {
                        MeasuredTag()
                    } else {
                        EstimateTag()
                    }
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
                // 构成详情：默认全部展示（不折叠）
                CompositionBreakdownSection(
                    composition = composition,
                    hasSnapshot = hasCompositionSnapshot,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            // 绑定环境区：助手绑定的工作区 / 信任文件夹，仅有绑定时出现（含分隔线）；
            // 工作区 shell 未就绪时整行置灰——工具未装配，绑定暂不生效
            if (boundWorkspace != null || boundTrustedProject != null) {
                val workspaceReady = boundWorkspace != null &&
                    boundWorkspace.shellStatus == WorkspaceShellStatus.READY.name
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (boundWorkspace != null) {
                        BindingRow(
                            icon = HugeIcons.Codesandbox,
                            label = stringResource(R.string.assistant_page_workspace),
                            value = if (workspaceReady) {
                                "${boundWorkspace.name} · ${conversation.workspaceCwd ?: "/workspace"}"
                            } else {
                                boundWorkspace.name
                            },
                            dimmed = !workspaceReady,
                        )
                    }
                    if (boundTrustedProject != null) {
                        BindingRow(
                            icon = HugeIcons.FolderLocked,
                            label = "信任文件夹",
                            value = boundTrustedProject.name,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }

            // 指标区（行式）：模型历史一行；输入输出与平均缓存（默认最常见两项）合并为一行，
            // 减少浮窗纵向高度，由管理控制台「上下文浮窗显示」勾选项驱动；全部关闭时给一行
            // 轻提示，避免浮窗只剩进度条与控制台入口时被误认为异常
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

/** 「实测」轻量标签：总量已被最近一次 provider 实测输入量校准（usage 锚点有效）。 */
@Composable
private fun MeasuredTag() {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
    ) {
        Text(
            text = stringResource(R.string.chat_page_context_measured),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            maxLines = 1,
        )
    }
}

/** 构成详情区：不做折叠，默认全部展示（系统/内置工具/MCP/技能/消息各一行占比条）。 */
@Composable
private fun CompositionBreakdownSection(
    composition: ContextComposition?,
    hasSnapshot: Boolean,
) {
    Column {
        Text(
            text = stringResource(R.string.chat_page_context_composition_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (composition == null || composition.totalTokens <= 0) {
            // 未开始的会话（或构成全空）：占用为 0，给空态引导而非展示 0% 行
            Text(
                text = stringResource(R.string.chat_page_context_composition_none),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        } else {
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
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

private fun CompositionCategory.labelRes(): Int = when (this) {
    CompositionCategory.SYSTEM -> R.string.chat_page_context_composition_system
    CompositionCategory.BUILTIN_TOOLS -> R.string.chat_page_context_composition_builtin_tools
    CompositionCategory.MCP_TOOLS -> R.string.chat_page_context_composition_mcp
    CompositionCategory.SKILLS -> R.string.chat_page_context_composition_skills
    CompositionCategory.MESSAGES -> R.string.chat_page_context_composition_messages
}

/**
 * 指标区（行式）：模型历史占一行；输入输出与平均缓存合并为一行（默认最常见两项，
 * 同行展示减少浮窗纵向高度），只勾选其一或其余项时保持独立行。全部由管理控制台
 * 「上下文浮窗显示」勾选项驱动。
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
        val showTokens = FooterIndicator.TOKENS in indicators
        val showCache = FooterIndicator.CACHE_HIT_RATE in indicators
        if (showTokens && showCache) {
            // 输入输出 + 平均缓存同一行：缓存居右
            TokensCacheRow(
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                cachedTokens = cachedTokens,
                cacheHitRate = cacheHitRate,
                compact = true,
            )
        } else {
            if (showTokens) {
                TokensCacheRow(
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    cachedTokens = cachedTokens,
                    cacheHitRate = null,
                    compact = false,
                )
            }
            if (showCache) {
                CacheHitRateText(cacheHitRate = cacheHitRate, rightAlign = false)
            }
        }
    }
}

/** 输入/输出行（缓存以括号形式挂在输入项内，与消息下方 NerdLine 同款）；[compact] 时末尾并入平均缓存。 */
@Composable
private fun TokensCacheRow(
    promptTokens: Long,
    completionTokens: Long,
    cachedTokens: Long,
    cacheHitRate: Double?,
    compact: Boolean,
) {
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
        if (compact) {
            Spacer(Modifier.weight(1f))
            CacheHitRateText(cacheHitRate = cacheHitRate, rightAlign = true)
        }
    }
}

/**
 * 绑定环境行：图标 + 类别 + 值的行式布局，标签列宽与构成详情行对齐；
 * [dimmed] 用于工作区 shell 未就绪时整行置灰（此时工具未装配，绑定暂不生效）。
 */
@Composable
private fun BindingRow(
    icon: ImageVector,
    label: String,
    value: String,
    dimmed: Boolean = false,
) {
    val alpha = if (dimmed) 0.4f else 1f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            modifier = Modifier.width(60.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
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

/** 平均缓存单项：单独成行（[rightAlign] = false）或并入输入输出行居右（true）。 */
@Composable
private fun CacheHitRateText(
    cacheHitRate: Double?,
    rightAlign: Boolean,
) {
    val cacheStr = if (cacheHitRate != null) {
        "平均缓存 " + "%05.2f".format(cacheHitRate * 100) + "%"
    } else {
        "平均缓存 -"
    }
    Text(
        text = cacheStr,
        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = if (rightAlign) TextOverflow.Ellipsis else TextOverflow.Clip,
        textAlign = if (rightAlign) TextAlign.End else TextAlign.Start,
    )
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