package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.config.AgentConfigExporter
import me.rerere.rikkahub.data.config.AgentConfigImporter
import me.rerere.rikkahub.data.config.AgentConfigPaths
import me.rerere.rikkahub.data.config.AgentConfigRepository
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.management.ManagementAuditStore
import me.rerere.rikkahub.data.management.ManagementRollbackStore

/**
 * 管理模式 AI 的统一配置工具族。
 *
 * - config_view：配置地图（目录 + 版本 + 计数 + 脏标记），读取前先看这里；
 * - config_read：按白名单相对路径读取配置（已脱敏，密钥只显示引用占位）；
 * - config_refresh：从 DataStore 重新导出，拿到最新配置视图；
 * - config_schema：读取内置 JSON Schema，明确可配置字段与取值；
 * - config_validate：把现有文件按 DTO 解码校验，报逐文件状态；
 * - config_write：写一个配置文件（原子写 + 快照 + 修订 + JSON 语法校验），
 *   可选 applyToSettings 合并回 DataStore；全程审计，可回滚。
 *
 * 前五个工具对设置都是只读的（refresh 只写 agent/ 导出目录，不回写设置），不需要审批；
 * config_write 是写工具，需要用户审批，并复用管理模式审计/回滚基础设施。
 */
fun createAgentConfigTools(
    repository: AgentConfigRepository,
    settingsStore: SettingsStore,
    auditStore: ManagementAuditStore,
    rollbackStore: ManagementRollbackStore,
): List<Tool> = listOf(
    Tool(
        name = "config_view",
        description = "Read-only map of the unified agent configuration: schema version, source, export time, file list and counts of providers, MCP servers and assistants. Call this before config_read.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = {
            val view = withContext(Dispatchers.IO) { repository.view() }
            listOf(
                UIMessagePart.Text(
                    buildString {
                        appendLine("agent config (schema v${view.schemaVersion})")
                        appendLine(
                            "source=${view.source ?: "n/a"} " +
                                "settingsDataVersion=${view.settingsDataVersion ?: "n/a"} " +
                                "exportedAt=${view.exportedAt ?: "n/a"}"
                        )
                        appendLine(
                            "providers=${view.providerCount} " +
                                "mcp_servers=${view.mcpServerCount} " +
                                "assistants=${view.assistantCount}"
                        )
                        appendLine("files:")
                        view.files.forEach { f ->
                            appendLine("  ${f.path} (${f.bytes} bytes, ${f.status})")
                        }
                        appendLine("Read a file with config_read, e.g. path=\"config/providers.json\".")
                    }
                )
            )
        },
    ),
    Tool(
        name = "config_read",
        description = "Read one file of the unified agent configuration by relative path, e.g. \"config/providers.json\", \"config/mcp.json\", \"config/assistants/<id>.json\", \"manifest.json\". Secrets are never included - only reference placeholders like keystore:provider:<id>:secret.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "path",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Relative path under agent/, e.g. config/providers.json")
                        }
                    )
                },
                required = listOf("path"),
            )
        },
        execute = { args ->
            val path = args.jsonObject["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (path.isBlank()) {
                return@Tool listOf(UIMessagePart.Text("path is required"))
            }
            val content = withContext(Dispatchers.IO) { repository.readConfigFile(path) }
                ?: return@Tool listOf(
                    UIMessagePart.Text(
                        "Path not allowed or not found under agent/. " +
                            "Use config_view to list valid files."
                    )
                )
            listOf(UIMessagePart.Text(content))
        },
    ),
    Tool(
        name = "config_refresh",
        description = "Re-export the unified agent configuration from current app settings into agent/ and return per-file validation status. Read-only for settings; use it to get the newest config view.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = {
            val result = withContext(Dispatchers.IO) {
                AgentConfigExporter.export(
                    settings = settingsStore.settingsFlow.value,
                    agentRoot = repository.root,
                )
            }
            listOf(
                UIMessagePart.Text(
                    buildString {
                        appendLine("export ${if (result.ok) "ok" else "with errors"} (${result.exportedAt})")
                        result.files.forEach { (path, status) ->
                            appendLine("  $path: $status")
                        }
                    }
                )
            )
        },
    ),
    Tool(
        name = "config_schema",
        description = "Read the JSON Schema of the unified agent configuration so you know exactly which fields exist and their allowed values before editing or configuring. name is one of: providers, mcp, assistant, manifest.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "name",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "providers / mcp / assistant / manifest")
                        }
                    )
                },
                required = listOf("name"),
            )
        },
        execute = { args ->
            val name = args.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val content = withContext(Dispatchers.IO) { repository.schema(name) }
                ?: return@Tool listOf(
                    UIMessagePart.Text("Unknown schema name '$name'. Use providers, mcp, assistant or manifest.")
                )
            listOf(UIMessagePart.Text(content))
        },
    ),
    Tool(
        name = "config_validate",
        description = "Validate the exported agent configuration files by decoding them back to their schemas. Returns per-file ok/error status. Run config_refresh first if files may be stale.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = {
            val validation = withContext(Dispatchers.IO) { repository.validate() }
            listOf(
                UIMessagePart.Text(
                    buildString {
                        appendLine("validation ${if (validation.ok) "ok" else "with errors"}")
                        validation.results.forEach { (path, status) ->
                            appendLine("  $path: $status")
                        }
                        if (validation.results.isEmpty()) {
                            appendLine("  (no config files found; run config_refresh first)")
                        }
                    }
                )
            )
        },
    ),
    Tool(
        name = "config_write",
        description = "Write a config file under agent/ (e.g. \"config/providers.json\", \"config/mcp.json\", \"config/assistants/<id>.json\", \"manifest.json\"). The file is written atomically with a backup snapshot and a revision record; JSON files are syntax-checked before writing. When applyToSettings=true the change is also merged back into the app settings (secret fields always keep their local values). Requires user approval.",
        needsApproval = { true },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "path",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Relative path under agent/, e.g. config/providers.json")
                        }
                    )
                    put(
                        "content",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "New file content (JSON for config files)")
                        }
                    )
                    put(
                        "applyToSettings",
                        buildJsonObject {
                            put("type", "boolean")
                            put("description", "Also merge this file into app settings (default false)")
                        }
                    )
                },
                required = listOf("path", "content"),
            )
        },
        execute = { args ->
            val input = args.jsonObject
            val path = input["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val content = input["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val apply = input["applyToSettings"]?.jsonPrimitive?.booleanOrNull == true
            if (path.isBlank() || content.isBlank()) {
                return@Tool listOf(UIMessagePart.Text("path and content are required"))
            }
            audited(
                auditStore = auditStore,
                tool = "config_write",
                target = path,
                rollbackStore = rollbackStore,
                captureSettings = { settingsStore.settingsFlow.value },
            ) {
                withContext(Dispatchers.IO) {
                    val error = writeAgentConfigFile(repository, path, content)
                    if (error != null) {
                        return@withContext listOf(UIMessagePart.Text("Error: $error"))
                    }
                    val fileStatus = repository.validate().results[path] ?: "untracked"
                    val applyNote = if (apply) {
                        val current = settingsStore.settingsFlow.value
                        val updated = applyFileToSettings(current, repository, path)
                        if (updated !== current) {
                            settingsStore.update(updated)
                            ", applied to settings"
                        } else {
                            ", not appliable to settings"
                        }
                    } else {
                        ""
                    }
                    listOf(
                        UIMessagePart.Text("config_write: $path written (validate: $fileStatus)$applyNote")
                    )
                }
            }
        },
    ),
)

