package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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
    fun `wide budget keeps longer strings for no-shell main chat`() {
        // 无 shell 主聊天没有 /tool_outputs 找回通道：4000 字符预算内必须原样保留，
        // 600 字符的笔记正文/知识库分块不得被 500 字符断崖砍掉（能力性回归防锚点）
        val text = "X".repeat(600)
        val bounded = boundToolOutput(
            json.parseToJsonElement("""{"text":"$text"}"""),
            BoundBudget.WIDE,
        )
        assertEquals(text, bounded.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `wide budget keeps more array items`() {
        // 50 条命中数组不得被 6 项预算静默截断（trusted_folder_search 默认 maxResults=50）
        val items = (1..30).joinToString(",") { "\"item$it\"" }
        val bounded = boundToolOutput(
            json.parseToJsonElement("""{"matches":[$items]}"""),
            BoundBudget.WIDE,
        )
        assertEquals(30, bounded.jsonObject["matches"]!!.jsonArray.size)
    }

    @Test
    fun `wide budget still caps oversized content`() {
        // 分档不等于放任：超 4000 字符仍截断，保证不把 32K 大输出整段塞回上下文
        val bounded = boundToolOutput(
            json.parseToJsonElement("""{"text":"${"Y".repeat(5000)}"}"""),
            BoundBudget.WIDE,
        )
        val out = bounded.jsonObject["text"]!!.jsonPrimitive.content
        assertEquals(4000 + "…[截断]".length, out.length)
    }

    @Test
    fun `medium tier fits multi-block results that exceed cap at wide`() {
        // kb_search 场景（10 块 × 5KB）：WIDE 产物 ≈41K 超出 32K 上限，
        // 若一步降 DEFAULT 会砍回 6×500（复现"10 块 → 6 块"）；MEDIUM（2000/20）
        // 保留全部 10 块且产物 ≤ 32K——降档必须逐级收紧而非一步跳底
        val blocks = (1..10).joinToString(",") { "\"${"K".repeat(5000)}\"" }
        val elem = json.parseToJsonElement("""{"blocks":[$blocks]}""")
        val wideProduct = json.encodeToString(boundToolOutput(elem, BoundBudget.WIDE))
        val mediumBounded = boundToolOutput(elem, BoundBudget.MEDIUM)
        assertTrue(
            "WIDE product should exceed 32K cap, got ${wideProduct.length}",
            wideProduct.length > 32 * 1024,
        )
        val mediumProduct = json.encodeToString(mediumBounded)
        assertTrue(
            "MEDIUM product should fit 32K cap, got ${mediumProduct.length}",
            mediumProduct.length <= 32 * 1024,
        )
        assertEquals(10, mediumBounded.jsonObject["blocks"]!!.jsonArray.size)
    }

    @Test
    fun `pick budget uses default for shell sessions`() {
        val huge = """{"content":"${"X".repeat(100_000)}"}"""
        assertEquals(
            BoundBudget.DEFAULT,
            pickBoundedJsonBudget(huge, json, 32 * 1024, hasShellAccess = true),
        )
    }

    @Test
    fun `pick budget uses wide for single oversized string`() {
        // 100KB 单串笔记：WIDE 产物 ≈4K，远低于上限，直接命中首选档
        val huge = """{"content":"${"X".repeat(100_000)}"}"""
        assertEquals(
            BoundBudget.WIDE,
            pickBoundedJsonBudget(huge, json, 32 * 1024, hasShellAccess = false),
        )
    }

    @Test
    fun `pick budget degrades to medium for multi-block oversized results`() {
        // WIDE 产物 ≈41K > 上限 → 降 MEDIUM（≈21K ≤ 上限），不得一步落 DEFAULT
        val blocks = (1..10).joinToString(",") { "\"${"K".repeat(5000)}\"" }
        assertEquals(
            BoundBudget.MEDIUM,
            pickBoundedJsonBudget("""{"blocks":[$blocks]}""", json, 32 * 1024, hasShellAccess = false),
        )
    }

    @Test
    fun `pick budget falls through ladder to default for pathological shapes`() {
        // 200 项 × 2000 字符：WIDE 留 50 项 ≈100K、MEDIUM 留 20 项 ≈40K 都超限 → DEFAULT 兜底
        val items = (1..200).joinToString(",") { "\"${"I".repeat(2000)}\"" }
        assertEquals(
            BoundBudget.DEFAULT,
            pickBoundedJsonBudget("""{"items":[$items]}""", json, 32 * 1024, hasShellAccess = false),
        )
    }

    @Test
    fun `pick budget returns default for non json text`() {
        // 非 JSON 文本：各档试算均失败，返回 DEFAULT；调用方实测产物为 null 后走纯文本截断
        assertEquals(
            BoundBudget.DEFAULT,
            pickBoundedJsonBudget("A".repeat(4000) + " {", json, 32 * 1024, hasShellAccess = false),
        )
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