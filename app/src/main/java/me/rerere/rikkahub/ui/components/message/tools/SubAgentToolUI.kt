package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bot

/**
 * 子代理工具渲染器（spawn_subagent）——极简折叠行，点击打开 JSON 入参/输出窗口。
 *
 * 点击行为：普通工具（alwaysOpenPreview=false）→ 弹 BottomSheet 显示 JSON 入参与结果，
 * 不跳子代理详情页（详情页入口改由"子代理完成气泡"承接，避免与派发气泡重复）。
 */
object SubAgentToolUI : ToolUIRenderer {
    override val toolName: String = "spawn_subagent"

    /** 点击弹 JSON 详情窗口，不跳全屏详情页 */
    override val alwaysOpenPreview: Boolean = false

    /** 不内联摘要（气泡里不出现子代理结果） */
    override fun hasSummary(context: ToolUIContext): Boolean = false

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Bot

    @Composable
    override fun title(context: ToolUIContext): String {
        val agentId = context.arguments.getStringContent("agentId")
        return if (!agentId.isNullOrBlank()) "调用子代理 $agentId" else "调用子代理"
    }
}

/**
 * 子代理完成气泡渲染器：复用 spawn_subagent 工具气泡样式，标题显示「已完成」。
 * 由 ChatService.insertSubAgentCompletionPart 在子代理完成时落库（追加到派发消息框架），
 * 点击经 alwaysOpenPreview 进详情页（toolCallId == taskId）。
 */
object SubAgentCompletedToolUI : ToolUIRenderer {
    override val toolName: String = "spawn_subagent_completed"

    /** 点击直接进子代理全屏详情页（不弹 BottomSheet） */
    override val alwaysOpenPreview: Boolean = true

    /** 不内联摘要 */
    override fun hasSummary(context: ToolUIContext): Boolean = false

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Bot

    @Composable
    override fun title(context: ToolUIContext): String {
        val agentId = context.arguments.getStringContent("agentId")
        val status = context.arguments.getStringContent("status")?.let {
            when (it) {
                "succeeded" -> "成功"
                "failed" -> "失败"
                "timeout" -> "超时"
                "token_limit" -> "预算耗尽"
                "cancelled" -> "已取消"
                else -> it
            }
        } ?: ""
        return if (!agentId.isNullOrBlank()) {
            "子代理 $agentId 已完成${if (status.isNotBlank()) "（$status）" else ""}"
        } else {
            "子代理已完成"
        }
    }
}
