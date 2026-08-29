package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

/**
 * 抽屉落定速度收敛契约：松手速度带入弹簧时，朝目标方向的速度按 ω·剩余距离 封顶
 * （临界阻尼无超调条件），背离目标的速度原样保留，已到目标时速度清零。
 */
class DrawerSettleVelocityTest {

    private val omega = sqrt(400f) // Spring.StiffnessMediumLow

    @Test
    fun `toward-target velocity within cap passes through`() {
        // 距目标 0.7，无超调上限 = 0.7 * 20 = 14，2.5 远低于上限
        assertEquals(2.5f, capSettleVelocity(2.5f, current = 0.3f, target = 1f, omega = omega), 1e-6f)
        assertEquals(-2.5f, capSettleVelocity(-2.5f, current = 0.3f, target = 0f, omega = omega), 1e-6f)
    }

    @Test
    fun `toward-target velocity beyond cap is clamped to omega times remaining`() {
        // 距目标 0.1，上限 = 2；快速甩动的 9 会被收敛到 2，保证不越过终点再折返
        assertEquals(2f, capSettleVelocity(9f, current = 0.9f, target = 1f, omega = omega), 1e-6f)
        assertEquals(-2f, capSettleVelocity(-9f, current = 0.1f, target = 0f, omega = omega), 1e-6f)
    }

    @Test
    fun `away-from-target velocity is untouched`() {
        // 目标 1（打开），但速度向左（背离目标）：弹簧先减速再折返，不会越过目标，无需收敛
        assertEquals(-3f, capSettleVelocity(-3f, current = 0.4f, target = 1f, omega = omega), 1e-6f)
        assertEquals(3f, capSettleVelocity(3f, current = 0.4f, target = 0f, omega = omega), 1e-6f)
    }

    @Test
    fun `already at target zeroes velocity`() {
        // 贴边（已到全开/全关）松手：直接停住，速度清零，避免冲出边界再弹回
        assertEquals(0f, capSettleVelocity(5f, current = 1f, target = 1f, omega = omega), 1e-6f)
        assertEquals(0f, capSettleVelocity(-5f, current = 0f, target = 0f, omega = omega), 1e-6f)
    }

    @Test
    fun `zero velocity stays zero`() {
        assertEquals(0f, capSettleVelocity(0f, current = 0.5f, target = 1f, omega = omega), 1e-6f)
    }
}
