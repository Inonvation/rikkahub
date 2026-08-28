package me.rerere.ai.ui

import androidx.compose.runtime.Immutable
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.util.json
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.uuid.Uuid

// 公共消息抽象, 具体的Provider实现会转换为API接口需要的DTO
@Serializable
data class UIMessage(
    val id: Uuid = Uuid.random(),
    val role: MessageRole,
    val parts: List<UIMessagePart>,
    val annotations: List<UIMessageAnnotation> = emptyList(),
    val createdAt: LocalDateTime = Clock.System.now()
        .toLocalDateTime(TimeZone.currentSystemDefault()),
    val finishedAt: LocalDateTime? = null,
    val modelId: Uuid? = null,
    // 生成该消息时的模型展示名快照（displayName 为空回退 API modelId）。
    // 模型从配置删除/更换后统计页仍可显示真实名称；旧数据缺省为 null 向后兼容。
    val modelName: String? = null,
    val usage: TokenUsage? = null,
    val translation: String? = null,
    // 请求期间生成的内部消息（系统提示/注入/提醒等）；仅内存中使用，不参与持久化
    @Transient
    val isSynthetic: Boolean = false,
    // 群组讨论：发言者标识。普通会话恒为 null；群聊会话里为成员 Assistant id + 名称快照。
    // 新增可空字段，JSON blob 老数据缺省即反序列化为 null，向后兼容。
    val speakerId: Uuid? = null,
    val speakerName: String? = null,
    // 会话增量同步的版本号（单调时钟）。由保存层在落库时维护：0 表示尚未参与同步（存量数据）。
    // 内存态每次重建（流式/编辑）不改变该值，内容未变时保留原版本，变化/新增才推进。
    val syncUpdatedAt: Long = 0L,
) {
    fun summaryAsText(maxLength: Int = Int.MAX_VALUE): String {
        val text = "[${role.name}]: " + parts.joinToString(separator = "\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> ""
            }
        }
        return if (text.length > maxLength) text.take(maxLength) + "..." else text
    }

    /**
     * 供上下文压缩摘要用的富序列化：文本 + 工具调用（入参/结果预览）+ 附件占位。
     *
     * 与 [summaryAsText]（只取 Text part，供标题/建议等轻量场景）不同：工具结果是上下文
     * 占用的大头（agent 会话尤甚），摘要器必须能看到"读过/改过什么、结果如何"，否则压缩后
     * 会丢失关键线索，模型只能重新调用工具摸索。Reasoning 刻意跳过（思考过程对续接任务是噪声）。
     */
    fun serializeForSummary(
        textLimit: Int = 4_000,
        toolInputLimit: Int = 500,
        toolOutputLimit: Int = 1_200,
    ): String = buildString {
        append("[${role.name}]")
        parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    val text = part.text.trim()
                    if (text.isNotEmpty()) {
                        append('\n')
                        append(text.ellipsizeForSummary(textLimit))
                    }
                }

                is UIMessagePart.Tool -> {
                    append("\n[tool ")
                    append(part.toolName)
                    val input = part.input.trim()
                    if (input.isNotEmpty()) {
                        append(" input: ")
                        append(input.ellipsizeForSummary(toolInputLimit))
                    }
                    val outputText = part.output.filterIsInstance<UIMessagePart.Text>()
                        .joinToString("\n") { it.text }
                        .trim()
                    if (outputText.isNotEmpty()) {
                        append(" -> output: ")
                        append(outputText.ellipsizeForSummary(toolOutputLimit))
                    }
                    append(']')
                }

                is UIMessagePart.ServerTool -> {
                    append("\n[server_tool ")
                    append(part.toolName)
                    append(" status=")
                    append(part.status.name.lowercase())
                    val outputText = part.output?.toString()?.trim().orEmpty()
                    if (outputText.isNotEmpty()) {
                        append(" -> output: ")
                        append(outputText.ellipsizeForSummary(toolOutputLimit))
                    }
                    append(']')
                }

                is UIMessagePart.Image -> append("\n[image]")
                is UIMessagePart.Video -> append("\n[video]")
                is UIMessagePart.Audio -> append("\n[audio]")
                is UIMessagePart.Document -> {
                    append("\n[document: ")
                    append(part.fileName)
                    append(']')
                }

                // 刻意跳过：思考过程对续接任务是噪声
                is UIMessagePart.Reasoning -> Unit
                // 废弃类型（Search/ToolCall/ToolResult）：仅老数据可能出现，不进摘要
                else -> Unit
            }
        }
    }

    /** 摘要序列化的截断：超限追加截断标记，让摘要模型知道内容不完整 */
    private fun String.ellipsizeForSummary(limit: Int): String =
        if (length <= limit) this else take(limit) + "...[truncated]"

    fun toText() = parts.joinToString(separator = "\n") { part ->
        when (part) {
            is UIMessagePart.Text -> part.text
            else -> ""
        }
    }

    fun getTools() = parts.filterIsInstance<UIMessagePart.Tool>()

    fun isValidToUpload() = parts.any { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.isNotBlank()
            is UIMessagePart.Image -> part.url.isNotBlank()
            is UIMessagePart.Video -> part.url.isNotBlank()
            is UIMessagePart.Audio -> part.url.isNotBlank()
            is UIMessagePart.Document -> part.url.isNotBlank()
            is UIMessagePart.Reasoning -> part.reasoning.isNotBlank()
            else -> true
        }
    }

    inline fun <reified P : UIMessagePart> hasPart(): Boolean {
        return parts.any {
            it is P
        }
    }

    fun hasBase64Part(): Boolean = parts.any {
        it is UIMessagePart.Image && it.url.startsWith("data:")
    }

    companion object {
        fun system(prompt: String) = UIMessage(
            role = MessageRole.SYSTEM,
            parts = listOf(UIMessagePart.Text(prompt))
        )

        fun user(prompt: String) = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(prompt))
        )

        fun assistant(prompt: String) = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(prompt))
        )
    }
}

