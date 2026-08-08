package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.ai.mcp.serverUrl
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

private const val TRANSPORT_SSE = "sse"
private const val TRANSPORT_STREAMABLE_HTTP = "streamable_http"

internal fun mcpTransportOf(config: McpServerConfig): String = when (config) {
    is McpServerConfig.SseTransportServer -> TRANSPORT_SSE
    is McpServerConfig.StreamableHTTPServer -> TRANSPORT_STREAMABLE_HTTP
}

internal fun mcpHeaderKeys(config: McpServerConfig): String {
    return config.commonOptions.headers.joinToString(", ") { it.first }
}

internal fun mcpParseHeaders(element: JsonObject?): List<Pair<String, String>> {
    if (element == null) return emptyList()
    return element.mapNotNull { (key, value) ->
        val headerValue = value.jsonPrimitive.contentOrNull ?: return@mapNotNull null
        key to headerValue
    }
}

internal fun mcpParseToolNames(element: JsonElement?): List<String>? {
    val jsonArray = element as? kotlinx.serialization.json.JsonArray ?: return null
    return jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
}

internal fun mcpUpdateUrl(config: McpServerConfig, url: String?): McpServerConfig {
    if (url == null) return config
    return when (config) {
        is McpServerConfig.SseTransportServer -> config.copy(url = url)
        is McpServerConfig.StreamableHTTPServer -> config.copy(url = url)
    }
}

/**
 * MCP 服务器管理工具组。
 *
 * 仿照 [me.rerere.rikkahub.data.ai.tools.createSkillTools] 的模式，把 MCP 配置的
 * 增删改查与连接测试暴露给 AI。所有写操作统一通过 [SettingsStore.update] 修改
 * `settings.mcpServers`，[McpManager] 内部监听该配置流变化自动 reconcile 连接。
 */
