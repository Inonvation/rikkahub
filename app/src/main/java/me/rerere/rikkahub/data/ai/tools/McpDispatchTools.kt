package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * MCP 使用侧动态调度工具。
 *
 * 取代「每个 MCP 工具注册一个 function schema」的全量注入方式：外部 server 的工具数量、
 * description 长度都不可控，是系统提示词膨胀的主要来源。这里只暴露两个通用工具：
 *  - [MCP_LIST_NAME]：列出当前 assistant 可用的 MCP 工具（server / tool / 摘要 / 参数名）。
 *  - [MCP_CALL_NAME]：按 server + tool 调用。
 *
 * 纯格式化 / 查找 / 参数解析逻辑抽成 internal 纯函数，便于单测；工厂只负责把 `McpManager`
 * 的查询与调用闭包接到 [Tool] 上。
 */

const val MCP_LIST_NAME: String = "mcp_list"
const val MCP_CALL_NAME: String = "mcp_call"

/** MCP 工具名的合法性：MCP 协议工具名一般为 ASCII 字母/数字/下划线/短横线。 */
internal fun isValidMcpName(value: String): Boolean =
    value.isNotBlank() && value.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-'
    }

/** MCP server 名宽松校验：只要是可读文本（非空、无控制字符）即可，允许中文/空格/点/短横线。 */
internal fun isValidMcpServerName(value: String): Boolean =
    value.isNotBlank() && value.none { it.isISOControl() }

internal sealed class McpResolveResult {
    data class Found(val tool: Triple<Uuid, String, McpTool>) : McpResolveResult()
    data object NotFound : McpResolveResult()
    data class Ambiguous(val serverNames: List<String>) : McpResolveResult()
}

/**
 * 解析 MCP 调用目标。优先按 server id（Uuid 字符串、大小写不敏感）精确匹配，
 * 否则按 server 名（大小写不敏感）匹配；同名 server 且工具名也相同 → 歧义。
 */
internal fun resolveMcpTool(
    tools: List<Triple<Uuid, String, McpTool>>,
    server: String,
    toolName: String,
): McpResolveResult {
    val byId = tools.filter { it.first.toString().equals(server, ignoreCase = true) }
    val source = if (byId.isNotEmpty()) byId else tools.filter { it.second.equals(server, ignoreCase = true) }
    val matches = source.filter { it.third.name == toolName }
    if (matches.isEmpty()) return McpResolveResult.NotFound
    val distinctServerIds = matches.map { it.first }.distinct()
    if (distinctServerIds.size > 1) {
        return McpResolveResult.Ambiguous(matches.map { it.second }.distinct())
    }
    return McpResolveResult.Found(matches.first())
}

/** 把一波 MCP 工具整理成模型可读的紧凑列表。 */
internal fun formatMcpToolList(
    tools: List<Triple<Uuid, String, McpTool>>,
    serverFilter: String?,
): String {
    val filtered = tools.filter {
        serverFilter.isNullOrBlank() || it.second.equals(serverFilter, ignoreCase = true)
    }
    if (filtered.isEmpty()) {
        return "No MCP tools available for the current assistant" +
            (if (serverFilter.isNullOrBlank()) "." else " on server '$serverFilter'.") +
            " Make sure the server is enabled, connected, and assigned to this assistant."
    }
    // 按 server 真实身份（id）分组：同名 server 各自带 id，避免模型把某台 server 的工具
    // 误挂到「第一个同名服务器」的 id 上，导致 mcp_call 用错 id 而 Not Found。
    val byServer: Map<Uuid, List<Triple<Uuid, String, McpTool>>> = filtered.groupBy { it.first }
    // 每个显示名实际对应的 server id 集合，用于判断是否重名。
    val nameToIds: Map<String, List<Uuid>> = filtered.groupBy { it.second.ifBlank { "(unnamed)" } }
        .mapValues { (_, group) -> group.map { it.first }.distinct() }
    return buildString {
        appendLine("Available MCP tools (grouped by server):")
        for ((id, serverTools) in byServer) {
            val displayServer = serverTools.first().second.ifBlank { "(unnamed)" }
            val collision = (nameToIds[displayServer]?.size ?: 1) > 1
            appendLine("server=$displayServer" + if (collision) " [id=$id]" else "")
            for ((_, _, tool) in serverTools) {
                val summary = tool.description?.trim().orEmpty()
                val params = (tool.inputSchema as? InputSchema.Obj)
                    ?.properties?.keys?.take(8)?.joinToString(",").orEmpty()
                val desc = if (summary.isNotBlank()) summary.take(120) else "(no description)"
                append("- ${tool.name} — $desc")
                if (params.isNotBlank()) append(" [params: $params]")
                appendLine()
            }
        }
    }
}

