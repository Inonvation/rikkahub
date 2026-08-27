package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 守护 [classifyToolFamily] 这个单一工具族分类来源：
 * 这里的判定同时驱动模型可见的工具分组（AgentBehaviorPrompt）与调试统计
 * （GenerationPrompts.toolFamilyForMetrics），因此每类工具族的命名前缀
 * 必须稳定映射，任何漂移都会让两处口径不一致。
 */
class ToolFamilyClassificationTest {

    @Test
    fun knownToolNamesMapToExpectedFamilies() {
        assertEquals(ToolFamily.MCP, classifyToolFamily("mcp__search"))
        assertEquals(ToolFamily.MCP_ADMIN, classifyToolFamily("mcp_admin_list"))
        assertEquals(ToolFamily.SEARCH, classifyToolFamily("search_web"))
        assertEquals(ToolFamily.SEARCH, classifyToolFamily("scrape_web"))
        assertEquals(ToolFamily.WORKSPACE, classifyToolFamily("workspace_read_file"))
        assertEquals(ToolFamily.TRUSTED_FOLDER, classifyToolFamily("trusted_folder_read"))
        assertEquals(ToolFamily.KNOWLEDGE, classifyToolFamily("kb_search"))
        assertEquals(ToolFamily.STUDY, classifyToolFamily("study_quiz"))
        assertEquals(ToolFamily.STUDY, classifyToolFamily("save_vocabulary"))
        assertEquals(ToolFamily.DOCUMENT, classifyToolFamily("document_read"))
        assertEquals(ToolFamily.HISTORY, classifyToolFamily("recent_chats"))
        assertEquals(ToolFamily.HISTORY, classifyToolFamily("conversation_history"))
        assertEquals(ToolFamily.TODO, classifyToolFamily("todo_write"))
        assertEquals(ToolFamily.ASK_USER, classifyToolFamily("ask_user"))
        assertEquals(ToolFamily.MEMORY, classifyToolFamily("memory_tool"))
        assertEquals(ToolFamily.SUBAGENT, classifyToolFamily("spawn_subagent"))
        assertEquals(ToolFamily.SKILL, classifyToolFamily("use_skill"))
        assertEquals(ToolFamily.MANAGEMENT, classifyToolFamily("assistant_update"))
        assertEquals(ToolFamily.MANAGEMENT, classifyToolFamily("settings_admin_set"))
        assertEquals(ToolFamily.MANAGEMENT, classifyToolFamily("env_inspect"))
        assertEquals(ToolFamily.LOCAL, classifyToolFamily("get_time"))
        assertEquals(ToolFamily.LOCAL, classifyToolFamily("eval_js"))
        assertEquals(ToolFamily.LOCAL, classifyToolFamily("clipboard_read"))
        assertEquals(ToolFamily.OTHER, classifyToolFamily("totally_unknown_tool"))
    }

    @Test
    fun adminMcpIsNotMergedIntoClients() {
        // 顺序敏感：mcp_admin_* 必须命中 MCP_ADMIN，不能被 mcp__* 分支抢先并入 MCP
        assertEquals(ToolFamily.MCP_ADMIN, classifyToolFamily("mcp_admin_add"))
        assertEquals(ToolFamily.MCP, classifyToolFamily("mcp__github__search_issues"))
    }

    @Test
    fun allFamiliesHaveDistinctMetricLabels() {
        assertEquals(
            ToolFamily.entries.size,
            ToolFamily.entries.map { it.metricLabel }.toSet().size,
        )
    }

    @Test
    fun everyFamilyCarriesAModelVisibleDisplayLabel() {
        ToolFamily.entries.forEach { family ->
            assertEquals(true, family.displayLabel.isNotBlank())
            assertEquals(true, family.metricLabel.isNotBlank())
        }
    }
}
