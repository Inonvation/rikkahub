package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Bot
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.rikkahub.ui.components.message.StatsItem
import me.rerere.rikkahub.utils.formatNumber

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

    /**
     * 完成气泡下方的一行统计：token 用量（输入/输出/缓存）+ 执行时长。
     * 复用主聊天区 NerdLine 的 [StatsItem]（矢量图标 + labelSmall），视觉统一；
     * 数据在 insertSubAgentCompletionPart 落库时随气泡持久化（重启后仍在）；
     * 旧气泡无这些字段则返回 null，不显示空统计行。
     */
    @Composable
    override fun subtitle(context: ToolUIContext): (@Composable () -> Unit)? {
        val args = context.arguments
        val prompt = args.getJsonInt("promptTokens")
        val completion = args.getJsonInt("completionTokens")
        val cached = args.getJsonInt("cachedTokens")
        val durationMs = args.getJsonLong("durationMs")

        val hasUsage = (prompt ?: 0) > 0 || (completion ?: 0) > 0 || (cached ?: 0) > 0
        val hasDuration = durationMs != null && durationMs > 0
        if (!hasUsage && !hasDuration) return null

        val promptText = (prompt ?: 0).formatNumber()
        val completionText = (completion ?: 0).formatNumber()
        val cachedText = (cached ?: 0).formatNumber()
        val durationText = durationMs?.let { formatSubAgentDuration(it) }

        return {
            val color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f)
            ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = color)) {
                CompositionLocalProvider(LocalContentColor provides color) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (hasUsage) {
                            StatsItem(
                                icon = {
                                    Icon(
                                        imageVector = HugeIcons.Upload02,
                                        contentDescription = "输入 tokens",
                                        modifier = Modifier.size(12.dp),
                                    )
                                },
                                content = {
                                    Text("$promptText tokens")
                                    if ((cached ?: 0) > 0) {
                                        Text("($cachedText cached)")
                                    }
                                },
                            )
                            StatsItem(
                                icon = {
                                    Icon(
                                        imageVector = HugeIcons.Download04,
                                        contentDescription = "输出 tokens",
                                        modifier = Modifier.size(12.dp),
                                    )
                                },
                                content = {
                                    Text("$completionText tokens")
                                },
                            )
                        }
                        if (hasDuration) {
                            StatsItem(
                                icon = {
                                    Icon(
                                        imageVector = HugeIcons.Clock02,
                                        contentDescription = "执行时长",
                                        modifier = Modifier.size(12.dp),
                                    )
                                },
                                content = { Text(durationText.orEmpty()) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 子代理执行时长格式化：<60s 显示秒，<1h 显示分+秒，其余小时+分 */
private fun formatSubAgentDuration(ms: Long): String {
    val totalSec = ms / 1000
    return when {
        totalSec < 60 -> "${totalSec}s"
        totalSec < 3600 -> "${totalSec / 60}m ${totalSec % 60}s"
        else -> "${totalSec / 3600}h ${(totalSec % 3600) / 60}m"
    }
}
