package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 拍照搜题输出协议切分的容错测试：覆盖标记完整 / 缺闭标记 / 完全无标记三类情况。
 */
class SolveOutputParserTest {
    @Test
    fun `complete markers split into process and final`() {
        val text = "## 解题过程\n\n第一步...\n\n<final_answer>\n## 作答\n\n1. ...\n</final_answer>"
        val (process, final) = splitSolveOutput(text)
        assertEquals("## 解题过程\n\n第一步...", process)
        assertEquals("## 作答\n\n1. ...", final)
    }

    @Test
    fun `no markers keeps whole text as process`() {
        val text = "只有解题过程，没有标记。"
        val (process, final) = splitSolveOutput(text)
        assertEquals(text, process)
        assertEquals("", final)
    }

    @Test
    fun `missing close marker treats rest as final`() {
        val text = "过程。\n\n<final_answer>\n精炼作答（流式截断，无闭标记）"
        val (process, final) = splitSolveOutput(text)
        assertEquals("过程。", process)
        assertEquals("精炼作答（流式截断，无闭标记）", final)
    }

    @Test
    fun `blank text returns blank pair`() {
        val (process, final) = splitSolveOutput("   ")
        assertEquals("", process)
        assertEquals("", final)
    }

    @Test
    fun `marker inside final block does not break first-split`() {
        // 模型重复标记时取第一个开标记，闭标记取其后第一个，避免内容错位
        val text = "A <final_answer> B </final_answer> <final_answer> C </final_answer>"
        val (process, final) = splitSolveOutput(text)
        assertEquals("A", process)
        assertEquals("B", final)
    }

    @Test
    fun `empty final block returns empty final`() {
        val text = "过程\n<final_answer></final_answer>"
        val (process, final) = splitSolveOutput(text)
        assertEquals("过程", process)
        assertEquals("", final)
    }
}