/** 列出当前 assistant 可用的 MCP 工具。 */
fun createMcpListTool(assistant: Assistant, mcpManager: McpManager): Tool =
    Tool(
        name = MCP_LIST_NAME,
        description = "List MCP tools available to the current assistant, grouped by server. Returns for each tool: the tool name, a short summary, and its parameter names. Call this BEFORE invoking an MCP tool with `mcp_call`. Accepts an optional `server` filter.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("server", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional server name to filter by")
                    })
                },
                required = emptyList(),
            )
        },
        systemPrompt = { _, _ ->
            "MCP: when the user's request likely needs an external tool/integration, call `mcp_list` to see available MCP tools, then `mcp_call` to invoke one. Never guess server/tool names from memory."
        },
        execute = { args ->
            val server = args.jsonObject["server"]?.jsonPrimitive?.contentOrNull
            listOf(UIMessagePart.Text(formatMcpToolList(mcpManager.getAllAvailableTools(assistant), server)))
        },
    )

/** 按 server + tool 调用一个 MCP 工具。 */
fun createMcpCallTool(
    assistant: Assistant,
    mcpManager: McpManager,
    forceNoApproval: Boolean = false,
): Tool {
    fun resolveForApproval(args: JsonElement): Boolean =
        if (forceNoApproval) false else
            runCatching {
                val server = args.jsonObject["server"]?.jsonPrimitive?.contentOrNull ?: return true
                val toolName = args.jsonObject["tool"]?.jsonPrimitive?.contentOrNull ?: return true
                when (val result = resolveMcpTool(mcpManager.getAllAvailableTools(assistant), server, toolName)) {
                    is McpResolveResult.Found -> result.tool.third.needsApproval
                    is McpResolveResult.NotFound -> true
                    is McpResolveResult.Ambiguous -> true
                }
            }.getOrDefault(true)

    return Tool(
        name = MCP_CALL_NAME,
        description = "Invoke an MCP tool by server name (or id) + tool name. Use `mcp_list` first to see available tools and their parameter names. Pass `arguments` as an object matching those params.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("server", buildJsonObject {
                        put("type", "string")
                        put("description", "MCP server name or id (as shown by mcp_list)")
                    })
                    put("tool", buildJsonObject {
                        put("type", "string")
                        put("description", "MCP tool name (as shown by mcp_list)")
                    })
                    put("arguments", buildJsonObject {
                        put("type", "object")
                        put("description", "Argument object for the MCP tool, matching the listed params.")
                    })
                },
                required = listOf("server", "tool"),
            )
        },
        needsApproval = { resolveForApproval(it) },
        execute = { args ->
            val obj = args.jsonObject
            val server = obj["server"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: error("server is required")
            val toolName = obj["tool"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: error("tool is required")
            if (!isValidMcpServerName(server)) error("invalid MCP server name: '$server'")
            if (!isValidMcpName(toolName)) error("invalid MCP tool name: '$toolName'")

            val arguments = parseMcpArguments(obj["arguments"])
            val all = mcpManager.getAllAvailableTools(assistant)
            when (val result = resolveMcpTool(all, server, toolName)) {
                is McpResolveResult.NotFound ->
                    error("MCP tool '$toolName' not found on server '$server'. Use `mcp_list` to see available tools.")
                is McpResolveResult.Ambiguous ->
                    error("Multiple MCP servers are named '${result.serverNames.joinToString("/")}'. Rename one or use its full id (shown by mcp_list).")
                is McpResolveResult.Found ->
                    mcpManager.callTool(result.tool.first, result.tool.third.name, arguments)
            }
        },
    )
}

/** 解析 `mcp_call` 的 `arguments`：对象直用，字符串按 JSON 解析，其它/缺失给空对象。 */
internal fun parseMcpArguments(raw: JsonElement?): JsonObject = when (raw) {
    null -> JsonObject(emptyMap())
    is JsonObject -> raw
    is JsonPrimitive -> runCatching {
        Json.parseToJsonElement(raw.content).jsonObject
    }.getOrDefault(JsonObject(emptyMap()))
    else -> JsonObject(emptyMap())
}
