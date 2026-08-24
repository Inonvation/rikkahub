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
 * （VibrationEffect.EFFECT_TICK / EFFECT_CLICK / EFFECT_DOUBLE_CLICK）。
 * 这些效果为线性马达设计，比 createOneShot 的原始波形更干脆、质感更好。
 * 预定义效果需要 API 30+；API 26-29 自动回退到平台触感（fallback），保证全版本有反馈。
 * perform() 与三档方法都做了同类型短窗口节流（80ms），避免高频连震。
 */
class HapticController(
    private val hapticFeedback: HapticFeedback,
    private val enabled: () -> Boolean,
    context: Context,
) {
    private val vibrator: Vibrator? by lazy {
        (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.takeIf { it.hasVibrator() }
    }

    // 节流窗口：同一类型触感在短时间内只触发一次，避免列表/滚动时高频“机枪式”连震。
    private val throttleWindowMs = 80L
    private var lastType: String? = null
    private var lastTimeMs: Long = 0L

    private fun shouldThrottle(type: String): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastType == type && now - lastTimeMs < throttleWindowMs) return true
        lastType = type
        lastTimeMs = now
        return false
    }

    fun perform(hapticFeedbackType: HapticFeedbackType) {
        if (!enabled()) return
        if (shouldThrottle("platform:${hapticFeedbackType}")) return
        hapticFeedback.performHapticFeedback(hapticFeedbackType)
    }

    /**
     * 轻档：普通确认性点击（图标按钮、tab 切换、选项选择、折叠展开）。
     */
    fun lightTap() {
        predefined("light", VibrationEffect.EFFECT_TICK, HapticFeedbackType.KeyboardTap)
    }

    /**
     * 中档：主要交互（卡片点击、开关切换、弹窗按钮）。
     */
    fun tap() {
        predefined("tap", VibrationEffect.EFFECT_CLICK, HapticFeedbackType.Confirm)
    }

    /**
     * 重档：破坏性或强反馈操作（删除、测试连接、保存落库）。
     * 使用连续两下的双击（DOUBLE_CLICK），保持干脆的质感，但与轻/中档拉开区分度。
     */
    fun heavyTap() {
        predefined("heavy", VibrationEffect.EFFECT_DOUBLE_CLICK, HapticFeedbackType.LongPress)
    }

    private fun predefined(key: String, effect: Int, fallback: HapticFeedbackType) {
        if (!enabled()) return
        if (shouldThrottle(key)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            vibrator?.vibrate(VibrationEffect.createPredefined(effect))
        } else {
            // API 26-29 无预定义线性马达效果，回退到平台触感，保证全版本都有反馈。
            hapticFeedback.performHapticFeedback(fallback)
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
