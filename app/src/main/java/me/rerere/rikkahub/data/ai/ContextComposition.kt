package me.rerere.rikkahub.data.ai

import androidx.compose.runtime.mutableStateMapOf
import kotlin.math.roundToInt
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.dropPresetMessages

/**
 * 附件型 part（图片/视频/音频/文档）的占位 token 估算。
 * 无真实 tokenizer（与全项目一致，见 [estimateTokensByChars]），附件按常见量级固定估算。
 */
internal const val ATTACHMENT_TOKEN_ESTIMATE = 1000

/**
 * 字符 → token 估算（主流 agent 通用启发式，4 字符 ≈ 1 token）。
 * 本项目中文会话占比高，CJK 字符按 1 字符 ≈ 1 token 单独计数，避免统一 /4 系统性低估；
 * 代码/JSON/英文等仍走 4 字符 ≈ 1 token（Codex / Claude Code / DeepSeek 生态的常见口径）。
 */
fun estimateTokensByChars(text: String): Int {
    if (text.isEmpty()) return 0
    var cjk = 0
    var other = 0
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val isCjk = cp in 0x4E00..0x9FFF ||  // 基本区
            cp in 0x3400..0x4DBF ||  // 扩展 A
            cp in 0xF900..0xFAFF ||  // 兼容表意文字
            cp in 0x3040..0x30FF ||  // 假名
            cp in 0x3000..0x303F ||  // CJK 标点
            cp in 0xFF00..0xFFEF     // 全角形式
        if (isCjk) cjk++ else other++
        i += Character.charCount(cp)
    }
    return cjk + (other + 3) / 4
}

/** 单个工具定义的近似 schema token（名称 + 描述 + 参数 JSON）。 */
fun Tool.estimateSchemaTokens(): Int =
    estimateTokensByChars(name) +
        estimateTokensByChars(description) +
        runCatching { estimateTokensByChars(parameters()?.toString() ?: "") }.getOrDefault(0)

private fun UIMessagePart.estimateTokens(): Int = when (this) {
    is UIMessagePart.Text -> estimateTokensByChars(text)
    is UIMessagePart.Reasoning -> estimateTokensByChars(reasoning)
    is UIMessagePart.Tool -> estimateTokensByChars(toolName) +
        estimateTokensByChars(description) +
        estimateTokensByChars(input) +
        output.sumOf { it.estimateTokens() }
    is UIMessagePart.ServerTool -> estimateTokensByChars(toolName) +
        estimateTokensByChars(input?.toString() ?: "") +
        estimateTokensByChars(output?.toString() ?: "")
    is UIMessagePart.Image,
    is UIMessagePart.Video,
    is UIMessagePart.Audio,
    is UIMessagePart.Document,
    -> ATTACHMENT_TOKEN_ESTIMATE
    // 已废弃 part（Search / ToolCall / ToolResult）不再发送，按 0 计
    else -> 0
}

/** 一条消息的近似 token（全部 part 求和）。 */
fun UIMessage.estimateTokens(): Int = parts.sumOf { it.estimateTokens() }

/**
 * 上下文构成类别：与浮窗「构成详情」展示顺序一致。
 */
enum class CompositionCategory {
    SYSTEM,
    BUILTIN_TOOLS,
    MCP_TOOLS,
    SKILLS,
    MESSAGES,
}

/**
 * 一次生成请求的上下文构成快照（token 估算）。
 *
 * 构成口径与主流 agent（Codex / Claude Code）的 context 构成一致：
 * 系统提示词 + 工具定义（系统工具 / MCP / 技能各自拆分）+ 消息历史；
 * 五者之和即「当前上下文占用」，供顶栏圆圈、浮窗进度条、自动压缩共用单一数据源。
 */
