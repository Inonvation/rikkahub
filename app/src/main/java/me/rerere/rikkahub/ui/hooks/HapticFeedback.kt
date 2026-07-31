package me.rerere.rikkahub.ui.hooks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import me.rerere.rikkahub.ui.context.LocalSettings

/**
 * 触感反馈控制器，仅在全局 UI 触感开关开启时执行反馈。
 * perform() 在调用时读取开关状态，而非组合时。
 */
class HapticController(
    private val hapticFeedback: HapticFeedback,
    private val enabled: () -> Boolean,
) {
    fun perform(hapticFeedbackType: HapticFeedbackType) {
        if (enabled()) {
            hapticFeedback.performHapticFeedback(hapticFeedbackType)
        }
    }
}

/**
 * 创建绑定到当前 [LocalSettings] 和 [LocalHapticFeedback] 的 [HapticController]。
 *
 * 返回的控制器受总开关 [me.rerere.rikkahub.data.datastore.DisplaySetting.enableHapticFeedback]
 * 和 [me.rerere.rikkahub.data.datastore.DisplaySetting.enableUiHapticFeedback] 控制。
 * 在 perform 时惰性读取设置值，因此与 staticCompositionLocalOf 配合正常工作。
 */
@Composable
fun rememberHaptic(): HapticController {
    val hapticFeedback = LocalHapticFeedback.current
    val settings = LocalSettings.current
    return remember(hapticFeedback, settings) {
        HapticController(
            hapticFeedback = hapticFeedback,
            enabled = {
                settings.displaySetting.enableHapticFeedback &&
                        settings.displaySetting.enableUiHapticFeedback
            },
        )
    }
}
