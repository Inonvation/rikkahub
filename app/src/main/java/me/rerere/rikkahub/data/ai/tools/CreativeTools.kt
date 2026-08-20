package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.os.Build
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.Logging
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Capability
import me.rerere.rikkahub.data.model.ChatMode
import me.rerere.rikkahub.data.model.ModeRefs
import me.rerere.rikkahub.data.model.ChatModePolicy
import me.rerere.rikkahub.data.model.CustomModeConfig
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.uuid.Uuid

private const val MAX_LOG_CHARS = 8 * 1024

/** 日志/环境文本中的密钥类字段脱敏：值替换为 [REDACTED]。 */
private val SECRET_PATTERN = Regex(
    """(?i)(authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|secret|password|private[_-]?key|bearer)\s*[:=]\s*["']?[^\s"',;]+"""
)

private fun redact(text: String): String =
    SECRET_PATTERN.replace(text) { m -> m.groupValues[1] + "=[REDACTED]" }

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun Settings.findCustomMode(ref: String): CustomModeConfig? {
    val id = ref.removePrefix(ModeRefs.CUSTOM_PREFIX)
    return customModes.find { it.id == id || it.id == ref }
}

private fun JsonElement.stringContentOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun JsonObject.stringArrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

/**
 * 管理模式专属工具组：环境/日志只读感知 + 提供商/新模式写入（需用户审批）。
 * 只允许追加 OpenAI 兼容提供商；新模式仅组合策略标志，不授予超出既有审批管线的权限。
 */
