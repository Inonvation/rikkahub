package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.rikkahub.R

/**
 * `mcp_call` 专用 UI 渲染器。
 *
 * 动态调度后工具名固定为 `mcp_call`，但真实调用的 MCP 工具在参数 `server` + `tool` 里。
 * 这里在折叠气泡标题里还原成 `mcp__{server}__{tool}`，与原先「每个 MCP 工具一个 function schema」
 * 的观感一致，用户能一眼看到正在调用哪个外部 MCP 工具。
 */
object McpCallToolUI : ToolUIRenderer {
    override val toolName: String = "mcp_call"

    private fun server(context: ToolUIContext): String? =
        context.arguments.jsonObjectOrNull?.getStringContent("server")

    private fun tool(context: ToolUIContext): String? =
        context.arguments.jsonObjectOrNull?.getStringContent("tool")

    @Composable
    override fun title(context: ToolUIContext): String {
        val server = server(context)
        val tool = tool(context)
        return if (!server.isNullOrBlank() && !tool.isNullOrBlank()) {
            "mcp__${server}__${tool}"
        } else {
            stringResource(R.string.chat_message_tool_call_generic, context.tool.toolName)
        }
    }
}
