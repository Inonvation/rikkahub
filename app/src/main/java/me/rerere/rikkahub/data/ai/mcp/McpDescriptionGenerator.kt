package me.rerere.rikkahub.data.ai.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.RetryPolicy
import me.rerere.ai.util.retryWithPolicy
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider

/** 生成的描述最长字符数（单行）。 */
internal const val MCP_DESCRIPTION_MAX_LENGTH = 200

/** 后台轻量调用（描述提炼）的重试策略：小预算、短延迟，与 OCR/标题生成保持一致。 */
private val MCP_DESCRIPTION_RETRY_POLICY = RetryPolicy(maxRetries = 2, initialDelayMs = 400, maxDelayMs = 5_000)

/** 生成 MCP 服务器描述的系统提示词（纯函数，便于单测）。 */
internal fun buildMcpDescriptionSystemPrompt(): String = """
    You are an expert at writing concise descriptions for MCP (Model Context Protocol) servers.
    The description is shown to an AI assistant (to help it decide when to use this server) and to the user in the settings list.
    Rules:
    - Summarize what this server can do based ONLY on the listed tools.
    - Write 1-2 short sentences in a single line, no more than $MCP_DESCRIPTION_MAX_LENGTH characters.
    - Do not invent capabilities that are not in the tool list.
    - Do not mention "MCP server" or the server name; describe capabilities and typical use scenarios instead.
    - Use the same language as the majority of the tool descriptions; if mixed or unclear, use Simplified Chinese.
""".trimIndent()

/** 组装 user 消息：服务器名 + 全部工具（名 + 描述），供模型据此写摘要（纯函数，便于单测）。 */
internal fun buildMcpDescriptionUserPrompt(serverName: String, tools: List<McpTool>): String = buildString {
    appendLine("MCP server name: ${serverName.ifBlank { "(unnamed)" }}")
    appendLine("Tools:")
    if (tools.isEmpty()) {
        appendLine("(none)")
    } else {
        tools.forEach { tool ->
            val desc = tool.description?.trim().orEmpty()
            if (desc.isNotBlank()) {
                appendLine("- ${tool.name}: $desc")
            } else {
                appendLine("- ${tool.name}")
            }
        }
    }
    appendLine()
    appendLine("Write the description now.")
}

/**
 * 清洗模型返回：压成单行、去掉首尾多余标点/引号、截断到最大长度。
 * 返回空串表示模型输出无效（调用方应报错）。
 */
internal fun cleanMcpDescription(text: String): String =
    text.replace(Regex("\\s+"), " ")
        .trim()
        .trim('"', '\'', '`', '，', ',', '。', '.', '：', ':')
        .take(MCP_DESCRIPTION_MAX_LENGTH)

/**
 * 用「默认快速模型」为 MCP 服务器提炼一句话描述。
 *
 * 输入为该服务器已发现的工具列表（连接成功后由 [McpManager] 同步进配置）；
 * 工具为空时直接抛错——描述必须基于真实工具，不能凭空捏造。
 * 失败抛出带用户可读信息的异常，由 UI 层 toast 提示。
 */
class McpDescriptionGenerator(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
) {
    suspend fun generateDescription(serverName: String, tools: List<McpTool>): String =
        withContext(Dispatchers.IO) {
            if (tools.isEmpty()) {
                error("该服务器还没有可用工具，无法生成描述")
            }
            val settings = settingsStore.settingsFlow.value
            val model = settings.findModelById(settings.fastModelId, fallback = settings.chatModelId)
                ?: error("未配置快速模型，请在模型设置中选择")
            val provider = model.findProvider(settings.providers)
                ?: error("快速模型对应的提供商不可用")
            val providerHandler = providerManager.getProviderByType(provider)

            val result = retryWithPolicy(MCP_DESCRIPTION_RETRY_POLICY) {
                providerHandler.generateText(
                    providerSetting = provider,
                    messages = listOf(
                        UIMessage.system(buildMcpDescriptionSystemPrompt()),
                        UIMessage.user(buildMcpDescriptionUserPrompt(serverName, tools)),
                    ),
                    params = TextGenerationParams(
                        model = model,
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                    ),
                )
            }
            cleanMcpDescription(result.message.toText()).ifBlank {
                error("模型没有返回有效的描述，请重试")
            }
        }
}
