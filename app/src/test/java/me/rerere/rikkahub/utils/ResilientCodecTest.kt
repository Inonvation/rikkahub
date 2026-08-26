package me.rerere.rikkahub.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * P0 兜底逻辑的单测：验证 [decodeOrDefault] / [parseUuidOrNull] 在坏输入下回退默认值，
 * 而不是抛异常打崩整个 settingsFlow。
 */
class ResilientCodecTest {

    @Test
    fun `decodeOrDefault returns decoded value for valid json`() {
        val result = decodeOrDefault<List<String>>("""["a","b"]""", listOf("x"))
        assertEquals(listOf("a", "b"), result)
    }

    @Test
    fun `decodeOrDefault returns default for malformed json`() {
        val result = decodeOrDefault<List<String>>("{not json", listOf("x"))
        assertEquals(listOf("x"), result)
    }

    @Test
    fun `decodeOrDefault returns default for wrong element type`() {
        // 数组里元素类型不符（数字对 String），解码抛异常 → 回退默认
        val result = decodeOrDefault<List<String>>("""[1,2,3]""", listOf("x"))
        assertEquals(listOf("x"), result)
    }

    @Test
    fun `decodeOrDefault returns default for null and blank`() {
        val fallback = listOf("x")
        assertEquals(fallback, decodeOrDefault<List<String>>(null, fallback))
        assertEquals(fallback, decodeOrDefault<List<String>>("", fallback))
        assertEquals(fallback, decodeOrDefault<List<String>>("   ", fallback))
    }

    @Test
    fun `decodeOrDefault for maps falls back on malformed json`() {
        val result = decodeOrDefault<Map<String, Int>>("{bad", mapOf("a" to 1))
        assertEquals(mapOf("a" to 1), result)
    }

    @Test
    fun `decodeListOrDefault keeps all items when array valid`() {
        val result = decodeListOrDefault<String>("""["a","b"]""", listOf("x"))
        assertEquals(listOf("a", "b"), result)
    }

    @Test
    fun `decodeListOrDefault drops a single bad item but keeps the rest`() {
        // 第三个元素是数字，无法解码为 String → 回收该条，保留 a/b
        val result = decodeListOrDefault<String>("""["a","b",42]""", listOf("x"))
        assertEquals(listOf("a", "b"), result)
    }

    @Test
    fun `decodeListOrDefault returns default when top-level is not an array`() {
        val fallback = listOf("x")
        assertEquals(fallback, decodeListOrDefault<String>("""{"a":1}""", fallback))
        assertEquals(fallback, decodeListOrDefault<String>("{bad", fallback))
        assertEquals(fallback, decodeListOrDefault<String>(null, fallback))
        assertEquals(fallback, decodeListOrDefault<String>("", fallback))
    }

    @Test
    fun `parseUuidOrNull returns null for invalid input`() {
        assertNull(parseUuidOrNull(null))
        assertNull(parseUuidOrNull(""))
        assertNull(parseUuidOrNull("not-a-uuid"))
        assertNull(parseUuidOrNull("00000000-0000-0000-0000-zzzzzzzzzzzz"))
    }

    @Test
    fun `parseUuidOrNull parses valid uuid`() {
        val uuid = Uuid.parse("00000000-0000-0000-0000-000000000000")
        assertEquals(uuid, parseUuidOrNull("00000000-0000-0000-0000-000000000000"))
    }
}