fun createMcpManagerTools(
    mcpManager: McpManager,
    settingsStore: SettingsStore,
    assistant: Assistant,
    isEnabled: Boolean,
): List<Tool> {
    if (!isEnabled) return emptyList()

    fun allServers(): List<McpServerConfig> = settingsStore.settingsFlow.value.mcpServers

    fun findServer(id: String): McpServerConfig {
        return allServers().firstOrNull { it.id.toString() == id }
            ?: error("MCP server '$id' not found. Use `mcp_admin_list` to see available server ids.")
    }

    fun statusText(config: McpServerConfig): String {
        val status = mcpManager.syncingStatus.value[config.id] ?: McpStatus.Idle
        return when (status) {
            is McpStatus.Idle -> "idle"
            is McpStatus.Connecting -> "connecting"
            is McpStatus.Connected -> "connected"
            is McpStatus.Reconnecting -> "reconnecting (${status.attempt}/${status.maxAttempts})"
            is McpStatus.Error -> "error: ${status.message}"
            is McpStatus.NeedsAuthorization -> "needs OAuth authorization"
            is McpStatus.Authorizing -> "authorizing"
        }
    }

    fun describeServer(config: McpServerConfig): String = buildString {
        val enabledForAssistant = config.id in assistant.mcpServers
        appendLine("- id: ${config.id}")
        appendLine("  name: ${config.commonOptions.name}")
        appendLine("  type: ${mcpTransportOf(config)}")
        appendLine("  url: ${config.serverUrl}")
        appendLine("  enable: ${config.commonOptions.enable}")
        appendLine("  status: ${statusText(config)}")
        appendLine("  enabled for current assistant: $enabledForAssistant")
        appendLine("  header keys: ${mcpHeaderKeys(config)}")
        val enabledTools = config.commonOptions.tools.filter { it.enable }
        if (enabledTools.isEmpty()) {
            appendLine("  tools: (none enabled)")
        } else {
            appendLine("  tools:")
            enabledTools.forEach { tool ->
                appendLine("    - ${tool.name}: ${tool.description ?: "(no description)"}")
            }
        }
    }

    fun listSummary(): String {
        val servers = allServers()
        if (servers.isEmpty()) return "No MCP servers configured."
        return buildString {
            appendLine("Configured MCP servers:")
            servers.forEach { appendLine(describeServer(it)) }
        }
    }

    val systemPrompt: (me.rerere.ai.provider.Model, List<me.rerere.ai.ui.UIMessage>) -> String = { _, _ ->
        buildString {
            appendLine("**MCP Server Management**")
            appendLine("You can manage MCP (Model Context Protocol) server configurations using the `mcp_admin_*` tools. Use these tools to list, inspect, add, update, delete, and test MCP servers. Use `mcp_admin_assistant_set_enabled` to control which configured MCP servers are enabled for the current assistant.")
            appendLine("<configured_mcp_servers>")
            allServers().forEach { server ->
                appendLine("  <server id=\"${server.id}\">${server.commonOptions.name} (${mcpTransportOf(server)}, status: ${statusText(server)})</server>")
            }
            appendLine("</configured_mcp_servers>")
        }
    }

    return listOf(
        Tool(
            name = "mcp_admin_list",
            description = """
                List all configured MCP servers with their id, name, transport type, url, enabled flag, connection status, and enabled tools.
                Call this first to discover available server ids before using other mcp_admin tools.
            """.trimIndent(),
            systemPrompt = systemPrompt,
            parameters = {
                InputSchema.Obj(properties = buildJsonObject {})
            },
            execute = {
                listOf(UIMessagePart.Text(listSummary()))
            },
        ),
        Tool(
            name = "mcp_admin_get",
            description = "Get detailed configuration of a single MCP server, including its tools and connection status.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", "string")
                            put("description", "The id (UUID) of the MCP server")
                        })
                    },
                    required = listOf("id"),
                )
            },
            execute = { args ->
                val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                listOf(UIMessagePart.Text(describeServer(findServer(id))))
            },
        ),
        Tool(
            name = "mcp_admin_add",
            description = """
                Add a new MCP server. Supported transport types: "sse" (SSE transport) and "streamable_http" (Streamable HTTP transport).
                Headers are passed as a JSON object of key-value pairs and are persisted with the config.
                The server will be connected automatically after creation.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("type", buildJsonObject {
                            put("type", "string")
                            put("description", "Transport type: \"sse\" or \"streamable_http\"")
                            put("enum", buildJsonArray {
                                add(JsonPrimitive("sse"))
                                add(JsonPrimitive("streamable_http"))
                            })
                        })
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "Server name (letters and digits only)")
                        })
                        put("url", buildJsonObject {
                            put("type", "string")
                            put("description", "Server URL (e.g. https://example.com/mcp)")
                        })
                        put("headers", buildJsonObject {
                            put("type", "object")
                            put("description", "Optional custom headers as a JSON object of key-value pairs, e.g. {\"Authorization\":\"Bearer token\"}")
                        })
                        put("enable", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Whether the server is enabled. Defaults to true")
                        })
                    },
                    required = listOf("type", "name", "url"),
                )
            },
            execute = { args ->
                val json = args.jsonObject
                val transport = json["type"]?.jsonPrimitive?.contentOrNull ?: error("type is required")
                val name = json["name"]?.jsonPrimitive?.contentOrNull ?: error("name is required")
                val url = json["url"]?.jsonPrimitive?.contentOrNull ?: error("url is required")
                val enable = json["enable"]?.jsonPrimitive?.booleanOrNull ?: true
                if (transport != TRANSPORT_SSE && transport != TRANSPORT_STREAMABLE_HTTP) {
                    error("type must be \"sse\" or \"streamable_http\"")
                }
                if (name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) {
                    error("name must be non-empty and contain only letters and digits")
                }
                val headers = mcpParseHeaders(json["headers"]?.jsonObject)

                val config: McpServerConfig = when (transport) {
                    TRANSPORT_SSE -> McpServerConfig.SseTransportServer(
                        id = Uuid.random(),
                        commonOptions = McpCommonOptions(enable = enable, name = name, headers = headers),
                        url = url,
                    )
                    else -> McpServerConfig.StreamableHTTPServer(
                        id = Uuid.random(),
                        commonOptions = McpCommonOptions(enable = enable, name = name, headers = headers),
                        url = url,
                    )
                }
                settingsStore.update { settings ->
                    settings.copy(mcpServers = settings.mcpServers + config)
                }
                mcpManager.markAiModified(config.id)
                listOf(UIMessagePart.Text("MCP server '${config.commonOptions.name}' added with id=${config.id}."))
            },
        ),
        Tool(
            name = "mcp_admin_update",
            description = """
                Update an existing MCP server. Only the provided fields are changed.
                To enable/disable specific tools, pass enableTools / disableTools with tool names (list them first with mcp_admin_get).
                Headers are replaced entirely when provided.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", "string")
                            put("description", "The id (UUID) of the MCP server")
                        })
                        put("name", buildJsonObject {
                            put("type", "string")
                            put("description", "New server name (letters and digits only)")
                        })
                        put("url", buildJsonObject {
                            put("type", "string")
                            put("description", "New server URL")
                        })
                        put("headers", buildJsonObject {
                            put("type", "object")
                            put("description", "Replace custom headers entirely with this JSON object of key-value pairs")
                        })
                        put("enable", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Enable or disable the server")
                        })
                        put("enableTools", buildJsonObject {
                            put("type", "array")
                            put("description", "Tool names to enable")
                            put("items", buildJsonObject { put("type", "string") })
                        })
                        put("disableTools", buildJsonObject {
                            put("type", "array")
                            put("description", "Tool names to disable")
                            put("items", buildJsonObject { put("type", "string") })
                        })
                    },
                    required = listOf("id"),
                )
            },
            execute = { args ->
                val json = args.jsonObject
                val id = json["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                val server = findServer(id)
                val name = json["name"]?.jsonPrimitive?.contentOrNull
                if (name != null && (name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' })) {
                    error("name must be non-empty and contain only letters and digits")
                }
                val url = json["url"]?.jsonPrimitive?.contentOrNull
                val enable = json["enable"]?.jsonPrimitive?.booleanOrNull
                val headersProvided = json["headers"] != null
                val newHeaders = if (headersProvided) mcpParseHeaders(json["headers"]?.jsonObject) else null
                val enableTools = mcpParseToolNames(json["enableTools"])
                val disableTools = mcpParseToolNames(json["disableTools"])

                val common = server.commonOptions
                val newCommon = common.copy(
                    name = name ?: common.name,
                    enable = enable ?: common.enable,
                    headers = newHeaders ?: common.headers,
                    tools = if (enableTools != null || disableTools != null) {
                        common.tools.map { tool ->
                            when {
                                enableTools?.contains(tool.name) == true -> tool.copy(enable = true)
                                disableTools?.contains(tool.name) == true -> tool.copy(enable = false)
                                else -> tool
                            }
                        }
                    } else {
                        common.tools
                    },
                )

                val updated = mcpUpdateUrl(server, url).clone(commonOptions = newCommon)
                settingsStore.update { settings ->
                    settings.copy(
                        mcpServers = settings.mcpServers.map {
                            if (it.id == server.id) updated else it
                        }
                    )
                }
                mcpManager.markAiModified(server.id)
                listOf(UIMessagePart.Text("MCP server '${updated.commonOptions.name}' (id=${server.id}) updated."))
            },
        ),
        Tool(
            name = "mcp_admin_assistant_set_enabled",
            description = """
                Enable or disable a configured MCP server for the current assistant.
                When enabled, the server's tools become available to this assistant (next message onwards).
                Use `mcp_admin_list` first to discover available server ids.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", "string")
                            put("description", "The id (UUID) of the MCP server")
                        })
                        put("enabled", buildJsonObject {
                            put("type", "boolean")
                            put("description", "true to enable this MCP server for the current assistant, false to disable it")
                        })
                    },
                    required = listOf("id", "enabled"),
                )
            },
            execute = { args ->
                val json = args.jsonObject
                val id = json["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                val enabled = json["enabled"]?.jsonPrimitive?.booleanOrNull ?: error("enabled must be a boolean")
                val server = findServer(id)
                val newSet = if (enabled) assistant.mcpServers + server.id else assistant.mcpServers - server.id
                settingsStore.updateAssistantMcpServers(assistant.id, newSet)
                listOf(
                    UIMessagePart.Text(
                        "MCP server '${server.commonOptions.name}' (id=${server.id}) " +
                            "${if (enabled) "enabled" else "disabled"} for the current assistant."
                    )
                )
            },
        ),
        Tool(
            name = "mcp_admin_delete",
            description = "Delete an MCP server configuration. This also removes it from all assistants' enabled MCP server sets.",
            needsApproval = { true },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", "string")
                            put("description", "The id (UUID) of the MCP server to delete")
                        })
                    },
                    required = listOf("id"),
                )
            },
            execute = { args ->
                val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                val server = findServer(id)
                settingsStore.update { settings ->
                    settings.copy(
                        mcpServers = settings.mcpServers.filterNot { it.id == server.id },
                        assistants = settings.assistants.map { assistantConfig ->
                            if (server.id in assistantConfig.mcpServers) {
                                assistantConfig.copy(mcpServers = assistantConfig.mcpServers - server.id)
                            } else {
                                assistantConfig
                            }
                        }
                    )
                }
                listOf(UIMessagePart.Text("MCP server '${server.commonOptions.name}' (id=${server.id}) deleted."))
            },
        ),
        Tool(
            name = "mcp_admin_test",
            description = "Test the connection to an MCP server and return its connection status. If the server requires OAuth, this reports that the user must complete authorization in the MCP settings page.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", "string")
                            put("description", "The id (UUID) of the MCP server")
                        })
                    },
                    required = listOf("id"),
                )
            },
            execute = { args ->
                val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
                val server = findServer(id)
                mcpManager.addClient(server)
                val status = mcpManager.getStatus(server).first()
                val text = when (status) {
                    is McpStatus.Connected ->
                        "MCP server '${server.commonOptions.name}' (id=${server.id}) is connected."
                    is McpStatus.NeedsAuthorization ->
                        "MCP server '${server.commonOptions.name}' (id=${server.id}) requires OAuth authorization. " +
                            "The user must complete authorization in the app's MCP settings page."
                    is McpStatus.Error ->
                        "MCP server '${server.commonOptions.name}' (id=${server.id}) connection failed: ${status.message}"
                    is McpStatus.Reconnecting ->
                        "MCP server '${server.commonOptions.name}' (id=${server.id}) is reconnecting (${status.attempt}/${status.maxAttempts})."
                    is McpStatus.Connecting ->
                        "MCP server '${server.commonOptions.name}' (id=${server.id}) is connecting..."
                    is McpStatus.Authorizing ->
                        "MCP server '${server.commonOptions.name}' (id=${server.id}) is authorizing..."
                    is McpStatus.Idle ->
                        "MCP server '${server.commonOptions.name}' (id=${server.id}) is idle."
                }
                listOf(UIMessagePart.Text(text))
            },
        ),
    )
}