/**
 * 移除字符串首尾的空白行（空行或仅含空白字符的行），保留中间内容。
 *
 * 用于清理 AI 输出末尾多余的换行导致的空白展示（例如内容为 `"第一行\n\n\n"` 时，
 * 第二、三行都是空白，渲染出来会多出空行）。
 */
fun String.trimBlankLines(): String {
    val lines = lineSequence().toList()
    var start = 0
    var end = lines.size
    while (start < end && lines[start].isBlank()) start++
    while (end > start && lines[end - 1].isBlank()) end--
    if (start >= end) return ""
    return lines.subList(start, end).joinToString("\n")
}

/**
 * 清理消息中的空白内容：
 * - 移除 Text / Reasoning part 首尾的空白行；
 * - 移除清理后变为全空白的 part。
 *
 * 未发生任何变化时返回原引用（保持身份，避免下游无谓重组）。
 */
fun UIMessage.cleanupBlankParts(): UIMessage {
    var changed = false
    val newParts = parts.mapNotNull { part ->
        when (part) {
            is UIMessagePart.Text -> {
                val cleaned = part.text.trimBlankLines()
                when {
                    cleaned.isBlank() -> {
                        changed = true
                        null
                    }
                    cleaned != part.text -> {
                        changed = true
                        part.copy(text = cleaned)
                    }
                    else -> part
                }
            }

            is UIMessagePart.Reasoning -> {
                val cleaned = part.reasoning.trimBlankLines()
                when {
                    cleaned.isBlank() -> {
                        changed = true
                        null
                    }
                    cleaned != part.reasoning -> {
                        changed = true
                        part.copy(reasoning = cleaned)
                    }
                    else -> part
                }
            }

            else -> part
        }
    }
    return if (changed) copy(parts = newParts) else this
}

/**
 * 对列表末尾的助手消息做空白行清理（流式 / 生成完成时调用）。
 * 只在最后一条是助手消息且确实发生变化时重建列表。
 */
fun List<UIMessage>.cleanupLastAssistantBlankLines(): List<UIMessage> {
    if (isEmpty()) return this
    val last = last()
    if (last.role != MessageRole.ASSISTANT) return this
    val cleaned = last.cleanupBlankParts()
    return if (cleaned === last) this else dropLast(1) + cleaned
}

/**
 * 判断这个消息是否有有任何用户**可输入内容**
 *
 * 例如: 文本，图片, 文档
 */
fun List<UIMessagePart>.isEmptyInputMessage(): Boolean {
    if (this.isEmpty()) return true
    return this.all { message ->
        when (message) {
            is UIMessagePart.Text -> message.text.isBlank()
            is UIMessagePart.Image -> message.url.isBlank()
            is UIMessagePart.Document -> message.url.isBlank()
            is UIMessagePart.Video -> message.url.isBlank()
            is UIMessagePart.Audio -> message.url.isBlank()
            else -> true
        }
    }
}

/**
 * 判断这个消息在UI上是否显示任何内容
 */
