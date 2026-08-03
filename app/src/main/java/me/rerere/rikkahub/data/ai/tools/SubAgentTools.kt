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
 */
fun createSubAgentTools(
    subAgentRunner: SubAgentRunner,
    parentConversationId: Uuid,
): List<Tool> {
    // 子代理清单放到 agentId 参数描述里（随工具 schema 提供），不注入 system 前缀——
    // 模型通过参数描述感知各子代理职责，省掉每请求 ~200 token 的常驻提示词
    val agentIdDescription = buildString {
        append("Sub-agent id. Available sub-agents:")
        SubAgentCatalog.all.forEach { def ->
            append("\n- ${def.id} (${def.name}): ${def.description}")
        }
    }

    // 母代理行为引导（异步唤醒模式）：派发后自由继续，可结束回合，完成时自动唤醒注入结果。
    val spawnBehavior = """
        ## Usage
        - After dispatching, **do not wait idle** — continue doing work you can do:
          analyze the user's deeper needs, clarify direction, plan steps, run base work
          that doesn't need the sub-agent, or handle parallel tasks with info you already have.
        - The sub-agent runs in the background. When it completes, you will be **woken up
          automatically** with its result injected into the context. There is no await tool —
          you never block waiting for it.
        - You may end this round whenever you have enough to respond, even if sub-agents are
          still running — they continue in the background and will wake you when done.
        - Once you have results, **cross-verify, synthesize and summarize** them into the best
          possible reply to the user. Do NOT just relay the sub-agent's output verbatim.
        - Never assume a sub-agent finished: `dispatched` is only a dispatch marker, not a result.
        - A timed-out or failed sub-agent may be **auto-retried once by the system** (same task,
          context preserved). If the final status is still `timeout`/`failed`, the sub-agent may
          still have produced partial work: use `summary`, `partialSteps` and `partialOutput` to
          answer as best you can. Do not pretend it succeeded — but do not simply tell the user it
          failed; salvage whatever partial result is available.
        - If `spawn_subagent` returns `status=limit_reached`, the concurrency cap is full. Do NOT
          retry spawn immediately; do other independent work first and try again later, or handle
          the task yourself.
        - Use sub-agents only when they add clear value (bulk research / long-document analysis /
          independent execution). Do simple things yourself — don't dispatch for the sake of it.

        ## Task template
        Compose the `task` argument in this markdown structure (the sub-agent cannot see this
        conversation — be self-contained, never write "as we discussed above"):
        # Task
        ## Background
        Relevant context and known information.
        ## Objective
        What to accomplish, stated clearly.
        ## Constraints
        Boundaries, limits, things to avoid.
        ## Deliverable
        The expected output form (e.g. a ranked list, a markdown report, a summary).
    """.trimIndent()

    return listOf(
        // ---- spawn_subagent：派发，不阻塞 ----
        Tool(
            name = "spawn_subagent",
            description = """
                Dispatch a task to a specialized sub-agent that runs in its own isolated context
                with its own model and tools. Returns a dispatch marker (`status=dispatched`) plus
                a `taskId` — **not** the execution result.

                $spawnBehavior

                Parameters:
                - agentId: sub-agent id (available sub-agents are listed in that parameter's description)
                - task: task description for the sub-agent, composed per the ## Task template above
                - modelId(optional): override the sub-agent's default model
            """.trimIndent(),
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
                                "Task description for the sub-agent. Compose it with this markdown structure (self-contained; the sub-agent cannot see this conversation):\n" +
                                    "# Task\n" +
                                    "## Background\n" +
                                    "Relevant context and known information.\n" +
                                    "## Objective\n" +
                                    "What to accomplish, stated clearly.\n" +
                                    "## Constraints\n" +
                                    "Boundaries, limits, things to avoid.\n" +
                                    "## Deliverable\n" +
                                    "The expected output form."
                            )
                        })
                        put("modelId", buildJsonObject {
                            put("type", "string")
                            put("description", "可选：覆盖子代理默认模型（模型 id）")
                        })
                    },
                    required = listOf("agentId", "task"),
                )
            },
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
