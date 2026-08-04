package me.rerere.rikkahub.ui.hooks

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import kotlin.math.roundToInt

@Composable
fun rememberAvatarShape(loading: Boolean): Shape {
    // 仅在加载中创建无限旋转动画：列表里每个可见头像都会调用本函数，
    // 若无条件创建，加载完成（loading=false）后动画仍常驻运行（每 3s 转一圈），
    // 造成多余的每帧刷新与功耗。加载完成后直接返回静态圆形。
    return if (loading) {
        val infiniteTransition = rememberInfiniteTransition()
        val rotateAngle = infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 3000,
                    easing = LinearEasing
                ),
            )
        )
        MaterialShapes.Cookie6Sided.toShape(rotateAngle.value.roundToInt())
    } else {
        CircleShape
    }
}
