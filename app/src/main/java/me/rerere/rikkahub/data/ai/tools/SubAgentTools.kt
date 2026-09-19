package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.SubAgentCatalog
import me.rerere.rikkahub.data.ai.subagent.SubAgentRequest
import me.rerere.rikkahub.data.ai.subagent.SubAgentRunner
import me.rerere.rikkahub.data.ai.subagent.SubAgentStatus
import me.rerere.rikkahub.data.ai.subagent.SubAgentTask
import kotlin.uuid.Uuid

/**
 * 母代理侧的子代理工具：spawn_subagent 派发（异步唤醒模式）。
 *
 * 执行模式（异步，对齐 Claude Code 背景子代理）：
 * - spawn 后**不中断**母代理生成循环，母代理继续自由输出/思考/做初步决策，
 *   甚至可以直接结束回合。
 * - 子代理在 AppScope detached 后台运行，独立于母代理 job。
 * - 子代理完成时通过 SubAgentRunner.taskCompletedFlow 广播事件，ChatService
 *   resumeAfterSubAgent 自动唤醒母代理续答并注入结果——母代理**无需任何 await 工具**。
 *
 * 占位状态用 "dispatched"（非 queued/succeeded），避免模型误判"子代理瞬间完成"。
 *
 * 声明信道划分（对齐 wire 截断契约，改动前先读 WireTool.kt）：
 * - description 只写"是什么 + 返回什么"（≤300 字符），截断后语义仍完整；
 * - 子代理清单/何时委派/异步用法/Task 模板走 [SUBAGENT_SPAWN_SYSTEM_PROMPT]（system 信道，
 *   不裁剪、随工具注册去重注入）。旧实现把清单放 agentId 参数描述、用法放 description 尾部，
 *   两者都被 wire 截断静默砍掉——模型实际看不到。
 */
fun createSubAgentTools(
    subAgentRunner: SubAgentRunner,
    parentConversationId: Uuid,
): List<Tool> {
    // 参数描述必须 ≤ WIRE_PARAM_DESCRIPTION_LIMIT（160），完整清单走 systemPrompt
    val agentIdDescription = "Sub-agent id (see the Sub-Agents section in system instructions for each sub-agent's role)"

    return listOf(
        // ---- spawn_subagent：派发，不阻塞 ----
        Tool(
            name = "spawn_subagent",
            description = """
                Dispatch a task to a specialized sub-agent that runs in its own isolated context
                with its own model and tools. Returns a dispatch marker (`status=dispatched`) plus
                a `taskId` — **not** the execution result. Follow the Sub-Agents instructions.
            """.trimIndent(),
            systemPrompt = { _, _ -> SUBAGENT_SPAWN_SYSTEM_PROMPT },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("agentId", buildJsonObject {
                            put("type", "string")
                            // M5: 枚举与 SubAgentCatalog 动态对齐，避免广告不存在的子代理
                            put("enum", buildJsonArray {
                                SubAgentCatalog.all.forEach { add(JsonPrimitive(it.id)) }
                            })
                            put("description", agentIdDescription)
                        })
                        put("task", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Self-contained task description (the sub-agent cannot see this conversation); " +
                                    "follow the Task template in the Sub-Agents instructions."
                            )
                        })
                        put("modelId", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional: override the sub-agent's default model (model id)")
                        })
                    },
                    required = listOf("agentId", "task"),
                )
            },
            // 需要运行时 toolCallId：GenerationHandler 注入 __toolCallId 到 args，
            // execute 里把它作为子代理 taskId（与母代理 Tool.toolCallId 对齐）
            injectToolCallId = true,
            execute = { args ->
                val request = SubAgentRequest.fromJsonElement(args)
                    ?: return@Tool listOf(UIMessagePart.Text("{\"error\":\"Invalid subagent request\"}"))

                // 并发上限快速失败：并发已满时返回 limit_reached 错误 JSON，让母代理自行决定
                // 稍后重试或自己处理（而非无限排队堆积）。强制指令（/search 等）不走此工具，
                // 由 ChatService 直接派发，不受此限制。
                if (!subAgentRunner.isConcurrencyAvailable()) {
                    return@Tool listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("status", "limit_reached")
                                put("agentId", request.agentId)
                                put("maxConcurrent", subAgentRunner.concurrencyLimit())
                                put("running", subAgentRunner.runningCount.value)
                                put(
                                    "message",
                                    "并发子代理数已达上限，你可以先处理其他可独立完成的任务，稍后重试。"
                                )
                            }.toString()
                        )
                    )
                }

                // 从 args 提取隐藏字段 __toolCallId，作为子代理 taskId（与母代理 Tool.toolCallId 对齐，
                // 让 UI observeTask(toolCallId) 能查到实时任务）——顶部横幅/详情页的关键
                val toolCallId = (args as? JsonObject)
                    ?.get("__toolCallId")?.jsonPrimitive?.contentOrNull
                val taskId = subAgentRunner.runAsync(
                    request,
                    parentConversationId,
                    taskId = toolCallId ?: kotlin.uuid.Uuid.random().toString(),
                )
                // 派发占位：status=dispatched（非 queued/succeeded），明确"未完成"。异步唤醒模式——
                // 母代理继续做自己的事或结束回合，子代理完成时自动唤醒并注入结果。
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("status", "dispatched")
                            put("agentId", request.agentId)
                            put("taskId", taskId)
                            put(
                                "note",
                                "子代理已后台派发执行中。你可以继续做自己的工作，或直接结束本轮——" +
                                    "子代理完成时会自动唤醒并注入结果。"
                            )
                        }.toString()
                    )
                )
            },
        ),
    )
}

