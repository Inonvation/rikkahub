package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.subagent.SubAgentCatalog
import me.rerere.rikkahub.data.ai.subagent.SubAgentRequest
import me.rerere.rikkahub.data.ai.subagent.SubAgentRunner
import me.rerere.rikkahub.data.ai.subagent.SubAgentTask
import kotlin.uuid.Uuid

/**
 * 母代理侧的子代理工具：spawn_subagent 派发 + await_subagent 取结果。
 *
 * 执行模式（await 驱动，修复"母代理偷懒"）：
 * - spawn 后**不中断**母代理生成循环（无 awaitAsyncCompletion break），母代理继续干自己的活
 *   （思考深层需求 / 规划 / 执行其它工具）；需要子代理结果时调 await_subagent 阻塞拿结果。
 * - 子代理在 AppScope detached 后台运行，独立于母代理 job。
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

    // 母代理行为引导：派发后不偷懒，需要结果时 await，拿到后交叉验证汇总。
    // 用 markdown 分节（业界标准：## Role / ## Usage / ## Task template），
    // 英文编写（主流模型对英文指令理解更充分），task 模板给母代理明确的填写结构。
    val spawnBehavior = """
        ## Usage
        - After dispatching, **do not wait idle** — continue doing work you can do:
          analyze the user's deeper needs, clarify direction, plan steps, run base work
          that doesn't need the sub-agent, or handle parallel tasks with info you already have.
        - Remember the returned `taskId`. When the final answer needs the sub-agent's result,
          call `await_subagent(taskId=...)` to fetch its completion status and summary.
          You may await multiple sub-agents in parallel (one call per taskId).
        - Once you have results, **cross-verify, synthesize and summarize** them into the best
          possible reply to the user. Do NOT just relay the sub-agent's output verbatim.
          You know the user's needs best; the sub-agent is an assistant to you.
        - Never assume a sub-agent finished: `dispatched` is only a dispatch marker, not a result.
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
                // 从 args 提取隐藏字段 __toolCallId，作为子代理 taskId（与母代理 Tool.toolCallId 对齐，
                // 让 UI observeTask(toolCallId) 能查到实时任务）——顶部横幅/详情页的关键
                val toolCallId = (args as? JsonObject)
                    ?.get("__toolCallId")?.jsonPrimitive?.contentOrNull
                val taskId = subAgentRunner.runAsync(
                    request,
                    parentConversationId,
                    taskId = toolCallId ?: kotlin.uuid.Uuid.random().toString(),
                )
                // 派发占位：status=dispatched（非 queued/succeeded），明确"未完成，需 await"
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("status", "dispatched")
                            put("agentId", request.agentId)
                            put("taskId", taskId)
                            put(
                                "note",
                                "子代理已后台派发执行中。请继续做你能做的工作；需要结果时调用 await_subagent(taskId=$taskId) 获取。"
                            )
                        }.toString()
                    )
                )
            },
        ),

        // ---- await_subagent：阻塞等待结果 ----
        Tool(
            name = "await_subagent",
            description = """
                Block until a previously spawned sub-agent (the `taskId` returned by spawn_subagent)
                reaches a terminal state, then return its result summary. Execution pauses while waiting.

                ## Usage
                - Pass the `taskId` returned by spawn_subagent.
                - Await multiple sub-agents in parallel: call await_subagent once per taskId in the same reply.
                - After getting results, cross-verify, synthesize and summarize them into the final
                  reply. Do not relay the raw summary verbatim.
                - If `status=not_found`, the task is no longer valid (e.g. process restarted);
                  answer based on whatever info you already have.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("taskId", buildJsonObject {
                            put("type", "string")
                            put("description", "spawn_subagent 返回的 taskId（= 子代理任务 id）")
                        })
                    },
                    required = listOf("taskId"),
                )
            },
            execute = { args ->
                val taskId = (args as? JsonObject)
                    ?.get("taskId")?.jsonPrimitive?.contentOrNull
                    ?: return@Tool listOf(UIMessagePart.Text("{\"error\":\"Missing taskId\"}"))
                val task = subAgentRunner.awaitTask(taskId)
                val payload = if (task == null) {
                    // 任务不存在（进程重启后跟随消息重生成）→ not_found
                    buildJsonObject {
                        put("status", "not_found")
                        put("taskId", taskId)
                        put("error", "子代理任务不存在（可能已失效）。请基于已有信息尽力作答，或重新派发。")
                    }
                } else {
                    subAgentResultPayload(task)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            },
        ),
    )
}

/** 子代理终态结果 payload（spawn 占位回填、await、backfill 三处复用）。 */
fun subAgentResultPayload(task: SubAgentTask): JsonObject = buildJsonObject {
    put("status", task.status.name.lowercase())
    put("agentId", task.agentId)
    put("taskId", task.taskId)
    put("summary", task.resultSummary ?: "")
    task.error?.let { put("error", JsonPrimitive(it)) }
}

/** 判断 spawn 占位是否仍待回填（dispatched，兼容旧历史 queued）。 */
fun isSubAgentPlaceholder(output: List<UIMessagePart>): Boolean =
    output.filterIsInstance<UIMessagePart.Text>()
        .firstOrNull()?.text
        ?.let { it.contains("\"status\":\"dispatched\"") || it.contains("\"status\":\"queued\"") }
        ?: false
