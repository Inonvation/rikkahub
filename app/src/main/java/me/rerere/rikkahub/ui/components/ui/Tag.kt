package me.rerere.rikkahub.ui.components.ui

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.theme.extendColors

enum class TagType {
    DEFAULT,
    SUCCESS,
    ERROR,
    WARNING,
    INFO
}

@Composable
fun Tag(
    modifier: Modifier = Modifier,
    type: TagType = TagType.DEFAULT,
    onClick: (() -> Unit)? = null,
    children: @Composable RowScope.() -> Unit
) {
    val background = when (type) { /* ... 保持不变 ... */ }
    val textColor = when (type) { /* ... 保持不变 ... */ }
    
    val hapticFeedback = LocalHapticFeedback.current  // ← 新增

    ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(color = textColor)) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(50))
                .background(background)
                .then(
    if (onClick != null) {
        Modifier.clickable {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        }
    } else {
        Modifier
    }
)
                .padding(horizontal = 6.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            children()
        }
    }
}

@Composable
@Preview(showBackground = true)
private fun TagPreview() {
    Column(
        modifier = Modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Tag(type = TagType.SUCCESS) {
            Text("测试")
        }
        Tag(type = TagType.ERROR) {
            Text("测试")
        }
        Tag(type = TagType.WARNING) {
            Text("测试")
        }
        Tag(type = TagType.INFO) {
            Text("测试")
        }
        Tag(type = TagType.DEFAULT) {
            Text("测试")
        }
    }
}