fun createCreativeTools(
    context: Context,
    settingsStore: SettingsStore,
    assistant: Assistant,
    conversationRepository: ConversationRepository,
): List<Tool> {
    return listOf(
        Tool(
            name = "env_inspect",
            description = "Read-only inspection of the app runtime environment: app version, Android API level, assistant name, workspace binding, enabled MCP servers, enabled skills, and knowledge base count. Returns only non-sensitive facts.",
            parameters = { null },
            execute = { _ ->
                val settings = settingsStore.settingsFlow.value
                val workspaceSummary = if (assistant.workspaceId != null) {
                    "bound (${assistant.workspaceId})"
                } else {
                    "none"
                }
                val mcpCount = settings.mcpServers.count {
                    it.id in assistant.mcpServers && it.commonOptions.enable
                }
                val skillsText = assistant.enabledSkills.sorted().joinToString(", ")
                    .ifEmpty { "(none)" }
                val text = buildString {
                    appendLine("app_version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("android_api_level: ${Build.VERSION.SDK_INT}")
                    appendLine("assistant: " + (assistant.name.ifBlank { "(unnamed)" }))
                    appendLine("workspace: $workspaceSummary")
                    appendLine("enabled_mcp_servers: $mcpCount")
                    appendLine("enabled_skills: $skillsText")
                    appendLine("knowledge_bases: ${assistant.knowledgeBaseIds.size}")
                }
                listOf(UIMessagePart.Text(text))
            },
        ),
        Tool(
            name = "app_logs",
            description = "Read-only: return recent in-memory app logs (newest first, up to 40 entries). Sensitive values (API keys, tokens, passwords, secrets, Authorization headers) are redacted to [REDACTED].",
            parameters = { null },
            execute = { _ ->
                val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
                val lines = Logging.getTextLogs().take(40).mapNotNull { entry ->
                    val message = redact(entry.message)
                    if (message.isBlank()) {
                        null
                    } else {
                        "[${formatter.format(Date(entry.timestamp))}] ${entry.tag}: $message"
                    }
                }
                val text = if (lines.isEmpty()) "(no recent app logs)" else lines.joinToString("\n")
                listOf(UIMessagePart.Text(text.take(MAX_LOG_CHARS)))
            },
        ),
        Tool(
            name = "provider_add",
            description = "Add a new OpenAI-compatible provider. Required fields: name, baseUrl, apiKey. Optional: models (array of model id strings). Requires user approval. Existing providers are never modified.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Provider display name"))
                        })
                        put("baseUrl", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("OpenAI-compatible base URL, e.g. https://api.example.com/v1"))
                        })
                        put("apiKey", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("API key for this provider"))
                        })
                        put("models", buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("description", JsonPrimitive("Optional model id strings, e.g. [\"gpt-4o\"]"))
                        })
                    },
                    required = listOf("name", "baseUrl", "apiKey"),
                )
            },
            needsApproval = { true },
            execute = { args ->
                val input = args as? JsonObject
                    ?: return@Tool listOf(UIMessagePart.Text("Error: invalid arguments."))
                val name = input.str("name")?.trim().orEmpty()
                val baseUrl = input.str("baseUrl")?.trim().orEmpty()
                val apiKey = input.str("apiKey")?.trim().orEmpty()
                if (name.isEmpty() || baseUrl.isEmpty() || apiKey.isEmpty()) {
                    return@Tool listOf(UIMessagePart.Text("Error: name, baseUrl and apiKey are required."))
                }
                val modelsElement = input["models"]
                if (modelsElement != null && modelsElement !is JsonArray) {
                    return@Tool listOf(UIMessagePart.Text("Error: models must be an array."))
                }
                val modelIds = input.stringArrayOrNull("models")
                    ?.mapNotNull { it.stringContentOrNull() } ?: emptyList()
                val provider = ProviderSetting.OpenAI(
                    id = Uuid.random(),
                    enabled = true,
                    name = name,
                    models = modelIds.map { Model(modelId = it, displayName = it) },
                    apiKey = apiKey,
                    baseUrl = baseUrl,
                )
                val current = settingsStore.settingsFlow.value
                if (current.providers.any { it.name.equals(name, ignoreCase = true) }) {
                    return@Tool listOf(UIMessagePart.Text("Error: provider with name \"$name\" already exists. Existing providers are never modified."))
                }
                settingsStore.update { it.copy(providers = it.providers + provider) }
                listOf(UIMessagePart.Text("Provider \"$name\" added (id=${provider.id}). This write required user approval."))
            },
        ),
        Tool(
            name = "mode_create",
            description = "Create a custom capability mode. Required: name. Provide capabilities (full list) OR base + add/remove to derive from an existing mode (built-in name or custom mode id). Capability names: LOCAL_TOOLS, SEARCH, DOCUMENT, WORKSPACE, TRUSTED_FOLDER, SKILL_USE, SKILL_ADMIN, MCP_USE, MCP_ADMIN, MEMORY, TODO, SUBAGENT, STUDY, HISTORY, KNOWLEDGE, PROMPT_INJECTION, REMINDERS, TOOL_SYSTEM_PROMPT, CREATIVE_TOOLS. Omit everything to start from the standard base. Requires user approval. The new mode appears at the end of the mode picker and in Settings.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Mode display name"))
                        })
                        put("description", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Optional mode description"))
                        })
                        put("capabilities", buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("description", JsonPrimitive("Full capability list, e.g. [\"WORKSPACE\", \"MCP_USE\"]. Mutually exclusive with base."))
                        })
                        put("base", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Base mode to derive from: STANDARD/PTC/MINIMAL/CREATIVE or an existing custom mode id"))
                        })
                        put("add", buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("description", JsonPrimitive("Capability names to add on top of base"))
                        })
                        put("remove", buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("description", JsonPrimitive("Capability names to remove from base"))
                        })
                    },
                    required = listOf("name"),
                )
            },
            needsApproval = { true },
            execute = { args ->
                val input = args as? JsonObject
                    ?: return@Tool listOf(UIMessagePart.Text("Error: invalid arguments."))
                val name = input.str("name")?.trim().orEmpty()
                if (name.isEmpty()) {
                    return@Tool listOf(UIMessagePart.Text("Error: name is required."))
                }
                val current = settingsStore.settingsFlow.value
                if (current.customModes.any { it.name.equals(name, ignoreCase = true) }) {
                    return@Tool listOf(UIMessagePart.Text("Error: custom mode with name \"" + name + "\" already exists. Delete it in Settings > 默认模式 first or pick another name."))
                }
                val capabilitiesElement = input["capabilities"]
                if (capabilitiesElement != null && capabilitiesElement !is JsonArray) {
                    return@Tool listOf(UIMessagePart.Text("Error: capabilities must be an array."))
                }
                val requested = capabilitiesElement?.jsonArray
                    ?.mapNotNull { it.stringContentOrNull() } ?: emptyList()
                val addElement = input["add"]
                if (addElement != null && addElement !is JsonArray) {
                    return@Tool listOf(UIMessagePart.Text("Error: add must be an array."))
                }
                val removeElement = input["remove"]
                if (removeElement != null && removeElement !is JsonArray) {
                    return@Tool listOf(UIMessagePart.Text("Error: remove must be an array."))
                }
                val addNames = input.stringArrayOrNull("add")
                    ?.mapNotNull { it.stringContentOrNull() } ?: emptyList()
                val removeNames = input.stringArrayOrNull("remove")
                    ?.mapNotNull { it.stringContentOrNull() } ?: emptyList()
                val baseName = input.str("base")?.trim().orEmpty()
                val allNames = requested + addNames + removeNames
                val unknown = allNames.filterNot { runCatching { Capability.valueOf(it) }.isSuccess }
                if (unknown.isNotEmpty()) {
                    return@Tool listOf(
                        UIMessagePart.Text(
                            "Error: unknown capability name(s): " + unknown.joinToString(", ") +
                                ". Valid names: " + Capability.entries.joinToString(", ") { it.name }
                        )
                    )
                }
                val capabilities = if (capabilitiesElement != null) {
                    // 完整清单优先，忽略 base/add/remove
                    requested.map { Capability.valueOf(it) }.toSet()
                } else {
                    val baseCaps = when {
                        baseName.isBlank() -> ChatMode.STANDARD.policy().capabilities
                        else -> {
                            val builtin = runCatching { ChatMode.valueOf(baseName) }.getOrNull()
                            val customBase = current.customModes.firstOrNull {
                                it.id == baseName ||
                                    it.id == baseName.removePrefix(ModeRefs.CUSTOM_PREFIX)
                            }
                            builtin?.policy()?.capabilities
                                ?: customBase?.policy?.capabilities
                                ?: return@Tool listOf(
                                    UIMessagePart.Text(
                                        "Error: base mode \"" + baseName + "\" not found. Use a built-in name (STANDARD/PTC/MINIMAL/CREATIVE) or an existing custom mode id."
                                    )
                                )
                        }
                    }
                    baseCaps + addNames.map { Capability.valueOf(it) } - removeNames.map { Capability.valueOf(it) }
                }
                val policy = ChatModePolicy(capabilities = capabilities)
                val custom = CustomModeConfig(
                    id = Uuid.random().toString(),
                    name = name,
                    description = input.str("description") ?: "",
                    policy = policy,
                )
                settingsStore.updateCustomModes(current.customModes + custom)
                listOf(UIMessagePart.Text("Custom mode \"" + custom.name + "\" created (id=" + custom.id + "). Select it from the mode picker in the chat input footer or Settings. This write required user approval."))
            },
        ),
        Tool(
            name = "mode_update",
            description = "Update an existing custom capability mode. Required: id (custom mode id or custom:<id>). Optional: name, description, capabilities (full list) OR add/remove to adjust the current capability list. Capability names: LOCAL_TOOLS, SEARCH, DOCUMENT, WORKSPACE, TRUSTED_FOLDER, SKILL_USE, SKILL_ADMIN, MCP_USE, MCP_ADMIN, MEMORY, TODO, SUBAGENT, STUDY, HISTORY, KNOWLEDGE, PROMPT_INJECTION, REMINDERS, TOOL_SYSTEM_PROMPT, CREATIVE_TOOLS. Requires user approval. Existing conversations keep referencing this mode id.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Custom mode id or custom:<id>"))
                        })
                        put("name", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("New mode display name"))
                        })
                        put("description", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("New mode description"))
                        })
                        put("capabilities", buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("description", JsonPrimitive("Full replacement capability list. Mutually exclusive with add/remove."))
                        })
                        put("add", buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("description", JsonPrimitive("Capability names to add on top of the current list"))
                        })
                        put("remove", buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("description", JsonPrimitive("Capability names to remove from the current list"))
                        })
                    },
                    required = listOf("id"),
                )
            },
            needsApproval = { true },
            execute = { args ->
                val input = args as? JsonObject
                    ?: return@Tool listOf(UIMessagePart.Text("Error: invalid arguments."))
                val ref = input.str("id")?.trim().orEmpty()
                if (ref.isEmpty()) {
                    return@Tool listOf(UIMessagePart.Text("Error: id is required."))
                }
                val current = settingsStore.settingsFlow.value
                val target = current.findCustomMode(ref)
                    ?: return@Tool listOf(UIMessagePart.Text("Error: custom mode \"$ref\" not found."))
                val name = input.str("name")?.trim().orEmpty()
                val description = input.str("description")
                val capabilitiesElement = input["capabilities"]
                if (capabilitiesElement != null && capabilitiesElement !is JsonArray) {
                    return@Tool listOf(UIMessagePart.Text("Error: capabilities must be an array."))
                }
                val requested = capabilitiesElement?.jsonArray
                    ?.mapNotNull { it.stringContentOrNull() } ?: emptyList()
                val addElement = input["add"]
                if (addElement != null && addElement !is JsonArray) {
                    return@Tool listOf(UIMessagePart.Text("Error: add must be an array."))
                }
                val removeElement = input["remove"]
                if (removeElement != null && removeElement !is JsonArray) {
                    return@Tool listOf(UIMessagePart.Text("Error: remove must be an array."))
                }
                val addNames = input.stringArrayOrNull("add")
                    ?.mapNotNull { it.stringContentOrNull() } ?: emptyList()
                val removeNames = input.stringArrayOrNull("remove")
                    ?.mapNotNull { it.stringContentOrNull() } ?: emptyList()
                if (name.isEmpty() && description == null &&
                    capabilitiesElement == null && addElement == null && removeElement == null
                ) {
                    return@Tool listOf(
                        UIMessagePart.Text("Error: provide at least one field to update: name, description, capabilities, add or remove.")
                    )
                }
                val allNames = requested + addNames + removeNames
                val unknown = allNames.filterNot { runCatching { Capability.valueOf(it) }.isSuccess }
                if (unknown.isNotEmpty()) {
                    return@Tool listOf(
                        UIMessagePart.Text(
                            "Error: unknown capability name(s): " + unknown.joinToString(", ") +
                                ". Valid names: " + Capability.entries.joinToString(", ") { it.name }
                        )
                    )
                }
                val updatedName = name.ifEmpty { target.name }
                if (current.customModes.any { it.id != target.id && it.name.equals(updatedName, ignoreCase = true) }) {
                    return@Tool listOf(UIMessagePart.Text("Error: custom mode with name \"$updatedName\" already exists."))
                }
                val capabilities = if (capabilitiesElement != null) {
                    requested.map { Capability.valueOf(it) }.toSet()
                } else {
                    target.policy.capabilities +
                        addNames.map { Capability.valueOf(it) } -
                        removeNames.map { Capability.valueOf(it) }
                }
                val updated = target.copy(
                    name = updatedName,
                    description = description?.let { it.trim() } ?: target.description,
                    policy = ChatModePolicy(capabilities = capabilities),
                )
                settingsStore.update { settings ->
                    settings.copy(
                        customModes = settings.customModes.map {
                            if (it.id == target.id) updated else it
                        }
                    )
                }
                listOf(UIMessagePart.Text("Custom mode \"" + updated.name + "\" updated (id=" + updated.id + "). This write required user approval."))
            },
        ),
        Tool(
            name = "mode_delete",
            description = "Delete an existing custom capability mode. Required: id (custom mode id or custom:<id>). Counts conversations referencing the mode first; those conversations fall back to the standard mode after deletion. Global and assistant defaults pointing to this mode are cleared. Requires user approval.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Custom mode id or custom:<id>"))
                        })
                    },
                    required = listOf("id"),
                )
            },
            needsApproval = { true },
            execute = { args ->
                val input = args as? JsonObject
                    ?: return@Tool listOf(UIMessagePart.Text("Error: invalid arguments."))
                val ref = input.str("id")?.trim().orEmpty()
                if (ref.isEmpty()) {
                    return@Tool listOf(UIMessagePart.Text("Error: id is required."))
                }
                val current = settingsStore.settingsFlow.value
                val target = current.findCustomMode(ref)
                    ?: return@Tool listOf(UIMessagePart.Text("Error: custom mode \"$ref\" not found."))
                val modeRef = ModeRefs.custom(target.id)
                val conversationCount = conversationRepository.countConversationsByMode(modeRef)
                settingsStore.update { settings ->
                    settings.copy(
                        customModes = settings.customModes.filterNot { it.id == target.id },
                        defaultMode = settings.defaultMode?.takeUnless { it == modeRef },
                        assistants = settings.assistants.map { assistant ->
                            if (assistant.defaultMode == modeRef) assistant.copy(defaultMode = null) else assistant
                        },
                    )
                }
                listOf(
                    UIMessagePart.Text(
                        "Custom mode \"" + target.name + "\" deleted (id=" + target.id + "). " +
                            conversationCount + " conversation(s) referencing it now fall back to the standard mode. " +
                            "This write required user approval."
                    )
                )
            },
        ),
    )
}
