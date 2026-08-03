package me.rerere.rikkahub.data.ai.prompts

import me.rerere.ai.core.Tool

/**
 * 主代理行为层提示词：系统提示词末尾追加的决策/工具/子代理/提问准则。
 *
 * 借鉴 Claude Code 的做法——独立短块动态拼装，不写成一长段层级：
 * 每条规则一个短句，写清"何时用 / 何时不用"优于"必须做 X"。
 * 英文编写（主流模型对英文指令理解更充分），与其它内置 prompt 一致。
 */
internal fun buildAgentBehaviorPrompt(tools: List<Tool>): String {
    val toolGroups = groupToolsForPrompt(tools)
    return buildString {
        appendLine("## Agent Behavior")
        appendLine()
        appendLine(DECISION_MAKING_SECTION)
        appendLine()
        appendLine(TOOL_USAGE_SECTION)
        if (toolGroups.isNotBlank()) {
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

private val DECISION_MAKING_SECTION = """
    ## Decision-Making
    - Before acting, restate the user's goal in one sentence and identify what is actually needed.
    - Do not start with tool calls out of habit. Only call a tool when it clearly advances the goal.
    - Prefer the fewest, highest-leverage steps; avoid doing work that does not move the answer forward.
    - Before you finalize a reply, check that it actually answers the user's question — if not, ask or gather what is missing.
""".trimIndent()

private val TOOL_USAGE_SECTION = """
    ## Tool Usage
    - A tool is a means, not a reflex. Call one only when you are confident it will advance the task;
      if you are unsure, ask the user to clarify instead of guessing.
    - When several tools could work, pick the most direct one — read before writing, targeted queries before broad scans.
    - If a tool returns an error, read the error, adjust your approach, and try once more; do not blindly repeat the same call.
    - If tool output is large, use it to answer, do not dump it back verbatim.
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
 * 工具分组清单：按命名前缀把工具聚成 <group> → 用途 的一行，帮模型建立"工具地图"。
 * 只分组、不展开单工具——单工具细节在其自身 description 里，避免重复占 token。
 */
private fun groupToolsForPrompt(tools: List<Tool>): String {
    val groups = linkedMapOf<String, MutableList<String>>()
    val known = setOf("spawn_subagent", "ask_user", "memory_tool", "todo_write")
    tools.forEach { tool ->
        val prefix = when {
            tool.name in known -> tool.name
            tool.name.startsWith("mcp__") -> "MCP servers"
            tool.name.startsWith("workspace_") -> "workspace"
            tool.name.startsWith("search_web") -> "web search"
            tool.name.startsWith("scrape_web") -> "web scrape"
            tool.name.startsWith("kb_") -> "knowledge base"
            tool.name.startsWith("study_") || tool.name.startsWith("save_") ||
                tool.name.startsWith("quiz_") || tool.name.startsWith("update_") ||
                tool.name.startsWith("delete_") -> "study tools"
            tool.name.startsWith("document_") -> "document reading"
            tool.name.startsWith("recent_") || tool.name.startsWith("conversation_") -> "conversation history"
            tool.name.startsWith("eval_") || tool.name.startsWith("get_time") ||
                tool.name.startsWith("clipboard") || tool.name.startsWith("text_to_speech") ||
                tool.name.startsWith("get_screen") || tool.name.startsWith("calendar") -> "local device"
            tool.name.startsWith("use_skill") -> "skills"
            else -> "other"
        }
        groups.getOrPut(prefix) { mutableListOf() }.add(tool.name)
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
