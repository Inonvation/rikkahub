package me.rerere.rikkahub.ui.components.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskUserQuestionParserTest {

    private val json = Json

    // ---- 常规解析：四种 selection_type + optional 字段 ----

    @Test
    fun `parse full arguments with all selection types`() {
        val args = json.parseToJsonElement("""
            {"questions":[
                {"id":"q1","question":"哪个方案?","selection_type":"single","options":["A","B"],"required":true},
                {"id":"q2","question":"选模块","selection_type":"multi","options":["a","b","c"]},
                {"id":"q3","question":"继续吗","selection_type":"confirmation"},
                {"id":"q4","question":"补充说明","selection_type":"text","placeholder":"...","required":false}
            ]}
        """.trimIndent())
        val questions = parseAskUserQuestions(args)
        assertEquals(4, questions.size)
        assertEquals("single", questions[0].selectionType)
        assertEquals(listOf("A", "B"), questions[0].options)
        assertTrue(questions[0].required)
        assertEquals("multi", questions[1].selectionType)
        assertEquals("confirmation", questions[2].selectionType)
        assertEquals("text", questions[3].selectionType)
        assertEquals("...", questions[3].placeholder)
        assertEquals(false, questions[3].required)
    }

    // ---- selection_type 大小写/别名漂移归一 ----

    @Test
    fun `selection type aliases and case drift normalize`() {
        val args = json.parseToJsonElement("""
            {"questions":[
                {"id":"a","question":"1","selection_type":"Multi"},
                {"id":"b","question":"2","selection_type":"MULTIPLE"},
                {"id":"c","question":"3","selection_type":"multi_select"},
                {"id":"d","question":"4","selection_type":"Single_Choice"},
                {"id":"e","question":"5","selection_type":"YES_NO"},
                {"id":"f","question":"6","selection_type":"fancy_unknown"},
                {"id":"g","question":"7"}
            ]}
        """.trimIndent())
        val questions = parseAskUserQuestions(args)
        assertEquals(
            listOf("multi", "multi", "multi", "single", "confirmation", "text", "text"),
            questions.map { it.selectionType },
        )
    }

    // ---- id 缺失/空串按序兜底，避免共享 "" 键互相覆盖 ----

    @Test
    fun `missing or blank id falls back to index-based id`() {
        val args = json.parseToJsonElement("""
            {"questions":[
                {"question":"A"},
                {"id":"","question":"B"}
            ]}
        """.trimIndent())
        val questions = parseAskUserQuestions(args)
        assertEquals("q1", questions[0].id)
        assertEquals("q2", questions[1].id)
    }

    // ---- 单条目畸形只影响自身，不拖垮其余问题 ----

    @Test
    fun `malformed entries are dropped without killing the rest`() {
        val args = json.parseToJsonElement("""
            {"questions":[
                "not-an-object",
                {"id":"ok","question":"正常","options":["x",{"bad":1},null]},
                {"id":"typed","question":"字段类型漂移","selection_type":{"nested":true},"required":"false"}
            ]}
        """.trimIndent())
        val questions = parseAskUserQuestions(args)
        assertEquals(2, questions.size)
        assertEquals("ok", questions[0].id)
        assertEquals(listOf("x"), questions[0].options)
        assertEquals("typed", questions[1].id)
        assertEquals("text", questions[1].selectionType)
        assertEquals(false, questions[1].required)
    }

    // ---- 结构性畸形返回空列表 ----

    @Test
    fun `non array or missing questions yields empty list`() {
        assertEquals(emptyList<AskUserQuestion>(), parseAskUserQuestions(JsonObject(emptyMap())))
        assertEquals(emptyList<AskUserQuestion>(), parseAskUserQuestions(json.parseToJsonElement("""{"questions":"nope"}""")))
        assertEquals(emptyList<AskUserQuestion>(), parseAskUserQuestions(json.parseToJsonElement("""[1,2,3]""")))
    }

    // ---- required 双型解析：boolean 与字符串都按语义解析，显式 false=选填 ----
    // 根因：schema 声明 required 为 boolean，模型既会传 true/false 也会传 "true"/"false"，
    // 只认字符串会让 boolean false（选填）被误判为必答，用户被强制作答/跳过本可留空的题。

    @Test
    fun `required accepts boolean and string forms`() {
        val args = json.parseToJsonElement("""
            {"questions":[
                {"id":"a","question":"1","required":false},
                {"id":"b","question":"2","required":true},
                {"id":"c","question":"3","required":"false"},
                {"id":"d","question":"4","required":"true"},
                {"id":"e","question":"5","required":"no"},
                {"id":"f","question":"6","required":"0"},
                {"id":"g","question":"7","required":"yes"},
                {"id":"h","question":"8","required":""},
                {"id":"i","question":"9"}
            ]}
        """.trimIndent())
        val questions = parseAskUserQuestions(args)
        assertEquals(
            listOf(false, true, false, true, false, false, true, true, true),
            questions.map { it.required },
        )
    }

    // ---- 重复 id 兜底：给后续冲突项追加序号，避免同一 answers 键互相覆盖 ----
    // 根因：id 唯一性此前无保障，重复 id 会让后一题的答案/跳过标记覆盖前一题，
    // 弹窗里表现为「题被自动跳过/答案串题」。

    @Test
    fun `duplicate explicit ids get numbered suffixes`() {
        val args = json.parseToJsonElement("""
            {"questions":[
                {"id":"dup","question":"A"},
                {"id":"dup","question":"B"},
                {"id":"c","question":"C"},
                {"id":"dup","question":"D"},
                {"id":"","question":"E"}
            ]}
        """.trimIndent())
        val questions = parseAskUserQuestions(args)
        assertEquals(listOf("dup", "dup_2", "c", "dup_3", "q5"), questions.map { it.id })
    }

    // ---- 空白/畸形 id 与重复兜底互不干扰 ----

    @Test
    fun `blank id fallback never collides with existing ids`() {
        val args = json.parseToJsonElement("""
            {"questions":[
                {"id":"q2","question":"explicit q2"},
                {"id":"","question":"falls back"}
            ]}
        """.trimIndent())
        val questions = parseAskUserQuestions(args)
        assertEquals(listOf("q2", "q2_2"), questions.map { it.id })
    }

    // ---- JsonNull 字段降级而非抛异常 ----

    @Test
    fun `json null fields degrade instead of throwing`() {
        val args = json.parseToJsonElement(
            """{"questions":[{"id":"n","question":"Q","selection_type":null,"options":null,"required":null}]}""",
        )
        val questions = parseAskUserQuestions(args)
        assertEquals(1, questions.size)
        assertEquals("text", questions[0].selectionType)
        assertTrue(questions[0].options.isEmpty())
        assertTrue(questions[0].required)
    }
}
