package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * boundToolOutput / headTailText：工具输出有界重编码。
 * 核心回归：shell 类输出（含 stdout/stderr 大字段）不得再被压到 500 字符断崖。
 */
class BoundToolOutputTest {

    private val json = Json

    @Test
    fun `short text is not truncated`() {
        assertNull(headTailText("hello"))
    }

    @Test
    fun `long text keeps head and tail with omitted marker`() {
        val headChars = SHELL_STREAM_HEAD_CHARS
        val tailChars = SHELL_STREAM_TAIL_CHARS
        val omitted = 4096
        // 构造 20K 输出: 可辨认的头尾用于断言
        val text = "H".repeat(headChars) + "M".repeat(omitted) + "T".repeat(tailChars)
        val out = headTailText(text) ?: error("expected truncation")
        // 去掉头尾后只剩省略标记
        assertEquals(
            "\n…[middle $omitted chars omitted]…",
            out.removePrefix("H".repeat(headChars)).removeSuffix("T".repeat(tailChars)),
        )
        assertEquals(headChars + tailChars + "\n…[middle $omitted chars omitted]…".length, out.length)
    }

    @Test
    fun `shell stdout is bounded with head plus tail not 500 chars`() {
        val stdout = "S".repeat(40 * 1024)
        val bounded = boundToolOutput(
            json.parseToJsonElement("""{"exitCode":0,"stdout":"$stdout","stderr":"","timedOut":false}""")
        )
        val out = bounded.jsonObject["stdout"]!!.jsonPrimitive.content
        assertTrue("stdout should keep head+tail, got ${out.length} chars", out.length > 500)
        assertTrue(out.startsWith("S".repeat(8192)))
        assertTrue(out.endsWith("S".repeat(8192)))
        assertTrue(out.contains("[middle"))
        // 其余字段保持原样
        assertEquals("0", bounded.jsonObject["exitCode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `non-shell long string keeps 500 char budget`() {
        val bounded = boundToolOutput(
            json.parseToJsonElement("""{"text":"${"X".repeat(600)}"}""")
        )
        val out = bounded.jsonObject["text"]!!.jsonPrimitive.content
        assertEquals(500 + "…[截断]".length, out.length)
    }

    @Test
    fun `short shell streams are unchanged`() {
        val bounded = boundToolOutput(
            json.parseToJsonElement("""{"exitCode":0,"stdout":"ok","stderr":""}""")
        )
        assertEquals("ok", bounded.jsonObject["stdout"]!!.jsonPrimitive.content)
    }

    @Test
    fun `stderr is also head-tailed`() {
        val stderr = "E".repeat(20 * 1024)
        val bounded = boundToolOutput(
            json.parseToJsonElement("""{"stdout":"","stderr":"$stderr"}""")
        )
        val out = bounded.jsonObject["stderr"]!!.jsonPrimitive.content
        assertTrue(out.length > 500)
        assertTrue(out.startsWith("E".repeat(8192)))
        assertTrue(out.endsWith("E".repeat(8192)))
    }

    @Test
    fun `top-level arrays keep the first 6 elements`() {
        val bounded = boundToolOutput(
            json.parseToJsonElement("""["a","b","c","d","e","f","g"]""")
        )
        val arr = bounded.toString()
        assertTrue(arr.contains("\"a\""))
        assertTrue(!arr.contains("\"g\""))
    }

    @Test
    fun `truncateSafely on shell json uses head tail not 500`() {
        val stdout = "S".repeat(20 * 1024)
        val text = """{"stdout":"$stdout","exitCode":0}"""
        val out = truncateSafely(text, json)
        // 整段 > 3000 字符触发截断，但 stdout 字段保留 head+tail 而非 500
        assertTrue(out.contains("[middle ${20 * 1024 - SHELL_STREAM_HEAD_CHARS - SHELL_STREAM_TAIL_CHARS} chars omitted]"))
        assertTrue(out.length > 500)
        val parsed = json.parseToJsonElement(out)
        assertEquals("0", parsed.jsonObject["exitCode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `truncateSafely on non json falls back to plain cut`() {
        // 注意：裸串会被 kotlinx 解析成非字符串字面量，必须用结构上非法、保证解析失败的输入
        val text = "A".repeat(4000) + " {"
        val out = truncateSafely(text, json)
        assertEquals(3000 + "\n...[truncated]".length, out.length)
        assertEquals("A".repeat(3000) + "\n...[truncated]", out)
        assertTrue(out.endsWith("[truncated]"))
    }

    @Test
    fun `full_output_path and truncated survive shell bounding`() {
        val stdout = "S".repeat(20 * 1024)
        val bounded = boundToolOutput(
            json.parseToJsonElement(
                """{"stdout":"$stdout","truncated":true,"full_output_path":"/tool_outputs/abc.txt"}"""
            )
        )
        assertEquals("/tool_outputs/abc.txt", bounded.jsonObject["full_output_path"]!!.jsonPrimitive.contentOrNull)
        assertEquals("true", bounded.jsonObject["truncated"]!!.jsonPrimitive.content)
    }
}