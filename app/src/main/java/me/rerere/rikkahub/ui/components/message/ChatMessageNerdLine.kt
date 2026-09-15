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
 * 速率一律为累计平均：completionTokens ÷ 纯流式时长（不含工具执行）。
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
                    val promptTokens = live?.promptTokens ?: usage?.promptTokens ?: 0
                    val completionTokens = live?.completionTokens ?: usage?.completionTokens ?: 0
                    val cachedTokens = live?.let { 0 } ?: usage?.cachedTokens ?: 0
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
