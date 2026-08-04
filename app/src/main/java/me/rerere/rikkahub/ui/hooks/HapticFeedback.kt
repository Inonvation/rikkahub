package me.rerere.rikkahub.ui.hooks

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import me.rerere.rikkahub.ui.context.LocalSettings

/**
 * 触感反馈控制器，仅在全局 UI 触感开关开启时执行反馈。
 * perform() 在调用时读取开关状态，而非组合时。
 *
 * 三档方法（lightTap / tap / heavyTap）使用系统预定义的高保真触感效果
 * （VibrationEffect.EFFECT_TICK / EFFECT_CLICK / EFFECT_HEAVY_CLICK）。
 * 这些效果为线性马达设计，比 createOneShot 的原始波形更干脆、质感更好。
 * 预定义效果需要 API 30+；API 26-29 不提供触感（静默）。
 */
class HapticController(
    private val hapticFeedback: HapticFeedback,
    private val enabled: () -> Boolean,
    context: Context,
) {
    private val vibrator: Vibrator? by lazy {
        (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.takeIf { it.hasVibrator() }
    }

    fun perform(hapticFeedbackType: HapticFeedbackType) {
        if (enabled()) {
            hapticFeedback.performHapticFeedback(hapticFeedbackType)
        }
    }

    /**
     * 轻档：普通确认性点击（图标按钮、tab 切换、选项选择、折叠展开）。
     */
    fun lightTap() {
        predefined(VibrationEffect.EFFECT_TICK)
    }

    /**
     * 中档：主要交互（卡片点击、开关切换、弹窗按钮）。
     */
    fun tap() {
        predefined(VibrationEffect.EFFECT_CLICK)
    }

    /**
     * 重档：破坏性或强反馈操作（删除、测试连接、保存落库）。
     * 使用连续两下的双击（DOUBLE_CLICK），保持干脆的质感，但与轻/中档拉开区分度。
     */
    fun heavyTap() {
        predefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
    }

    private fun predefined(effect: Int) {
        if (!enabled()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val v = vibrator ?: return
        v.vibrate(VibrationEffect.createPredefined(effect))
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
    val context = LocalContext.current
    return remember(hapticFeedback, settings, context) {
        HapticController(
            hapticFeedback = hapticFeedback,
            enabled = {
                settings.displaySetting.enableHapticFeedback &&
                        settings.displaySetting.enableUiHapticFeedback
            },
            context = context,
        )
    }
}
