package me.rerere.ai.provider.providers

import me.rerere.ai.ui.UIMessagePart

/**
 * UI 专用"假工具"：应用注入用于展示，**不是模型真实调用**的工具。
 * 序列化回传模型时必须排除——否则模型看到历史消息里出现一个从没调用过的工具名
 * （如 spawn_subagent_completed / user_guidance），会误以为它是可用工具而反复调用等待，
 * 甚至把一批已结束的子代理结果反复读进上下文。
 * 这些工具的真实内容（子代理结果摘要 / 引导文本）已通过 resumeContext 作为最后一条
 * USER 消息单独注入模型，过滤掉气泡不影响模型获取信息。
 */
internal val UI_ONLY_TOOL_NAMES = setOf(
    "spawn_subagent_completed",
    "user_guidance",
)

/**
 * 消息 parts 按工具边界分组的结果
 * - Content: 普通内容（Text、Image、Reasoning 等）
 * - Tools: 连续的已执行工具
 */
internal sealed class PartGroup {
    data class Content(val parts: List<UIMessagePart>) : PartGroup()
    data class Tools(val tools: List<UIMessagePart.Tool>) : PartGroup()
}

/**
 * 将消息 parts 按工具边界分组
 *
 * 例如 [Text1, Tool1, Tool2, Text2, Tool3] 会分组为:
 * - Content([Text1])
 * - Tools([Tool1, Tool2])
 * - Content([Text2])
 * - Tools([Tool3])
 *
 * 这样可以确保 tool_use/functionCall 后面紧跟 tool_result/functionResponse
 */
internal fun groupPartsByToolBoundary(parts: List<UIMessagePart>): List<PartGroup> {
    val groups = mutableListOf<PartGroup>()
    val currentContent = mutableListOf<UIMessagePart>()
    val currentTools = mutableListOf<UIMessagePart.Tool>()

    fun flushContent() {
        if (currentContent.isNotEmpty()) {
            groups.add(PartGroup.Content(currentContent.toList()))
            currentContent.clear()
        }
    }

    fun flushTools() {
        if (currentTools.isNotEmpty()) {
            groups.add(PartGroup.Tools(currentTools.toList()))
            currentTools.clear()
        }
    }

    for (part in parts) {
        // UI 假工具（spawn_subagent_completed / user_guidance）：完全跳过，
        // 不进 Tools 也不进 Content——模型不应看到这些它从未调用的"工具"。
        if (part is UIMessagePart.Tool && part.toolName in UI_ONLY_TOOL_NAMES) {
            continue
        }
        if (part is UIMessagePart.Tool && part.isExecuted) {
            flushContent()
            currentTools.add(part)
        } else {
            flushTools()
            currentContent.add(part)
        }
    }

    flushContent()
    flushTools()
    return groups
}
