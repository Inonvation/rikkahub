package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.theme.LocalChatFontFamily
import me.rerere.rikkahub.ui.theme.rememberChatFontFamily
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * 共享的"推理头部"展示元素，供悬浮吸顶条与真实思考头部复用。
 *
 * 把"标题 | 思考了n秒"、"折叠/展开箭头"的渲染规则与触感抽到这里，
 * 保证冻结悬浮状态与普通显示状态的样式、缩进、交互一致，切换时不会水平错位。
 */

/** 主文案：加载中显示轮换趣味文案（标题保留在旁），否则标题优先、无标题显示"思考了n秒" */
@Composable
internal fun ReasoningHeaderLabel(
    title: String?,
    duration: Duration,
    loading: Boolean,
    chatFontFamily: FontFamily,
) {
    // 加载中（reasoning 流式进行中）→ 主文案轮换趣味文案；有自定义标题时保留在旁
    if (loading) {
        RotatingThinkingLabel(
            enabled = true,
            primaryTitle = title,
            chatFontFamily = chatFontFamily,
        )
        return
    }
    val style = MaterialTheme.typography.titleSmall.copy(fontFamily = chatFontFamily)
    val color = MaterialTheme.colorScheme.secondary
    if (title != null) {
        Text(
            text = title,
            style = style,
            color = color,
            modifier = Modifier.shimmer(isLoading = loading),
        )
    } else {
        Text(
            text = stringResource(
                R.string.deep_thinking_seconds,
                duration.toDouble(DurationUnit.SECONDS).toFloat(),
            ),
            style = style,
            color = color,
            modifier = Modifier.shimmer(isLoading = loading),
        )
    }
}

/** 折叠/展开箭头：内容可见且未滚动折叠 → 收起箭头；否则 → 展开箭头 */
@Composable
internal fun ReasoningFoldArrow(
    contentVisible: Boolean,
    folded: Boolean,
    modifier: Modifier = Modifier,
) {
    val imageVector = if (contentVisible && !folded) {
        HugeIcons.ArrowUp01
    } else {
        HugeIcons.ArrowDown01
    }
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        modifier = modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 整行可点的推理头部行：图标 + 主文案 + (可选 extra) + 折叠/展开箭头。
 * 悬浮吸顶条整体用它渲染；真实思考头部由 [ChainOfThoughtScope] 提供行布局，
 * 仅复用 [ReasoningHeaderLabel] 与 [ReasoningFoldArrow] 保持视觉/交互一致。
 */
@Composable
internal fun ReasoningHeaderRow(
    icon: @Composable () -> Unit = {
        Icon(
            imageVector = HugeIcons.Idea01,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.secondary,
        )
    },
    title: String?,
    duration: Duration,
    loading: Boolean,
    extra: (@Composable () -> Unit)? = null,
    contentVisible: Boolean,
    folded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hapticController = rememberHaptic()
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable {
                hapticController.perform(HapticFeedbackType.KeyboardTap)
                onClick()
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.width(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Box(modifier = Modifier.weight(1f)) {
            ReasoningHeaderLabel(
                title = title,
                duration = duration,
                loading = loading,
                chatFontFamily = LocalChatFontFamily.current
                    ?: rememberChatFontFamily(LocalSettings.current.displaySetting),
            )
        }
        if (extra != null) {
            extra()
        }
        ReasoningFoldArrow(
            contentVisible = contentVisible,
            folded = folded,
        )
    }
}