data class ContextComposition(
    val systemTokens: Int,
    val builtinToolTokens: Int,
    val mcpToolTokens: Int,
    val skillToolTokens: Int,
    val messageTokens: Int,
) {
    val totalTokens: Int
        get() = systemTokens + builtinToolTokens + mcpToolTokens + skillToolTokens + messageTokens

    fun tokensOf(category: CompositionCategory): Int = when (category) {
        CompositionCategory.SYSTEM -> systemTokens
        CompositionCategory.BUILTIN_TOOLS -> builtinToolTokens
        CompositionCategory.MCP_TOOLS -> mcpToolTokens
        CompositionCategory.SKILLS -> skillToolTokens
        CompositionCategory.MESSAGES -> messageTokens
    }

    /**
     * 用最近一次 provider 实测的输入总量校准估算构成（对标 Claude Code 的
     * count_tokens 校准）：总量变为真实值，各项按估算比例同比例缩放——
     * 五类构成之和 = 真实总量，比例保持估算口径，全程自洽。
     *
     * 估算严重失真（比值超出 [1/4, 4]）或实测缺失时返回自身（不校准），
     * 避免把 provider 异常返回值放大进 UI。
     */
    fun calibratedWith(realTotal: Int?): ContextComposition {
        if (realTotal == null || realTotal <= 0 || totalTokens <= 0) return this
        val ratio = realTotal.toDouble() / totalTokens.toDouble()
        if (ratio < 0.25 || ratio > 4.0) return this
        fun scale(value: Int) = (value * ratio).roundToInt()
        return ContextComposition(
            systemTokens = scale(systemTokens),
            builtinToolTokens = scale(builtinToolTokens),
            mcpToolTokens = scale(mcpToolTokens),
            skillToolTokens = scale(skillToolTokens),
            messageTokens = scale(messageTokens),
        )
    }
}

/**
 * 最近一次请求的实测输入总量（provider 返回的 promptTokens），作为构成估算的
 * 校准锚点。取 [messages] 中最后一条带 usage 的消息——它对应最近一次生成时的
 * 实际发送快照（与快照同一构造源：[effectiveMessages] 的压缩感知列表）。
 * 无任何生成记录时返回 null。
 */
fun List<UIMessage>.lastRealPromptTokens(): Int? =
    lastOrNull { (it.usage?.promptTokens ?: 0) > 0 }?.usage?.promptTokens

/**
 * 会话是否已产生真实消息（扣除预设开场展示）。
 *
 * 未开始（无消息或仅预设消息）的会话还没有发生过任何请求，谈不上
 * 「上下文占用」——顶栏圆圈应显示 0、浮窗构成区给空态引导，而不是把
 * 系统提示配置估算当成占用展示。已开始的会话查询兜底估算才有意义
 * （这些历史消息下次发送时确实会占用窗口）。
 */
fun Conversation.hasRealMessages(presetMessages: List<UIMessage>): Boolean =
    effectiveMessages().dropPresetMessages(presetMessages).isNotEmpty()

/**
 * 校准锚点是否已过时：压缩后、且最后一条带 usage 的消息仍在压缩点（含）之前，
 * 说明最近的 provider 实测输入量来自压缩前的旧请求——用它校准会把压缩后写入的
 * 新构成估算重新拉回压缩前的占用（虚高）。待压缩点之后出现新生成（锚点消息在
 * 压缩点之后）恢复校准。
 *
 * 无压缩、无锚点或锚点无法定位时返回 false（保持旧行为可校准）。
 * 顶栏圆圈与浮窗构成共用此判定（computeTokenStats / ContextStatusPanel）。
 */
fun Conversation.hasStaleCalibrationAnchor(): Boolean {
    val compressed = compressedHistory ?: return false
    val lastOriginalMessageId = compressed.lastOriginalMessageId ?: return false
    val anchorIndex = currentMessages.indexOfLast { (it.usage?.promptTokens ?: 0) > 0 }
    val compressIndex = currentMessages.indexOfFirst { it.id == lastOriginalMessageId }
    if (anchorIndex < 0 || compressIndex < 0) return false
    return anchorIndex <= compressIndex
}

