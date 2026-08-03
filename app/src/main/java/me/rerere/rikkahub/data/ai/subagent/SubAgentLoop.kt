package me.rerere.rikkahub.data.ai.subagent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.merge
import me.rerere.ai.core.sum
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.handleMessageChunk

private const val TAG = "SubAgentLoop"

/** 单个工具输出截断阈值：防止长任务（web_researcher 24 步）撑爆模型上下文窗口 */
private const val MAX_TOOL_OUTPUT_CHARS = 3000

/** 单条消息内累积文本上限：超过丢弃较早文本，保留最新生成内容（MED-5） */
private const val MAX_MESSAGE_TEXT_CHARS = 8000

/** 子代理上下文保留的最大消息条数：超出裁剪最早的消息，防止无界增长 */
private const val MAX_CONTEXT_MESSAGES = 30

/** token 预算超限信号：由调用方在 onUsageUpdate 里检测并抛出，runWithTimeout 捕获置 TOKEN_LIMIT。
 *  走独立异常类型而非复用通用 Exception，避免被当作"模型调用失败"记录日志/触发重试。 */
class TokenBudgetExceeded : Exception("Sub-agent token budget exceeded")

/**
 * 子代理独立回合循环（精简版 GenerationHandler）。
 *
 * 不复用 GenerationHandler 的原因：它强绑定 assistant: Assistant + transformers + 记忆，
 * 为子代理造合成 Assistant 是"为复用而复用"。这里复刻 GenerationHandler 的核心骨架
 * （模型生成 → 提取未执行工具 → 执行 → 结果回填），裁掉 transformers / 记忆 / 审批拦截 /
 * 文件落盘。取消必须向上传播，与 GenerationHandler.kt:297 一致。
 *
 * 与母代理的关键差异：
 * - **无审批拦截**：子代理是母代理信任的委派执行器，内无用户审批路径。
 *   需要审批的工具直接回填错误让模型读到并调整，绝不 break 假成功。
 * - **上下文裁剪**：工具输出截断 + 消息条数上限，防止长任务超模型上下文窗口。
 *
 * @return 子代理完整消息序列（最后一条为最终产出）
 */
