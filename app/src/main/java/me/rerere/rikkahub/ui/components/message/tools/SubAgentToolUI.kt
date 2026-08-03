package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bot

/**
 * 子代理工具渲染器（spawn_subagent）——极简折叠行，但**可点击进详情页**。
 *
 * 设计（用户需求）：
 * - 顶部横幅在全部任务完成后会隐藏，因此母代理聊天气泡里的工具调用位置作为**常驻入口**，
 *   点击直接进入该子代理的详情页（查看执行历史/结果）。
 * - 折叠行仍保持极简：只显示标题，不订阅任务 StateFlow，重组开销趋零。
 *
 * spawn_subagent 的 taskId == tool.toolCallId（派发时对齐），点击可直接定位任务。
 */
object SubAgentToolUI : ToolUIRenderer {
    override val toolName: String = "spawn_subagent"

    /** 点击直接进子代理全屏详情页（不弹 BottomSheet） */
    override val alwaysOpenPreview: Boolean = true

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