/**
 * 子代理使用规范（system 信道，随 spawn_subagent 注册去重注入）。
 *
 * 为什么整体放 system 而不是 description：wire 层把工具描述截到 300 字符
 * （见 WireTool.kt），以下三段的完整语义放不进 description——
 * 旧布局下它们全部在截断点之后，模型实际从未看到（清单在参数描述 80 字符外同理）。
 * 本段是子代理用法与清单的**单一来源**：AgentBehaviorPrompt 只保留一行指针，不再重复叙述。
 * 文本变化需同步 [me.rerere.rikkahub.data.ai.PROMPT_REVISION]。
 */
internal val SUBAGENT_SPAWN_SYSTEM_PROMPT: String = buildString {
    appendLine("**Sub-Agents**")
    appendLine("Sub-agents run in isolated contexts with their own models and tools. Available sub-agents:")
    SubAgentCatalog.all.forEach { def ->
        appendLine("- `${def.id}` (${def.name}): ${def.description}")
    }
    appendLine()
    appendLine("When to delegate:")
    appendLine("- The task splits into several **independent** workstreams (bulk research, long-document analysis, parallel verification, comparing options) whose combined output is more than you should load here.")
    appendLine("- Otherwise do it yourself — do not delegate single-file reads or simple lookups.")
    appendLine("How to run them:")
    appendLine("- Call `spawn_subagent` once per sub-agent **in the same reply** — each call dispatches one worker, so multiple calls in one reply run concurrently. Record every returned `taskId`.")
    appendLine("- After dispatching, **do not wait idle** — continue your own work: plan, analyze, run base steps that need no sub-agent result.")
    appendLine("Getting results:")
    appendLine("- Sub-agents wake you **automatically** when they complete — their result is injected into your context, so you never block waiting for them. There is no await tool.")
    appendLine("- You may end this round even while sub-agents run; they finish in the background and wake you.")
    appendLine("- `dispatched` is only a dispatch marker, never a result. A timed-out or failed task may be auto-retried once by the system (same task, context preserved); if the final status is still timeout/failed, salvage `summary`/`partialSteps`/`partialOutput` and answer from what you have — do not respawn yourself.")
    appendLine("- Cross-verify, synthesize and summarize their results into the best answer. Do not relay sub-agent output verbatim — you know the user's needs best.")
    appendLine("- When a sub-agent result corresponds to an item you track in the todo list, update that item (in_progress / completed) in your synthesis round.")
    appendLine("- If `spawn_subagent` returns `status=limit_reached`, the concurrency cap is full. Do not retry immediately; do other independent work first, or handle the task yourself.")
    appendLine()
    appendLine("Task template — compose the `task` argument in this markdown structure (the sub-agent cannot see this conversation; be self-contained, never write \"as we discussed above\"):")
    appendLine("# Task")
    appendLine("## Background")
    appendLine("Relevant context and known information.")
    appendLine("## Objective")
    appendLine("What to accomplish, stated clearly.")
    appendLine("## Constraints")
    appendLine("Boundaries, limits, things to avoid.")
    append("## Deliverable\nThe expected output form (e.g. a ranked list, a markdown report, a summary).")
}

/** 子代理终态结果 payload（spawn 占位回填、backfill 复用）。
 *  timeout/failed/token_limit 时附上 partialSteps/partialOutput，母代理可据此尽力作答。
 *  retryCount>0 时附带，母代理据此感知"自动重试过"。 */
fun subAgentResultPayload(task: SubAgentTask): JsonObject = buildJsonObject {
    put("status", task.status.name.lowercase())
    put("agentId", task.agentId)
    put("taskId", task.taskId)
    put("summary", task.resultSummary ?: "")
    if (task.retryCount > 0) put("retryCount", JsonPrimitive(task.retryCount))
    task.error?.let { put("error", JsonPrimitive(it)) }
    if (task.status == SubAgentStatus.TIMEOUT || task.status == SubAgentStatus.FAILED ||
        task.status == SubAgentStatus.TOKEN_LIMIT
    ) {
        task.steps.takeLast(10).joinToString("\n") { it.message }
            .takeIf { it.isNotBlank() }
            ?.let { put("partialSteps", JsonPrimitive(it)) }
        task.streamText.takeIf { it.isNotBlank() }
            ?.let { put("partialOutput", JsonPrimitive(it.take(2000))) }
    }
}

/** 判断 spawn 占位是否仍待回填（dispatched，兼容旧历史 queued）。 */
fun isSubAgentPlaceholder(output: List<UIMessagePart>): Boolean =
    output.filterIsInstance<UIMessagePart.Text>()
        .firstOrNull()?.text
        ?.let { it.contains("\"status\":\"dispatched\"") || it.contains("\"status\":\"queued\"") }
        ?: false
