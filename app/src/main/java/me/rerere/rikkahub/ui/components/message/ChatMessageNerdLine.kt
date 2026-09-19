package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.datetime.toJavaLocalDateTime
import me.rerere.ai.ui.UIMessage
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.hugeicons.stroke.Zap
import me.rerere.rikkahub.data.ai.GenerationLiveStats
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.formatNumber
import me.rerere.rikkahub.utils.toFixed
import java.time.Duration

/**
 * 显示消息的技术统计信息（token 使用量 / 速率 / 耗时）。
 *
 * 生成中传入 [liveStats] 走实时数据（与完成后同一渲染，保证前后一致）；
 * 完成后从 [message.usage] / [UIMessage.streamDurationMillis] 读取。
 *
 * token 口径（对齐 Codex last/total 双字段）：
 * - 输入 + cached = **单步口径**（最后一步 provider 实测，= 当前上下文占用），读
 *   [UIMessage.contextPromptTokens] / [UIMessage.contextCachedTokens]；旧数据缺失时
 *   回退 usage 累计值（显示偏大，诚实降级）。
 * - 输出 = 回合账单累计（usage.completionTokens），多步工具循环每步输出都计入。
 * - 速率一律为累计平均：completionTokens ÷ 纯流式时长（不含工具执行）。
 */
@Composable
fun ChatMessageNerdLine(
    message: UIMessage,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f),
    /** 非 null = 生成中：token/速率/耗时改从实时统计读取，渲染样式与完成后完全一致 */
    liveStats: GenerationLiveStats? = null,
) {
    val settings = LocalSettings.current.displaySetting

    ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = color)) {
        CompositionLocalProvider(LocalContentColor provides color) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = modifier.padding(horizontal = 4.dp),
            ) {
                val live = liveStats
                val usage = message.usage
                val hasContent = if (live != null) {
                    live.promptTokens > 0 || live.completionTokens > 0
                } else {
                    settings.showTokenUsage && usage != null
                }
                if (hasContent) {
                    // 完成态：输入/cached 用单步锚点（旧数据回退 usage 累计）；输出用账单累计。
                    // 生成中：liveStats 已是单步输入口径 + 累计输出（见 publishLiveStats），
                    // cached 在本步 usage 到达前不可知（null → 不显示）。
                    val promptTokens = live?.promptTokens
                        ?: message.contextPromptTokens?.takeIf { it > 0 }
                        ?: usage?.promptTokens ?: 0
                    val cachedTokens = when {
                        live != null -> live.cachedTokens ?: 0
                        else -> message.contextCachedTokens?.takeIf { it > 0 }
                            ?: usage?.cachedTokens ?: 0
                    }
                    val completionTokens = live?.completionTokens ?: usage?.completionTokens ?: 0
                    // 速率时长：生成中用累计纯流式时长；完成后优先 streamDurationMillis，旧数据回退墙钟
                    val durationMillis: Long? = when {
                        live != null -> live.streamMillis.takeIf { it > 0 }
                        message.finishedAt != null -> message.streamDurationMillis
                            ?: Duration.between(
                                message.createdAt.toJavaLocalDateTime(),
                                message.finishedAt!!.toJavaLocalDateTime()
                            ).toMillis()
                        else -> null
                    }

                    // Input tokens
                    StatsItem(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.Upload02,
                                contentDescription = "Input",
                                tint = color,
                                modifier = Modifier.size(12.dp)
                            )
                        },
                        content = {
                            Text(text = "${promptTokens.formatNumber()} tokens")
                            // Cached tokens（生成中 usage 未到齐时不可知，完成后显示）
                            if (cachedTokens > 0) {
                                Text(text = "(${cachedTokens.formatNumber()} cached)")
                            }
                        }
                    )
                    // Output tokens
                    StatsItem(
                        icon = {
                            Icon(
                                imageVector = HugeIcons.Download04,
                                contentDescription = "Output",
                                modifier = Modifier.size(12.dp)
                            )
                        },
                        content = {
                            Text(text = "${completionTokens.formatNumber()} tokens")
                        }
                    )
                    // TPS：累计平均 = 总输出 token ÷ 纯流式时长（不含工具执行/审批等待）
                    if (durationMillis != null && durationMillis > 0) {
                        val tps = completionTokens.toFloat() / durationMillis * 1000
                        val seconds = (durationMillis / 1000f).toFixed(1)
                        StatsItem(
                            icon = {
                                Icon(
                                    imageVector = HugeIcons.Zap,
                                    contentDescription = "Speed",
                                    modifier = Modifier.size(12.dp)
                                )
                            },
                            content = {
                                Text(text = "${tps.toFixed(1)} tok/s")
                            }
                        )

                        StatsItem(
                            icon = {
                                Icon(
                                    imageVector = HugeIcons.Clock02,
                                    contentDescription = "Duration",
                                    modifier = Modifier.size(12.dp)
                                )
                            },
                            content = {
                                Text(text = "${seconds}s")
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatsItem(
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        icon()
        content()
    }
}
