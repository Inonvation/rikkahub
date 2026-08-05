package me.rerere.rikkahub.utils

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** parseToolOutputText：工具输出文本 → JsonElement 的纯解析（非法/空文本回退空对象） */
class ToolParseCacheTest {

    @Test
    fun `valid json parses to object`() {
        val json = parseToolOutputText("""{"stdout":"hi","addedFiles":["a.txt"]}""")
        assertEquals("hi", json.jsonObject["stdout"]?.jsonPrimitive?.content)
    }

    @Test
    fun `invalid json falls back to empty object`() {
        val json = parseToolOutputText("not json at all")
        assertTrue(json.jsonObject.isEmpty())
    }

    @Test
    fun `empty text falls back to empty object`() {
        assertTrue(parseToolOutputText("").jsonObject.isEmpty())
    }
}
