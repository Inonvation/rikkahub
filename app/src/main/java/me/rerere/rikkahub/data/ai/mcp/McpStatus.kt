package me.rerere.rikkahub.data.ai.mcp

sealed class McpStatus {
    data object Idle : McpStatus()
    data object Connecting : McpStatus()
    data object Connected : McpStatus()
    data class Reconnecting(val attempt: Int, val maxAttempts: Int) : McpStatus()

    /**
     * 连接/同步出错。
     *
     * @param message 简短摘要，用于列表内联展示
     * @param detail 完整错误信息（含 cause 链与堆栈），用于展开查看与复制；无异常来源时为 null
     */
    data class Error(val message: String, val detail: String? = null) : McpStatus() {
        companion object {
            fun from(throwable: Throwable, fallbackMessage: String? = null): Error {
                val summary = throwable.message?.takeIf { it.isNotBlank() }
                    ?: fallbackMessage
                    ?: throwable.javaClass.simpleName
                return Error(message = summary, detail = throwable.stackTraceToString())
            }
        }
    }

    /** 服务器返回 401，需要用户完成 OAuth 授权。 */
    data object NeedsAuthorization : McpStatus()

    /** 正在进行 OAuth 授权流程（等待浏览器回调 / 交换令牌）。 */
    data object Authorizing : McpStatus()

    /**
     * 配置结构性非法（URL 为空/协议错、名称为空等）。
     *
     * 这类错误属于「配置错误」而非「网络/服务错误」：不允许自动重连、不允许调用工具，
     * 仅提示用户修复；与 [Error]（网络/服务层失败、可有限重试）严格区分。
     */
    data object InvalidConfig : McpStatus()
}
