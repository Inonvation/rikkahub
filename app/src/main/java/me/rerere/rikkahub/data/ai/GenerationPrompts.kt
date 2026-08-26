package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalString
import java.security.MessageDigest
import java.time.LocalDate

internal const val PROMPT_REVISION = "2026-08-21-v1"

internal fun currentDateLabel(): String = LocalDate.now().toLocalString(true)

/** 提示词内容指纹：任一稳定提示词片段变化时测试会失败，提醒同步升级 [PROMPT_REVISION]。 */
internal fun promptFingerprint(vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part -> digest.update(part.toByteArray(Charsets.UTF_8)) }
    return digest.digest().take(8).joinToString("") { "%02x".format(it) }
}

/** 最近一次主生成链路的系统提示词指标，供调试页展示。 */
object PromptMetrics {
    @Volatile
    var lastSystemPromptChars: Int = 0

    @Volatile
    var lastApproxTokens: Int = 0

    @Volatile
    var lastToolCount: Int = 0

    /** provider 收到 tools 数组（function schema）的近似字符成本。 */
    @Volatile
    var lastToolSchemaChars: Int = 0

    /** 各工具族数量，用于看清「你好」到底被哪些能力撑大。 */
    @Volatile
    var lastToolFamilies: Map<String, Int> = emptyMap()

    val revision: String get() = PROMPT_REVISION
}

/**
 * 调试用工具族归类。与 AgentBehaviorPrompt 的工具分组保持一致的粗粒度口径，
 * 但只用于统计，不参与模型可见文本。
 */
internal fun toolFamilyForMetrics(name: String): String = when {
    name == "mcp_list" || name == "mcp_call" || name.startsWith("mcp__") -> "mcp"
    name.startsWith("mcp_admin_") -> "mcp-admin"
    name.startsWith("search_web") || name.startsWith("scrape_web") -> "search"
    name.startsWith("workspace_") -> "workspace"
    name.startsWith("trusted_folder_") -> "trusted_folder"
    name.startsWith("kb_") -> "knowledge"
    name.startsWith("study_") || name.startsWith("save_") ||
        name.startsWith("quiz_") || name.startsWith("update_") ||
        name.startsWith("delete_") -> "study"
    name.startsWith("document_") -> "document"
    name.startsWith("recent_") || name.startsWith("conversation_") -> "history"
    name == "todo_write" -> "todo"
    name == "ask_user" -> "ask_user"
    name == "memory_tool" -> "memory"
    name == "spawn_subagent" || name.startsWith("subagent") -> "subagent"
    name.startsWith("use_skill") -> "skill"
    name.startsWith("provider_") || name.startsWith("assistant_") ||
        name.startsWith("settings_admin_") || name.startsWith("search_admin_") ||
        name.startsWith("admin_") || name.startsWith("workspace_admin_") ||
        name.startsWith("trusted_folder_admin_") || name.startsWith("knowledge_admin_") ||
        name.startsWith("conversation_admin_") || name.startsWith("audit_") ||
        name.startsWith("mode_") || name.startsWith("skill_admin_") ||
        name == "env_inspect" || name == "app_logs" -> "management"
    else -> "local"
}

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    buildString {
        appendLine()
        append("**Memories**")
        appendLine()
        append("Memories from past conversations. Reference them when relevant.")
        appendLine()
        val json = buildJsonArray {
            memories.forEach { memory ->
                add(buildJsonObject {
                    put("id", memory.id)
                    put("content", memory.content)
                })
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
    }

/**
 * 收集本轮每个工具要注入 system 的说明，并按内容去重。
 *
 * 同一份 systemPrompt（如 SkillTools 挂在多个技能工具上）只注入一次，避免重复粘贴
 * 相同段落放大 system 体积、稀释指令。去重以「内容完全相同」为准，不会误伤真正独立的工具说明。
 */
internal fun buildToolSystemPrompts(
    tools: List<Tool>,
    model: Model,
    messages: List<UIMessage>,
): List<String> =
    tools.mapNotNull { tool ->
        tool.systemPrompt(model, messages).takeIf { it.isNotBlank() }
    }.distinct()