/**
 * 无生成快照（新会话 / 进程重启后未发送）时的兜底构成：系统提示词（助手/会话级
 * 文本，不含注入与工具 system prompt）+ 可见消息历史的字符估算，工具/MCP/技能
 * 在真实采样前不可知，以 0 占位（UI 据此显示「发送后统计」提示而非 0% 误导）。
 *
 * 与 [buildContextComposition] 同口径的估算器，保证无快照时进度条、圆圈与
 * 构成详情行自洽；首轮生成后即被完整快照替换。仅对已产生真实消息的会话生效
 * （空会话见 [hasRealMessages]，占用为 0）。
 */
fun estimateFallbackComposition(
    conversation: Conversation,
    settings: Settings,
): ContextComposition {
    // 预设剔除必须用会话绑定的助手：getCurrentAssistant 是全局当前助手（用户在主界面
    // 切换后与旧会话不一致），用它会在「全局切助手后打开旧会话」时把预设消息重新计入
    // 占用（预设不复存在导致 id 失配，与 hasRealMessages 口径同理）
    val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
    val systemText = if (assistant.allowConversationSystemPrompt && !conversation.customSystemPrompt.isNullOrBlank()) {
        conversation.customSystemPrompt
    } else {
        assistant.systemPrompt
    }
    return ContextComposition(
        systemTokens = estimateTokensByChars(systemText),
        builtinToolTokens = 0,
        mcpToolTokens = 0,
        skillToolTokens = 0,
        // 预设消息（开场展示）不进入上下文占用统计，与发送路径口径一致
        messageTokens = conversation.effectiveMessages()
            .dropPresetMessages(assistant.presetMessages)
            .sumOf { it.estimateTokens() },
    )
}

/**
 * 由一次请求的可发送内容计算构成快照（纯函数，便于单测）。
 *
 * @param systemText 最终 system 文本（含用户资料/工具 prompt/行为层等全部堆叠）
 * @param tools 装配完成的工具列表（GenerationHandler 规范化排序后的全量工具）
 * @param messages transforms 后最终发送列表；合成 system 消息自动剔除，避免与 [systemText] 重复计
 */
fun buildContextComposition(
    systemText: String,
    tools: List<Tool>,
    messages: List<UIMessage>,
): ContextComposition {
    var builtin = 0
    var mcp = 0
    var skill = 0
    for (tool in tools) {
        val tokens = tool.estimateSchemaTokens()
        when {
            tool.name.startsWith("mcp__") -> mcp += tokens
            tool.name.startsWith("use_skill") || tool.name.startsWith("skill_admin_") -> skill += tokens
            else -> builtin += tokens
        }
    }
    val messageTokens = messages
        .filterNot { it.isSynthetic && it.role == MessageRole.SYSTEM }
        .sumOf { it.estimateTokens() }
    return ContextComposition(
        systemTokens = estimateTokensByChars(systemText),
        builtinToolTokens = builtin,
        mcpToolTokens = mcp,
        skillToolTokens = skill,
        messageTokens = messageTokens,
    )
}

/**
 * 进程级上下文构成快照存储（进程存活期间有效；单用户本地 App，与 PromptMetrics /
 * SectionExpandStore 同方案）。Key = conversationId。
 *
 * GenerationHandler 在每次构造请求体时写入；顶栏圆圈 / 浮窗进度条 / 自动压缩读取。
 * 用 Compose snapshot state 承载，写入后正在组合的 UI（含 computeTokenStats）自动重组刷新。
 */
object ContextCompositionStore {
    private val snapshots = mutableStateMapOf<String, ContextComposition>()

    fun get(conversationId: String): ContextComposition? = snapshots[conversationId]

    fun update(conversationId: String, composition: ContextComposition) {
        snapshots[conversationId] = composition
    }

    fun remove(conversationId: String) {
        snapshots.remove(conversationId)
    }
}