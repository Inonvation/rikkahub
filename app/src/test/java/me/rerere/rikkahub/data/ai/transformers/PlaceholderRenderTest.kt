package me.rerere.rikkahub.data.ai.transformers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 守护占位符渲染核心的缓存稳定性语义：
 * - 稳定值全量替换；波动值（电量等）只在最后一条用户消息位置解析，其余位置删除；
 * - {{key}} / {key} 两种形式、忽略大小写的行为与旧实现一致。
 */
class PlaceholderRenderTest {

    @Test
    fun `stable placeholders resolved everywhere`() {
        val text = "Hello {{char}}, today is {cur_date}."
        assertEquals(
            "Hello 小助手, today is 2026-08-27.",
            renderPlaceholders(
                text,
                stableValues = mapOf("char" to "小助手", "cur_date" to "2026-08-27"),
            ),
        )
    }

    @Test
    fun `matching is case insensitive`() {
        // {{key}} 与 {key} 各命中一次，忽略大小写（与旧实现一致）
        assertEquals(
            "x x",
            renderPlaceholders("{{CHAR}} {Char}", stableValues = mapOf("char" to "x")),
        )
    }

    @Test
    fun `volatile stripped outside tail user message`() {
        val text = "battery={{battery_level}}"
        assertEquals(
            "battery=",
            renderPlaceholders(
                text,
                stableValues = emptyMap(),
                volatileValues = mapOf("battery_level" to "88"),
                includeVolatile = false,
            ),
        )
    }

    @Test
    fun `volatile resolved on tail user message`() {
        val text = "battery={battery_level} {{BATTERY_LEVEL}}"
        assertEquals(
            "battery=88 88",
            renderPlaceholders(
                text,
                stableValues = emptyMap(),
                volatileValues = mapOf("battery_level" to "88"),
                includeVolatile = true,
            ),
        )
    }

    @Test
    fun `history rendering is deterministic across calls`() {
        val text = "{{cur_date}} / {{user}}"
        val values = mapOf("cur_date" to "2026-08-01", "user" to "cy")
        val once = renderPlaceholders(text, stableValues = values)
        // 同一输入多次渲染逐字节一致 —— 历史消息字节永不漂移
        repeat(3) { assertEquals(once, renderPlaceholders(text, stableValues = values)) }
    }
}
