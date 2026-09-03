package me.rerere.rikkahub.ui.components.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.material3.Material3

/**
 * 聊天悬浮层（顶栏/输入栏）共用的毛玻璃样式：Material3 tint + 12dp 模糊半径。
 * 单点定义，调整观感只改这里；[progressive] 可选传入渐隐遮罩（如顶栏底边淡出）。
 */
@Composable
fun barHazeBlurStyle(progressive: HazeProgressive? = null): HazeBlurStyle = HazeBlurStyle.Material3 {
    blurRadius(12.dp)
    if (progressive != null) progressive(progressive)
}
