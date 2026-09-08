package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 拍照搜题输出协议切分的容错测试。
 *
 * 协议 v2（problem_statement + final_answer 三段）容错矩阵：
 * - statement：完整 / 缺闭标记（流式截断）/ 无标记（退化为旧行为）/ 空内容 / 杂散前缀丢弃；
 * - final_answer 沿用旧规则：完整 / 缺闭标记 / 完全无标记。
 */
class SolveOutputParserTest {

    // ---------- final_answer 旧语义回归（协议 v1 行为不可破坏） ----------

    @Test
    fun `complete markers split into process and final`() {
        val text = "## 解题过程\n\n第一步...\n\n<final_answer>\n## 作答\n\n1. ...\n</final_answer>"
        val blocks = splitSolveOutput(text)
        assertEquals("", blocks.statement)
        assertEquals("## 解题过程\n\n第一步...", blocks.process)
        assertEquals("## 作答\n\n1. ...", blocks.finalAnswer)
    }

    @Test
    fun `no markers keeps whole text as process`() {
        val text = "只有解题过程，没有标记。"
        val blocks = splitSolveOutput(text)
        assertEquals("", blocks.statement)
        assertEquals(text, blocks.process)
        assertEquals("", blocks.finalAnswer)
    }

    @Test
    fun `missing close marker treats rest as final`() {
        val text = "过程。\n\n<final_answer>\n精炼作答（流式截断，无闭标记）"
        val blocks = splitSolveOutput(text)
        assertEquals("过程。", blocks.process)
        assertEquals("精炼作答（流式截断，无闭标记）", blocks.finalAnswer)
    }

    @Test
    fun `blank text returns blank blocks`() {
        val blocks = splitSolveOutput("   ")
        assertEquals("", blocks.statement)
        assertEquals("", blocks.process)
        assertEquals("", blocks.finalAnswer)
    }

    @Test
    fun `marker inside final block does not break first-split`() {
        // 模型重复标记时取第一个开标记，闭标记取其后第一个，避免内容错位
        val text = "A <final_answer> B </final_answer> <final_answer> C </final_answer>"
        val blocks = splitSolveOutput(text)
        assertEquals("A", blocks.process)
        assertEquals("B", blocks.finalAnswer)
    }

    @Test
    fun `empty final block returns empty final`() {
        val text = "过程\n<final_answer></final_answer>"
        val blocks = splitSolveOutput(text)
        assertEquals("过程", blocks.process)
        assertEquals("", blocks.finalAnswer)
    }

    // ---------- statement 协议 v2 新语义 ----------

    @Test
    fun `complete statement splits into three blocks`() {
        val text = """
            <problem_statement>
            已知 ${'$'}x^2 + 2x - 3 = 0${'$'}，求 ${'$'}x${'$'}。
            </problem_statement>
            ## 解题过程
            配方法...
            <final_answer>
            ${'$'}x = 1${'$'} 或 ${'$'}x = -3${'$'}
            </final_answer>
        """.trimIndent()
        val blocks = splitSolveOutput(text)
        assertEquals("已知 ${'$'}x^2 + 2x - 3 = 0${'$'}，求 ${'$'}x${'$'}。", blocks.statement)
        assertEquals("## 解题过程\n配方法...", blocks.process)
        assertEquals("${'$'}x = 1${'$'} 或 ${'$'}x = -3${'$'}", blocks.finalAnswer)
    }

    @Test
    fun `statement without close marker keeps rest as statement during streaming`() {
        // 流式中间态：开标签已到、闭标签未到 → 其后全文暂计 statement
        val text = "<problem_statement>\n已知 ${'$'}a${'$'}、${'$'}b${'$'}，求 ${'$'}a+b${'$'}（流式截断，尚无闭标记"
        val blocks = splitSolveOutput(text)
        assertEquals("已知 ${'$'}a${'$'}、${'$'}b${'$'}，求 ${'$'}a+b${'$'}（流式截断，尚无闭标记", blocks.statement)
        assertEquals("", blocks.process)
        assertEquals("", blocks.finalAnswer)
    }

    @Test
    fun `statement open tag with empty content leaves empty statement`() {
        // 模型输出了空 statement（形如 <problem_statement></problem_statement>）
        val text = "<problem_statement></problem_statement>\n解题过程内容\n<final_answer>答</final_answer>"
        val blocks = splitSolveOutput(text)
        assertEquals("", blocks.statement)
        assertEquals("解题过程内容", blocks.process)
        assertEquals("答", blocks.finalAnswer)
    }

    @Test
    fun `stray text before statement open tag is dropped`() {
        // 协议要求 statement 是回复第一块；模型偶发前置闲聊时随开标签丢弃，不进任何展示区
        val text = "好的，我来解答。\n<problem_statement>\n题干\n</problem_statement>\n过程\n<final_answer>答</final_answer>"
        val blocks = splitSolveOutput(text)
        assertEquals("题干", blocks.statement)
        assertEquals("过程", blocks.process)
        assertEquals("答", blocks.finalAnswer)
    }

    @Test
    fun `statement and final both present with unclosed final`() {
        val text = """
            <problem_statement>
            题干
            </problem_statement>
            过程（缺闭标记的 final 场景）
            <final_answer>
            精炼作答截断
        """.trimIndent()
        val blocks = splitSolveOutput(text)
        assertEquals("题干", blocks.statement)
        assertEquals("过程（缺闭标记的 final 场景）", blocks.process)
        assertEquals("精炼作答截断", blocks.finalAnswer)
    }
}
