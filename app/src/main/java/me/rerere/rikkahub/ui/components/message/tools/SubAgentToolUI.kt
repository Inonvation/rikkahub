package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bot
import me.rerere.hugeicons.stroke.Clock02

/**
 * 子代理工具渲染器（spawn_subagent / await_subagent）——**极简折叠行**。
 *
 * 设计意图（用户需求：子代理不出现在聊天气泡里）：
 * - 只显示一行纯标题（"调用子代理 X" / "等待子代理结果"），**剥离一切状态/结果/摘要/流光**。
 * - 子代理的实时状态只出现在顶部横幅（SubAgentRunningBanner）+ 详情页/面板页。
 * - 不订阅任务 StateFlow，重组开销趋零；图标静态，无 shimmer。
 *
 * 主聊天仍能看到"母代理调用了子代理"这个环节（链式思考不断裂），但不展示结果。
 */
object SubAgentToolUI : ToolUIRenderer {
    override val toolName: String = "spawn_subagent"

    /** 不自动展开，不强制进详情页（状态在顶部横幅展示） */
    override val alwaysOpenPreview: Boolean = false

    /** 不内联摘要（气泡里不出现子代理结果） */
    override fun hasSummary(context: ToolUIContext): Boolean = false

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Bot

    @Composable
    override fun title(context: ToolUIContext): String {
        val agentId = context.arguments.getStringContent("agentId")
        return if (!agentId.isNullOrBlank()) {
            "调用子代理 $agentId"
        } else {
            "调用子代理"
        }
    }
}

/** await_subagent 的极简渲染器：纯标题"等待子代理结果" */
object AwaitSubAgentToolUI : ToolUIRenderer {
    override val toolName: String = "await_subagent"

    override val alwaysOpenPreview: Boolean = false

    override fun hasSummary(context: ToolUIContext): Boolean = false

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Clock02

    @Composable
    override fun title(context: ToolUIContext): String = "等待子代理结果"
}