fun List<UIMessagePart>.isEmptyUIMessage(): Boolean {
    if (this.isEmpty()) return true
    return this.all { message ->
        when (message) {
            is UIMessagePart.Text -> message.text.isBlank()
            is UIMessagePart.Image -> message.url.isBlank()
            is UIMessagePart.Document -> message.url.isBlank()
            is UIMessagePart.Reasoning -> message.reasoning.isBlank()
            is UIMessagePart.Video -> message.url.isBlank()
            is UIMessagePart.Audio -> message.url.isBlank()
            is UIMessagePart.Tool -> false
            is UIMessagePart.ServerTool -> false
            else -> true
        }
    }
}

/**
 * 截断后保留的消息条数占上限的比例
 *
 * 越小则截断点前进的步幅越大, 连续命中缓存的轮数越多, 但一次丢弃的上下文也越多
 */
private const val CONTEXT_KEEP_RATIO = 0.5f

/**
 * 按阶梯式(滞回)策略限制上下文消息数量
 *
 * 与每轮平移一条的滑动窗口不同, 截断点只在消息数越过 [limit] 时才前进一大步,
 * 在此之后的连续多轮里保持不动, 使请求前缀保持稳定, 从而命中提示词缓存。
 * 截断点仅由消息条数推导, 不需要额外持久化状态, 且对追加消息天然稳定。
 *
 * 保留的条数始终落在 `[limit * CONTEXT_KEEP_RATIO, limit)` 区间内。
 *
 * @param limit 触发截断的消息条数上限, 小于等于 0 表示不限制
 */
fun List<UIMessage>.limitContext(limit: Int): List<UIMessage> {
    if (limit <= 0 || this.size <= limit) return this

    // 截断后回落到的目标条数, 以及两次截断之间截断点前进的步幅
    // limit 为 1 时无法构造滞回(步幅至少为 1), 此时退化为逐条平移的滑动窗口
    val target = (limit * CONTEXT_KEEP_RATIO).roundToInt().coerceIn(1, limit)
    val stride = (limit - target).coerceAtLeast(1)

    // 每越过一级台阶, 截断点前进 stride 条; 台阶之内截断点不动
    // 上界兜底保证至少保留一条消息, 正常路径(limit >= 2)不会触发
    val startIndex = (((this.size - limit) / stride + 1) * stride).coerceAtMost(this.size - 1)

    return this.subList(alignContextStart(startIndex), this.size)
}

/**
 * 将截断起点回退到安全边界, 避免把 tool call 与其结果拆散, 或让上下文从半截的工具调用开始
 *
 * 只会向前(下标减小)调整, 因此不会破坏 [limitContext] 保留条数的下界。
 * 调整只依赖 `[0, startIndex]` 区间内的消息, 这部分在追加新消息时不会变化, 结果因此保持稳定。
 */
private fun List<UIMessage>.alignContextStart(startIndex: Int): Int {
    var adjustedStartIndex = startIndex

    // 循环往前查找, 直到满足所有依赖条件
    var needsAdjustment = true
    val visitedIndices = mutableSetOf<Int>()

    while (needsAdjustment && adjustedStartIndex > 0) {
        needsAdjustment = false

        // 防止无限循环
        if (adjustedStartIndex in visitedIndices) break
        visitedIndices.add(adjustedStartIndex)

        val currentMessage = this[adjustedStartIndex]

        // 如果当前消息包含已执行的tool（有output），往前查找对应的tool call
        if (currentMessage.getTools().any { it.isExecuted }) {
            for (i in adjustedStartIndex - 1 downTo 0) {
                if (this[i].getTools().any { !it.isExecuted }) {
                    adjustedStartIndex = i
                    needsAdjustment = true
                    break
                }
            }
        }

        // 如果当前消息包含未执行的tool call，往前查找对应的用户消息
        if (currentMessage.getTools().any { !it.isExecuted }) {
            for (i in adjustedStartIndex - 1 downTo 0) {
                if (this[i].role == MessageRole.USER) {
                    adjustedStartIndex = i
                    needsAdjustment = true
                    break
                }
            }
        }
    }

    return adjustedStartIndex
}

/**
 * Sort message parts by type priority:
 * - Reasoning (-1): shown first
 * - Text, Tool, ServerTool, ToolCall, ToolResult, Search (0): middle
 * - Image, Video, Audio, Document (1): shown last
 *
 * WARNING: This function is intended for migration only.
 * Do not use for new messages as it may break the semantic order
 * when a message contains multiple Reasoning/Text parts.
 */
