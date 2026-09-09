package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.rikkahub.ui.modifier.onClick

/** 「其他」组（未分类助手）的折叠 key */
const val ASSISTANT_GROUP_OTHER = "other"

/** 分类组折叠 key 前缀，完整 key 为 "$ASSISTANT_GROUP_CATEGORY_PREFIX<分类id>" */
const val ASSISTANT_GROUP_CATEGORY_PREFIX = "cat:"

/**
 * 折叠分组组头：箭头 + 组名 + 数量。箭头随展开/收起平滑旋转。
 *
 * 展开/收起与「可用模型」分组浏览同构，但箭头带旋转动画；折叠区间的
 * 平滑动画由 [AssistantCollapsibleGroup] 统一提供。
 */
@Composable
fun AssistantGroupHeader(
    title: String,
    count: Int,
    collapsed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (collapsed) 0f else 180f,
        label = "assistantGroupHeaderArrow",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onClick(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = HugeIcons.ArrowDown01,
            contentDescription = null,
            modifier = Modifier
                .size(18.dp)
                .rotate(rotation),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 可折叠分组容器：组头 + 展开区间。区间用 AnimatedVisibility 做高度方向上的
 * expand/shrink 手风琴动画，保证折叠/展开平滑。
 *
 * 供助手设置页分类组、助手选择弹层分组列表复用，保持两处交互手感一致。
 *
 * @param collapsed 当前是否折叠（true = 收起，仅显示组头）
 * @param groupContent 展开后组内的内容（成员卡片等），由调用方按需排列
 */
@Composable
fun AssistantCollapsibleGroup(
    title: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    groupContent: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        AssistantGroupHeader(
            title = title,
            count = count,
            collapsed = collapsed,
            onClick = onToggle,
        )
        AnimatedVisibility(
            visible = !collapsed,
            enter = expandVertically(animationSpec = tween(220)) + fadeIn(tween(150)),
            exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(120)),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                groupContent()
            }
        }
    }
}
