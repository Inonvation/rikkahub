package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.SkillFrontmatterParser
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.files.SkillPaths

/**
 * Skill 工具组：读取与使用 skill，并暴露"当前助手启用的 skill"状态供 AI 查看和编辑。
 *
 * 管理工具参照 [me.rerere.rikkahub.data.ai.tools.createMcpManagerTools] 的模式，
 * 写操作通过 [setEnabledSkills] 回调交给调用方（[me.rerere.rikkahub.service.ChatService]
 * 中落到 `SettingsStore.updateAssistantSkills`）。
 */
fun createSkillTools(
    enabledSkills: Set<String>,
    allSkills: List<SkillMetadata>,
    setEnabledSkills: suspend (Set<String>) -> Unit = {},
): List<Tool> {
    val available = allSkills.filter { it.name in enabledSkills }

    fun skillEnabled(skill: SkillMetadata): Boolean = skill.name in enabledSkills

    fun skillStateLine(skill: SkillMetadata): String = buildString {
        appendLine("- name: ${skill.name}")
        appendLine("  description: ${skill.description}")
        appendLine("  enabled for current assistant: ${skillEnabled(skill)}")
        if (!skill.compatibility.isNullOrBlank()) {
            appendLine("  compatibility: ${skill.compatibility}")
        }
    }

    fun listSummary(): String {
        if (allSkills.isEmpty()) {
            return "No skills installed. Skills live in the app's skills directory; import a skill to make it available."
        }
        return buildString {
            appendLine("Installed skills:")
            allSkills.forEach { appendLine(skillStateLine(it)) }
            appendLine()
            appendLine("Enabled for current assistant: ${enabledSkills.sorted().joinToString(", ").ifEmpty { "(none)" }}")
        }
    }

    val systemPrompt: (me.rerere.ai.provider.Model, List<me.rerere.ai.ui.UIMessage>) -> String = { _, _ ->
        buildString {
            appendLine("**Skills**")
            appendLine("The app ships a skills system. Each skill is a directory containing a SKILL.md with specialized instructions. You can read skill content with `use_skill`, inspect which skills the current assistant has enabled with `skill_admin_list`, and enable or disable them for the current assistant with `skill_admin_set_enabled`.")
            appendLine("<enabled_skills>")
            available.forEach { skill ->
                appendLine("  <skill>")
                appendLine("    <name>${skill.name}</name>")
                appendLine("    <description>${skill.description}</description>")
                appendLine("  </skill>")
            }
            append("</enabled_skills>")
            appendLine()
            appendLine("Use `skill_admin_list` to inspect all installed skills and their enablement.")
        }
    }

    val tools = mutableListOf<Tool>()

    if (available.isNotEmpty()) {
        tools += Tool(
            name = "use_skill",
            description = """
                Load and apply a skill to get specialized instructions or capabilities.
                Call this tool when the user's request matches one of the available skills.
                Avoid calling it when no enabled skill clearly matches the request.
            """.trimIndent(),
            systemPrompt = systemPrompt,
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "The name of the skill to use")
                        })
                        put("path", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Optional relative path to a file inside the skill directory. Omit to read the default SKILL.md instructions. Only use paths extracted from Markdown links in the SKILL.md content. Do NOT guess or infer paths."
                            )
                        })
                    },
                    required = listOf("name")
                )
            },
            execute = {
                val name = it.jsonObject["name"]?.jsonPrimitive?.content
                    ?: error("name is required")
                val skill = available.firstOrNull { skill -> skill.name == name }
                    ?: error("Skill '$name' is not enabled for the current assistant. Use `skill_admin_list` to see all skills and `skill_admin_set_enabled` to enable it.")
                val path = it.jsonObject["path"]?.jsonPrimitive?.content
                val content = if (path.isNullOrBlank()) {
                    require(skill.skillFile.exists()) { "Skill '$name' not found" }
                    SkillFrontmatterParser.extractBody(skill.skillFile.readText())
                } else {
                    val target = SkillPaths.resolveSkillFile(skill.skillDir, path)
                        ?: error("Path '$path' is outside the skill directory")
                    require(target.exists()) { "File '$path' not found in skill '$name'" }
                    target.readText()
                }
                listOf(UIMessagePart.Text(content))
            }
        )
    }

    tools += Tool(
        name = "skill_admin_list",
        description = "List all installed skills and whether each one is enabled for the current assistant. Use when you need to inspect available skills or their enablement.",
        systemPrompt = systemPrompt,
        parameters = {
            InputSchema.Obj(properties = buildJsonObject {})
        },
        execute = {
            listOf(UIMessagePart.Text(listSummary()))
        },
    )

    tools += Tool(
        name = "skill_admin_set_enabled",
        description = "Enable or disable a skill for the current assistant. Use when the user asks to manage skills. The change takes effect from the next message; call `skill_admin_list` first to see available skill names.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "The name of the skill to enable or disable")
                    })
                    put("enabled", buildJsonObject {
                        put("type", "boolean")
                        put("description", "true to enable the skill for the current assistant, false to disable it")
                    })
                },
                required = listOf("name", "enabled")
            )
        },
        execute = {
            val name = it.jsonObject["name"]?.jsonPrimitive?.content
                ?: error("name is required")
            val enabled = it.jsonObject["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                ?: error("enabled must be a boolean")
            val skill = allSkills.firstOrNull { skill -> skill.name == name }
                ?: error("Skill '$name' not found. Use `skill_admin_list` to see available skills.")
            val newEnabled = if (enabled) enabledSkills + name else enabledSkills - name
            setEnabledSkills(newEnabled)
            listOf(
                UIMessagePart.Text(
                    "Skill '${skill.name}' ${if (enabled) "enabled" else "disabled"} for the current assistant."
                )
            )
        },
    )

    return tools
}
