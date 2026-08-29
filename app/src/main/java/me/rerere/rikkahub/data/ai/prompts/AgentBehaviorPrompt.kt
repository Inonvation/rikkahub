package me.rerere.rikkahub.data.ai.prompts

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.classifyToolFamily
import me.rerere.rikkahub.data.model.AgentBehaviorProfile

/**
 * 主代理行为层提示词：系统提示词末尾追加的决策/工具/子代理/提问准则。
 *
 * 借鉴 Claude Code 的做法——独立短块动态拼装，不写成一长段层级：
 * 每条规则一个短句，写清"何时用 / 何时不用"优于"必须做 X"。
 * 英文编写（主流模型对英文指令理解更充分），与其它内置 prompt 一致。
 */
internal fun buildAgentBehaviorPrompt(
    tools: List<Tool>,
    profile: AgentBehaviorProfile = AgentBehaviorProfile.STANDARD,
): String {
    val toolGroups = groupToolsForPrompt(tools)
    return buildString {
        appendLine("## Agent Behavior")
        appendLine()
        if (profile != AgentBehaviorProfile.LEGACY) {
            appendLine(modeGuidanceSection(profile, tools))
            appendLine()
        }
        appendLine(PLAN_AND_ACT_SECTION)
        appendLine()
        if (toolGroups.isNotBlank() && profile != AgentBehaviorProfile.MINIMAL) {
            appendLine()
            append(toolGroups)
        }
        appendLine()
        appendLine(ASK_USER_SECTION)
        if (tools.any { it.name == "spawn_subagent" }) {
            appendLine()
            append(SUB_AGENT_DELEGATION_SECTION)
        }
    }
}

private fun modeGuidanceSection(profile: AgentBehaviorProfile, tools: List<Tool>): String = when (profile) {
    AgentBehaviorProfile.STANDARD -> MODE_STANDARD_SECTION
    AgentBehaviorProfile.WORKSPACE -> MODE_WORKSPACE_SECTION
    AgentBehaviorProfile.MANAGEMENT -> managementSection(tools)
    AgentBehaviorProfile.MINIMAL -> MODE_MINIMAL_SECTION
    AgentBehaviorProfile.LEGACY -> ""
}

private val MODE_STANDARD_SECTION = """
    ## Mode: Balanced
    - You are in balanced mode: the full standard toolset is available, but tool access is optional.
    - Finish each request in as few turns as possible; do not pause between independent steps just to narrate progress.
    - Match the user's working style: answer directly when enough context exists, use tools only when they add concrete value.
""".trimIndent()

private val MODE_WORKSPACE_SECTION = """
    ## Mode: Workspace
    - You are in workspace mode: treat the bound workspace and trusted folders as your working context.
    - Plan multi-step file work before starting, then execute continuously in the same turn: read, edit, verify, and move on.
    - Batch independent operations in one reply; do not stop to ask after every file change.
    - After edits, verify the result and report concrete outcomes, not a play-by-play of each call.
    - Prefer workspace tools over pasting file contents into chat. If no workspace is configured, fall back to balanced behavior.
""".trimIndent()

/**
 * 管理段：感知工具名单按本轮实际注入动态生成——只点名 admin_inventory 与已注入的 *_list 工具，
 * 部分管理能力的自定义模式不会引用不存在的工具；内置管理模式能力齐全，
 * 产出覆盖旧静态文本列出的全部名单（settings_admin_list/audit_list/mode_list 也一并纳入）。
 */
private fun managementSection(tools: List<Tool>): String = buildString {
    appendLine("## Mode: Management")
    appendLine("- You are in management mode: your work targets the assistant's own skills, MCP servers, providers, modes, logs, and environment.")
    val inspectTools = buildList {
        if (tools.any { it.name == "admin_inventory" }) add("admin_inventory")
        addAll(tools.map { it.name }.filter { it.endsWith("_list") })
    }
    if (inspectTools.isNotEmpty()) {
        appendLine("- Inspect first: call ${inspectTools.joinToString(", ")} before changing anything.")
    }
    appendLine("- Before a write, state the affected scope and expected effect, and wait for approval whenever the tool requires it.")
    appendLine("- Prefer reversible changes; include a rollback or verification plan when modifying persistent configuration.")
    appendLine("- After a settings-backed write, verify the result; if the change is wrong, use management_undo to revert it.")
    appendLine("- Call out explicitly when an action affects global settings, other assistants, or running conversations.")
    appendLine("- For file work in the workspace, keep workspace behavior: plan, execute continuously, and verify before reporting; management writes remain approval-gated.")
}