@Deprecated(
    message = "Only use for migration. May break semantic order for messages with multiple Reasoning/Text parts.",
    level = DeprecationLevel.WARNING
)
fun List<UIMessagePart>.toSortedMessageParts(): List<UIMessagePart> {
    // Skip sorting if multiple Reasoning or Text parts exist to preserve semantic order
    val reasoningCount = count { it is UIMessagePart.Reasoning }
    val textCount = count { it is UIMessagePart.Text }
    if (reasoningCount > 1 || textCount > 1) {
        return this
    }
    return sortedBy { part ->
        when (part) {
            is UIMessagePart.Reasoning -> -1
            is UIMessagePart.Text -> 0
            is UIMessagePart.Tool -> 0
            is UIMessagePart.ServerTool -> 0
            is UIMessagePart.ToolCall -> 0
            is UIMessagePart.ToolResult -> 0
            is UIMessagePart.Search -> 0
            is UIMessagePart.Image -> 1
            is UIMessagePart.Video -> 1
            is UIMessagePart.Audio -> 1
            is UIMessagePart.Document -> 1
        }
    }
}

fun UIMessage.finishReasoning(): UIMessage {
    // 无未完成 Reasoning 的消息返回 this（引用不变），避免生成结束时对历史消息
    // 全量 copy → 引用全变 → 整屏重组
    var changed = false
    val newParts = parts.map { part ->
        when (part) {
            is UIMessagePart.Reasoning -> {
                if (part.finishedAt == null) {
                    changed = true
                    part.copy(
                        finishedAt = Clock.System.now()
                    )
                } else {
                    part
                }
            }

            else -> part
        }
    }
    return if (changed) copy(parts = newParts) else this
}

fun UIMessage.finishPendingTools(
    transform: (UIMessagePart.Tool) -> UIMessagePart.Tool
): UIMessage {
    val updatedParts = parts.map { part ->
        if (part is UIMessagePart.Tool && !part.isExecuted) {
            transform(part)
        } else {
            part
        }
    }

    if (updatedParts == parts) {
        return this
    }

    return copy(
        parts = updatedParts,
        finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    ).finishReasoning()
}

/**
 * Migrate legacy ToolCall parts to new Tool type within a single message.
 * This converts ToolCall parts to Tool parts with empty output.
 */
@Suppress("DEPRECATION")
private fun UIMessage.migrateToolParts(): UIMessage {
    val toolCalls = parts.filterIsInstance<UIMessagePart.ToolCall>()
    if (toolCalls.isEmpty()) {
        // Even if no ToolCall migration needed, ensure parts are sorted
        val sortedParts = parts.toSortedMessageParts()
        return if (sortedParts != parts) copy(parts = sortedParts) else this
    }

    val migratedParts = parts.map { part ->
        if (part is UIMessagePart.ToolCall) {
            UIMessagePart.Tool(
                toolCallId = part.toolCallId,
                toolName = part.toolName,
                input = part.arguments,
                output = emptyList(),
                approvalState = part.approvalState,
                metadata = part.metadata
            )
        } else {
            part
        }
    }
    return copy(parts = migratedParts.toSortedMessageParts())
}

/**
 * Migrate TOOL role messages into previous ASSISTANT messages by
 * merging ToolResult parts into corresponding Tool parts.
 * Returns the migrated list with TOOL messages removed.
 */
@Suppress("DEPRECATION")
fun List<UIMessage>.migrateToolMessages(): List<UIMessage> {
    val result = mutableListOf<UIMessage>()
    var i = 0

    while (i < size) {
        val message = this[i]

        // If this is a TOOL role message, merge its results into previous ASSISTANT message
        if (message.role == MessageRole.TOOL) {
            val toolResults = message.parts.filterIsInstance<UIMessagePart.ToolResult>()
            if (result.isNotEmpty() && result.last().role == MessageRole.ASSISTANT) {
                // Find the last ASSISTANT message and update its Tool parts with results
                val lastAssistant = result.removeAt(result.lastIndex)
                val updatedParts = lastAssistant.parts.map { part ->
                    if (part is UIMessagePart.Tool && !part.isExecuted) {
                        val matchingResult = toolResults.find { result -> result.toolCallId == part.toolCallId }
                        if (matchingResult != null) {
                            part.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(matchingResult.content)
                                    )
                                )
                            )
                        } else {
                            part
                        }
                    } else if (part is UIMessagePart.ToolCall) {
                        // Also handle legacy ToolCall parts
                        val matchingResult = toolResults.find { result -> result.toolCallId == part.toolCallId }
                        if (matchingResult != null) {
                            UIMessagePart.Tool(
                                toolCallId = part.toolCallId,
                                toolName = part.toolName,
                                input = part.arguments,
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(matchingResult.content)
                                    )
                                ),
                                approvalState = part.approvalState,
                                metadata = part.metadata
                            )
                        } else {
                            UIMessagePart.Tool(
                                toolCallId = part.toolCallId,
                                toolName = part.toolName,
                                input = part.arguments,
                                output = emptyList(),
                                approvalState = part.approvalState,
                                metadata = part.metadata
                            )
                        }
                    } else {
                        part
                    }
                }
                result.add(lastAssistant.copy(parts = updatedParts.toSortedMessageParts()))
            }
            // Skip the TOOL message (don't add it to result)
            i++
            continue
        }

        // For other messages, migrate their tool parts first
        result.add(message.migrateToolParts())
        i++
    }

    return result
}

