package me.rerere.ai.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> InputSchema? = { null },
    val systemPrompt: (model: Model, messages: List<UIMessage>) -> String = { _, _ -> "" },
    val needsApproval: (JsonElement) -> Boolean = { false },
    /** 工具执行后是否等待异步后台完成（异步 monitor 模式，v2）。返回 true 时母代理本轮 break，不产出最终回复 */
    val awaitAsyncCompletion: (JsonElement) -> Boolean = { false },
    /**
     * 执行时是否向 args 注入隐藏字段 `__toolCallId`（当前 Tool 的 toolCallId）。
     * 仅对**需要运行时 toolCallId** 的工具开启（如 spawn_subagent 用它对齐 UI 任务 id）。
     * 默认关闭：MCP 等工具会把 args 原样转发给远程服务，注入多余字段会让远程 schema 校验失败
     * （如 tavily 的 pydantic `unexpected_keyword_argument`）。
     */
    val injectToolCallId: Boolean = false,
    val execute: suspend (JsonElement) -> List<UIMessagePart>
)

@Serializable
sealed class InputSchema {
    @Serializable
    @SerialName("object")
    data class Obj(
        val properties: JsonObject,
        val required: List<String>? = null,
    ) : InputSchema()
}
