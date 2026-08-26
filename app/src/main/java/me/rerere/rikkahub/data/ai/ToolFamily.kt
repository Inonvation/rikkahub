package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.ai.tools.MCP_CALL_NAME
import me.rerere.rikkahub.data.ai.tools.MCP_LIST_NAME

/**
 * 工具族单一分类来源。
 *
 * 两类消费方共享同一份判定，避免前缀映射漂移：
 * 1. [groupToolsForPrompt]（模型可见的工具分组清单，见 AgentBehaviorPrompt）
 * 2. [toolFamilyForMetrics]（调试页的家族统计，见 GenerationPrompts）
 *
 * [metricLabel] 是统计口径的短名（供 PromptMetrics.lastToolFamilies 展示），
 * [displayLabel] 是模型可见的口径长名（供 Agent Behavior 的工具分组展示）。
 * 两者可不同；判定始终以 [classifyToolFamily] 为唯一事实来源。
 */
enum class ToolFamily(val metricLabel: String, val displayLabel: String) {
    MCP("mcp", "MCP servers"),
    MCP_ADMIN("mcp-admin", "MCP management"),
    SEARCH("search", "web search / scrape"),
    WORKSPACE("workspace", "workspace"),
    TRUSTED_FOLDER("trusted_folder", "trusted folders"),
    KNOWLEDGE("knowledge", "knowledge base"),
    STUDY("study", "study tools"),
    DOCUMENT("document", "document reading"),
    HISTORY("history", "conversation history"),
    TODO("todo", "todo"),
    ASK_USER("ask_user", "ask user"),
    MEMORY("memory", "memory"),
    SUBAGENT("subagent", "subagents"),
    SKILL("skill", "skills"),
    MANAGEMENT("management", "management"),
    /** 本地/设备工具（时间/剪贴板/JS/HTML/TTS/屏幕/日历/`eval_*` 等） */
    LOCAL("local", "local device"),
    /** 未归类（未知工具名的兜底，避免误并入 LOCAL） */
    OTHER("other", "other"),
}

/**
 * 工具名 → 工具族的唯一判定。新增工具族时只改这里即可。
 *
 * 注意判断顺序：`mcp_admin_*` 必须在 `mcp__*` 之前判定，
 * 避免 admin 工具被误合并进 MCP 使用族。
 */
internal fun classifyToolFamily(name: String): ToolFamily = when {
    name == MCP_LIST_NAME || name == MCP_CALL_NAME || name.startsWith("mcp__") -> ToolFamily.MCP
    name.startsWith("mcp_admin_") -> ToolFamily.MCP_ADMIN
    name.startsWith("search_web") || name.startsWith("scrape_web") -> ToolFamily.SEARCH
    name.startsWith("workspace_") -> ToolFamily.WORKSPACE
    name.startsWith("trusted_folder_") -> ToolFamily.TRUSTED_FOLDER
    name.startsWith("kb_") -> ToolFamily.KNOWLEDGE
    name.startsWith("study_") || name.startsWith("save_") ||
        name.startsWith("quiz_") || name.startsWith("update_") ||
        name.startsWith("delete_") -> ToolFamily.STUDY
    name.startsWith("document_") -> ToolFamily.DOCUMENT
    name.startsWith("recent_") || name.startsWith("conversation_") -> ToolFamily.HISTORY
    name == "todo_write" -> ToolFamily.TODO
    name == "ask_user" -> ToolFamily.ASK_USER
    name == "memory_tool" -> ToolFamily.MEMORY
    name == "spawn_subagent" || name.startsWith("subagent") -> ToolFamily.SUBAGENT
    name.startsWith("use_skill") -> ToolFamily.SKILL
    // 管理/管理类工具族
    name.startsWith("provider_") || name.startsWith("assistant_") ||
        name.startsWith("settings_admin_") || name.startsWith("search_admin_") ||
        name.startsWith("admin_") || name.startsWith("workspace_admin_") ||
        name.startsWith("trusted_folder_admin_") || name.startsWith("knowledge_admin_") ||
        name.startsWith("conversation_admin_") || name.startsWith("audit_") ||
        name.startsWith("mode_") || name.startsWith("skill_admin_") ||
        name == "env_inspect" || name == "app_logs" -> ToolFamily.MANAGEMENT
    // 本地/设备工具
    name.startsWith("eval_") || name.startsWith("get_time") ||
        name.startsWith("clipboard") || name.startsWith("text_to_speech") ||
        name.startsWith("get_screen") || name.startsWith("calendar") ||
        name.startsWith("javascript") || name.startsWith("html_") -> ToolFamily.LOCAL
    else -> ToolFamily.OTHER
}