/**
 * config_write 的「写文件 + 校验」核心（同步、无 store 依赖，便于单测）：
 * JSON 语法校验 → 原子写（快照 + 修订）。
 * 返回 null 表示成功，否则返回错误信息。
 */
internal fun writeAgentConfigFile(
    repository: AgentConfigRepository,
    path: String,
    content: String,
): String? {
    val isJson = path.substringAfterLast('.', "").lowercase() == "json"
    if (isJson) {
        val jsonError = runCatching { Json.parseToJsonElement(content) }.exceptionOrNull()
        if (jsonError != null) return "invalid JSON: ${jsonError.message}"
    }
    return repository.writeConfigFile(path, content)
}

/**
 * config_write 的可选「应用到设置」分支（纯函数，无 store 依赖，便于单测）：
 * 按文件路径把 agent/ 合并回 Settings（密钥保留本地）；不可应用的路径原样返回。
 */
internal fun applyFileToSettings(
    settings: Settings,
    repository: AgentConfigRepository,
    path: String,
): Settings = when {
    path == AgentConfigPaths.PROVIDERS_FILE ->
        AgentConfigImporter.applyProviders(settings, repository.root)
    path == AgentConfigPaths.MCP_FILE ->
        AgentConfigImporter.applyMcpServers(settings, repository.root)
    path.startsWith("${AgentConfigPaths.ASSISTANTS_DIR}/") -> {
        val assistantId = path.substringAfterLast('/').removeSuffix(".json")
        AgentConfigImporter.applyAssistants(settings, repository.root, onlyAssistantId = assistantId)
    }
    else -> settings
}
