package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 平板适配模式：由 AppRoutes 根据设置提供（默认 false）。
 * 深层组件（输入框、内容气泡等）据此在平板横屏下放宽限宽，界面本身仍是普通滑动抽屉式。
 */
val LocalTabletAdaptation = staticCompositionLocalOf { false }