/**
 * Migrate legacy TOOL role messages at the MessageNode level.
 * This handles the case where TOOL messages are stored in separate MessageNodes
 * by merging ToolResult parts into the previous ASSISTANT node's Tool parts.
 *
 * @param MessageNode A container holding one or more UIMessages for branching.
 * @return Migrated list with TOOL nodes removed and their results merged into ASSISTANT nodes.
 */
@Suppress("DEPRECATION")
fun <T> List<T>.migrateToolNodes(
    getMessages: (T) -> List<UIMessage>,
    setMessages: (T, List<UIMessage>) -> T
): List<T> {
    val result = mutableListOf<T>()
    var i = 0

    while (i < size) {
        val node = this[i]
        val messages = getMessages(node)

        // Check if this node contains TOOL role messages
        val isToolNode = messages.any { it.role == MessageRole.TOOL }

        if (isToolNode && result.isNotEmpty()) {
            // Find the previous ASSISTANT node
            val lastIndex = result.lastIndex
            val lastNode = result[lastIndex]
            val lastMessages = getMessages(lastNode)
            val isAssistantNode = lastMessages.any { it.role == MessageRole.ASSISTANT }

            if (isAssistantNode) {
                // Collect all ToolResults from the TOOL node
                val toolResults = messages.flatMap { msg ->
                    msg.parts.filterIsInstance<UIMessagePart.ToolResult>()
                }

                // Update the ASSISTANT node's messages by merging ToolResults
                val updatedMessages = lastMessages.map { assistantMsg ->
                    if (assistantMsg.role != MessageRole.ASSISTANT) return@map assistantMsg

                    val updatedParts = assistantMsg.parts.map { part ->
                        when (part) {
                            is UIMessagePart.Tool -> {
                                if (!part.isExecuted) {
                                    val matchingResult = toolResults.find { it.toolCallId == part.toolCallId }
                                    if (matchingResult != null) {
                                        part.copy(
                                            output = listOf(
                                                UIMessagePart.Text(
                                                    json.encodeToString(matchingResult.content)
                                                )
                                            )
                                        )
                                    } else part
                                } else part
                            }

                            is UIMessagePart.ToolCall -> {
                                val matchingResult = toolResults.find { it.toolCallId == part.toolCallId }
                                if (matchingResult != null) {
                                    UIMessagePart.Tool(
                                        toolCallId = part.toolCallId,
                                        toolName = part.toolName,
                                        input = part.arguments,
                                        output = listOf(
                                            UIMessagePart.Text(
                                                json.encodeToString(matchingResult.content)
                                            )
                                        ),
                                        approvalState = part.approvalState,
                                        metadata = part.metadata
                                    )
                                } else {
                                    UIMessagePart.Tool(
                                        toolCallId = part.toolCallId,
                                        toolName = part.toolName,
                                        input = part.arguments,
                                        output = emptyList(),
                                        approvalState = part.approvalState,
                                        metadata = part.metadata
                                    )
                                }
                            }

                            else -> part
                        }
                    }
                    assistantMsg.copy(parts = updatedParts.toSortedMessageParts())
                }

                result[lastIndex] = setMessages(lastNode, updatedMessages)
                // Skip the TOOL node (don't add it to result)
                i++
                continue
            }
        }

        // For non-TOOL nodes, migrate their internal tool parts
        val migratedMessages = messages.migrateToolMessages()
        result.add(setMessages(node, migratedMessages))
        i++
    }

    return result
}
