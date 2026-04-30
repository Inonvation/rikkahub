package me.rerere.rikkahub.ui.components.ui

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

@Composable
fun ToggleSurface(
    checked: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(50),
    onClick: () -> Unit = {},
    content: @Composable () -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current  // ← 新增
    val contentColor =
        if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress) // ← 新增
            onClick()
        },
        color = Color.Transparent,
        contentColor = contentColor,
        modifier = modifier,
        shape = shape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            content()
        }
    }
}