private val MODE_MINIMAL_SECTION = """
    ## Mode: Minimal
    - You are in minimal mode: do not call tools unless the user explicitly asks for that capability.
    - Answer from knowledge and reasoning first; when in doubt, prefer no tool call.
    - If a tool is genuinely necessary, keep it single-purpose and explain why.
""".trimIndent()

private val PLAN_AND_ACT_SECTION = """
    ## Plan & Act
    - Restate the goal in one sentence before acting, then pick the fewest high-leverage steps.
    - Do not call tools out of habit; call one only when it clearly advances the goal.
    - Prefer the most direct tool: read before write, targeted query before broad scan.
    - If a tool errors, adjust and retry once; do not repeat the same call or dump large output verbatim.
    - Before finalizing, verify the answer actually addresses the user's request.
""".trimIndent()

private val ASK_USER_SECTION = """
    ## Asking the User
    - When information is missing, ambiguous, or a choice materially changes the result, ask the user.
    - Prefer asking a focused question (one at a time, with options when possible) over guessing.
    - Do not ask what you can determine yourself through tools or reasoning.
""".trimIndent()

private val SUB_AGENT_DELEGATION_SECTION = """
    ## Sub-Agent Delegation
    Sub-agents run in isolated contexts with their own models and tools. They are for work you should
    not inline into your own context.

    When to delegate:
    - The task splits into several **independent** workstreams (bulk research, long-document analysis,
      parallel verification, comparing options) whose combined output is more than you should load here.
    - Otherwise do it yourself — do not delegate single-file reads or simple lookups.

    How to run them in parallel:
    - Call `spawn_subagent` once per sub-agent **in the same reply** — each call dispatches one worker,
      so multiple calls in one reply run them concurrently. Record every returned `taskId`.
    - Do not sit idle after spawning. Continue your own work: plan, analyze, run base steps that need no
      sub-agent result.

    Getting results:
    - Sub-agents wake you **automatically** when they complete — their result is injected into your
      context, so you never block waiting for them. There is no await tool.
    - You may end this round even while sub-agents run; they finish in the background and wake you.
    - Cross-verify, synthesize and summarize their results into the best answer. Do not relay sub-agent
      output verbatim — you know the user's needs best.
    - `dispatched` is only a dispatch marker, never a result. A timed-out or failed task may be
      auto-retried by the system (same task, context preserved); if the final status is still
      timeout/failed, answer from what you already have — do not respawn yourself.
    - When a sub-agent result corresponds to an item you track in the todo list, update that item
      (in_progress / completed) in your synthesis round.
""".trimIndent()

/**
 * 工具分组清单：按 [classifyToolFamily] 把工具聚成 <group> → 用途 的一行，帮模型建立"工具地图"。
 * 只分组、不展开单工具——单工具细节在其自身 description 里，避免重复占 token。
 * 判定与调试统计共用同一份 [classifyToolFamily]，新增工具族只需改这一处。
 */
private fun groupToolsForPrompt(tools: List<Tool>): String {
    val groups = linkedMapOf<String, MutableList<String>>()
    tools.forEach { tool ->
        val family = classifyToolFamily(tool.name)
        groups.getOrPut(family.displayLabel) { mutableListOf() }.add(tool.name)
    }

    if (groups.isEmpty()) return ""

    return buildString {
        appendLine("## Tool Groups")
        appendLine("Available tools are grouped below. Within a group, pick the most specific tool for the job:")
        groups.forEach { (group, names) ->
            appendLine("- $group: ${names.take(6).joinToString(", ")}${if (names.size > 6) ", …" else ""}")
        }
    }
}