suspend fun subAgentRunLoop(
    json: Json,
    providerImpl: Provider<ProviderSetting>,
    providerSetting: ProviderSetting,
    messages: List<UIMessage>,
    tools: List<Tool>,
    params: TextGenerationParams,
    maxSteps: Int,
    onStep: (String) -> Unit,
    /** 实时流式回调：每收到一段模型输出 delta 就回调（供 UI 实时展示子代理输出） */
    onStreamUpdate: (String) -> Unit = {},
    /** 实时思考回调：每收到一段 Reasoning delta 就回调（供 UI 实时展示子代理思考内容）。
     *  与 [onStreamUpdate] 一样，回调的是"当前步累积全文"，由调用方自行做 delta/追加处理。 */
    onReasoningUpdate: (String) -> Unit = {},
    /** 实时消息序列回调：每收到一段模型输出 chunk（或工具回填）后，把当前完整消息列表上推。
     *  供 UI 实时重建结构化思维链时间线（Reasoning/Tool parts 交错）。 */
    onMessagesUpdate: (List<UIMessage>) -> Unit = {},
    /** 工具调用回调：执行每个工具前回调（工具名 + 入参摘要），供 UI 展示"调用了哪些工具" */
    onToolCall: (String, String) -> Unit = { _, _ -> },
    /** 用量回调：每次流式 chunk 携带 usage 时回调累计增量，供调用方跨步骤累加并持久化 */
    onUsageUpdate: (TokenUsage) -> Unit = {},
    /** 引导回调：每步生成前调用，返回注入用户引导后的消息列表。
     *  子代理运行中从详情页发送的引导消息由此注入（subAgentAllowGuidance 开启时）。 */
    onGuidance: suspend (List<UIMessage>) -> List<UIMessage> = { it },
): List<UIMessage> {
    var messages = messages
    var lastMessages: List<UIMessage> = messages

    for (stepIndex in 0 until maxSteps) {
        Log.i(TAG, "step #$stepIndex")

        // 0. 每步生成前注入用户引导消息（若子代理运行期间有收到引导）
        messages = onGuidance(messages)

        // 1. 生成（流式，实时输出到 onStreamUpdate；步骤日志覆盖进度感知）
        try {
            // 每步重置 Reasoning delta 提取基线：只回调本步新增的思考增量
            var lastReasoningLen = 0
            var stepUsage: TokenUsage? = null
            providerImpl.streamText(
                providerSetting = providerSetting,
                messages = messages,
                params = params,
            ).collect { chunk ->
                messages = messages.handleMessageChunk(chunk = chunk, model = params.model)
                lastMessages = messages
                // 收集本步 usage（chunk 级增量，merge 累积后回调调用方）
                chunk.usage?.let { stepUsage = stepUsage.merge(it) }
                // 实时上推结构化消息（供 UI 重建思维链时间线）
                onMessagesUpdate(messages)
                // 提取本轮新增文本 delta，实时回调
                val lastText = messages.lastOrNull()
                    ?.parts
                    ?.filterIsInstance<UIMessagePart.Text>()
                    ?.joinToString("") { it.text }
                    .orEmpty()
                onStreamUpdate(lastText)
                // 提取本轮新增 Reasoning delta，实时回调（handleMessageChunk 对 Reasoning 是 append-only）
                val lastReasoning = messages.lastOrNull()
                    ?.parts
                    ?.filterIsInstance<UIMessagePart.Reasoning>()
                    ?.joinToString("") { it.reasoning }
                    .orEmpty()
                if (lastReasoning.length > lastReasoningLen) {
                    onReasoningUpdate(lastReasoning.drop(lastReasoningLen))
                }
                lastReasoningLen = lastReasoning.length
            }
            stepUsage?.let(onUsageUpdate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TokenBudgetExceeded) {
            // token 预算耗尽不是模型失败：不记步骤日志、不脱敏包装，原样上抛由 runWithTimeout 处理
            throw e
        } catch (e: Exception) {
            onStep("模型调用失败：${e.message ?: e.javaClass.simpleName}")
            throw e
        }
        onStep("完成第 ${stepIndex + 1} 步思考")

        // 2. 提取未执行工具
        val pendingTools = messages.last().getTools().filter { !it.isExecuted }
        if (pendingTools.isEmpty()) {
            break
        }

        // 3. 执行工具并回填 output（无审批拦截）
        val executedTools = arrayListOf<UIMessagePart.Tool>()
        pendingTools.forEach { tool ->
            val toolDef = tools.find { it.name == tool.toolName }

            // 需要审批的工具无法在子代理内审批：回填错误，让模型读到并调整，不假成功
            val needsApproval = toolDef?.needsApproval(tool.inputAsJson()) == true &&
                tool.approvalState is ToolApprovalState.Auto
            if (needsApproval) {
                executedTools += tool.copy(
                    output = listOf(
                        UIMessagePart.Text(
                            json.encodeToString(buildJsonObject {
                                put("error", JsonPrimitive(
                                    "Tool ${tool.toolName} requires user approval and cannot be executed inside a sub-agent. " +
                                        "Skip it or ask the parent agent to run it."
                                ))
                            })
                        )
                    )
                )
                onStep("工具 ${tool.toolName} 需要用户审批，子代理内跳过")
                return@forEach
            }

            if (tool.approvalState is ToolApprovalState.Denied) {
                val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                executedTools += tool.copy(
                    output = listOf(
                        UIMessagePart.Text(
                            json.encodeToString(buildJsonObject {
                                put("error", JsonPrimitive(
                                    "Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}"
                                ))
                            })
                        )
                    )
                )
                return@forEach
            }
            if (tool.approvalState is ToolApprovalState.Answered) {
                executedTools += tool.copy(
                    output = listOf(UIMessagePart.Text((tool.approvalState as ToolApprovalState.Answered).answer))
                )
                return@forEach
            }
            if (tool.approvalState is ToolApprovalState.Pending) {
                // 防御性兜底：Pending 审批工具在子代理里没有审批路径。正常路径不会走到这
                // （生成时审批工具已置 Pending 并 break 等待），走到说明异常——回填错误让
                // 模型读到并调整，避免 executedTools 为空 → break → 被误判为"完成"。
                // 与 needsApproval 分支的回填语义一致（错误 JSON 让模型调整而非假成功）。
                executedTools += tool.copy(
                    output = listOf(
                        UIMessagePart.Text(
                            json.encodeToString(buildJsonObject {
                                put(
                                    "error",
                                    JsonPrimitive(
                                        "Tool ${tool.toolName} is awaiting user approval in the parent conversation " +
                                            "and cannot complete inside a sub-agent. Skip it and continue."
                                    )
                                )
                            })
                        )
                    )
                )
                return@forEach
            }

            runCatching {
                val def = tools.find { it.name == tool.toolName }
                    ?: error("Tool ${tool.toolName} not found")
                val args: JsonElement = runCatching {
                    json.parseToJsonElement(tool.input.ifBlank { "{}" })
                }.getOrElse {
                    error("Invalid tool arguments JSON for ${tool.toolName}: ${it.message}")
                }
                Log.i(TAG, "executing tool ${def.name}")
                // 记录工具调用（供 UI 展示）
                onToolCall(def.name, args.toString().take(200))
                val result = def.execute(args)
                // H3: 截断工具输出，防止上下文爆炸
                executedTools += tool.copy(output = truncateParts(result, json))
                onStep("工具 ${def.name} 执行完成")
            }.onFailure { e ->
                if (e is CancellationException) throw e
                e.printStackTrace()
                executedTools += tool.copy(
                    output = listOf(
                        UIMessagePart.Text(
                            json.encodeToString(buildJsonObject {
                                put("error", JsonPrimitive("[${e.javaClass.name}] ${e.message}"))
                            })
                        )
                    )
                )
            }
        }

        if (executedTools.isEmpty()) break

        // 4. 更新最后一条消息的工具输出 + 裁剪上下文
        val lastMessage = messages.last()
        val updatedParts = lastMessage.parts.map { part ->
            if (part is UIMessagePart.Tool) {
                executedTools.find { it.toolCallId == part.toolCallId } ?: part
            } else part
        }
        messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
        // 保留最近的 N 条消息，防止无界增长。system 必须保留：丢弃会失去身份定义/工具说明
        if (messages.size > MAX_CONTEXT_MESSAGES) {
            val system = messages.firstOrNull { it.role == me.rerere.ai.core.MessageRole.SYSTEM }
            messages = if (system != null) {
                listOf(system) + messages.takeLast(MAX_CONTEXT_MESSAGES - 1)
            } else {
                messages.takeLast(MAX_CONTEXT_MESSAGES)
            }
        }
        // MED-5: 单条消息内 Text 也做体量上限——handleMessageChunk 把各步 delta 合并进同一条
        // assistant 消息（消息条数基本恒定），真正的膨胀发生在条内的 Text/Tool 累积。
        // 超过上限时丢弃最早的非末段文本，保留最新内容。
        messages = messages.map { msg ->
            val textParts = msg.parts.filterIsInstance<UIMessagePart.Text>()
            val nonTextParts = msg.parts.filterNot { it is UIMessagePart.Text }
            if (textParts.sumOf { it.text.length } <= MAX_MESSAGE_TEXT_CHARS) {
                msg
            } else {
                // 保留最后一段文本（当前正在生成的内容），丢弃较早的中间文本
                val lastText = textParts.last()
                msg.copy(
                    parts = nonTextParts + UIMessagePart.Text(
                        lastText.text.take(MAX_MESSAGE_TEXT_CHARS) + "\n...[历史输出已裁剪]"
                    )
                )
            }
        }
        lastMessages = messages
        // 步骤 4 之后也上推一次：maxSteps 耗尽停在工具调用时，工具 output 已回填，需实时更新
        onMessagesUpdate(messages)
    }

    return lastMessages.also { onMessagesUpdate(it) }
}

/**
 * 从子代理消息序列提取最终产出。
 *
 * 摘要 = 最后一条 assistant 消息的**最后一段**文本（MED-6：避免取到开头"我来搜索"类开场白）；
 * 若为空（maxSteps 用尽时最后停在工具调用），回退合并已执行工具的文本输出（截断），
 * 保证母代理能读到有价值的检索/抓取结果。
 */
fun subAgentResultSummary(messages: List<UIMessage>): Pair<String, List<UIMessagePart>> {
    val lastAssistant = messages.lastOrNull { it.role == me.rerere.ai.core.MessageRole.ASSISTANT }
    // 取最后一段 Text（而非全部拼接），规避多步合并后 toText() 混入开场白
    val text = lastAssistant?.parts
        ?.filterIsInstance<UIMessagePart.Text>()
        ?.lastOrNull()
        ?.text
        ?.trim()
        .orEmpty()

    // 收集已执行工具的输出文本
    val toolTexts = messages.flatMap { it.parts }
        .filterIsInstance<UIMessagePart.Tool>()
        .filter { it.output.isNotEmpty() }
        .mapNotNull { tool ->
            tool.output.filterIsInstance<UIMessagePart.Text>()
                .firstOrNull()?.text?.take(MAX_TOOL_OUTPUT_CHARS)
        }
        .filter { it.isNotBlank() }

    val summary = when {
        text.isNotBlank() -> text
        toolTexts.isNotEmpty() -> toolTexts.joinToString("\n\n").take(MAX_TOOL_OUTPUT_CHARS)
        else -> ""
    }

    // result：截断后的工具输出 parts（供 UI 详情展示，不回流母代理）
    val toolParts = messages.flatMap { it.parts }
        .filterIsInstance<UIMessagePart.Tool>()
        .flatMap { it.output }
        .take(20)
        .map { part ->
            if (part is UIMessagePart.Text && part.text.length > MAX_TOOL_OUTPUT_CHARS) {
                UIMessagePart.Text(part.text.take(MAX_TOOL_OUTPUT_CHARS))
            } else part
        }

    return summary to toolParts
}

/**
 * 截断工具输出：每个 Text part 限长，其他 part 类型保留。
 *
 * 对 JSON 文本做**结构安全**截断：解析后按有界规则重编码（长字符串截断、数组保留前 N 个），
 * 保证产物永远是合法 JSON —— 否则 `ChatMessageToolStep` 的 `parseToJsonElement` 会失败，
 * search_web 等工具的详情/展开就失效。非 JSON 文本回退简单截断。
 */
private fun truncateParts(parts: List<UIMessagePart>, json: Json): List<UIMessagePart> {
    return parts.map { part ->
        if (part is UIMessagePart.Text && part.text.length > MAX_TOOL_OUTPUT_CHARS) {
            UIMessagePart.Text(truncateSafely(part.text, json))
        } else part
    }
}

/** 有界重编码 JSON：字符串截断、数组保留前 6 个元素，保证产物合法 */
private fun truncateSafely(text: String, json: Json): String {
    val elem = runCatching { json.parseToJsonElement(text) }.getOrNull()
    if (elem == null) return text.take(MAX_TOOL_OUTPUT_CHARS) + "\n...[truncated]"
    return json.encodeToString(boundJson(elem))
}

private fun boundJson(elem: JsonElement): JsonElement = when (elem) {
    is JsonPrimitive -> if (elem.toString().startsWith('"')) {
        JsonPrimitive(if (elem.content.length > 500) elem.content.take(500) + "…[截断]" else elem.content)
    } else {
        elem
    }

    is JsonArray -> buildJsonArray {
        elem.take(6).forEach { add(boundJson(it)) }
    }

    is JsonObject -> buildJsonObject {
        elem.forEach { (k, v) -> put(k, boundJson(v)) }
    }
}

private fun UIMessage.toText(): String = parts.joinToString(separator = "\n") { part ->
    when (part) {
        is UIMessagePart.Text -> part.text
        else -> ""
    }
}
