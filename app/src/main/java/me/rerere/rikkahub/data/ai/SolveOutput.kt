package me.rerere.rikkahub.data.ai

/**
 * 拍照搜题输出协议的切分纯函数。
 *
 * 根因：精炼作答依赖模型输出 <final_answer> 标记做流式分段（协议见 prompts/Solve.kt），
 * 但模型不保证 100% 遵守标记格式，页面展示与历史落库必须共用同一套容错切分，
 * 否则会出现「流式显示与历史回看内容不一致」。
 *
 * 三层容错（永不抛错）：
 * 1. 标记完整：开标记后、闭标记前 → final；其余 → process。
 * 2. 只有开标记（流式中断/截断）：开标记之后的全文 → final。
 * 3. 完全无标记：全文 → process，final 为空。
 */
object SolveOutputParser {
    const val OPEN_TAG = "<final_answer>"
    const val CLOSE_TAG = "</final_answer>"
}

/** solveQuestion 流式回调载荷：三个字段均为当前尝试的累积全文（覆盖式更新，非增量拼接） */
data class SolveStreamUpdate(
    val reasoning: String = "",
    val process: String = "",
    val finalAnswer: String = "",
)

/** solveQuestion 终态结果（与最后一次 SolveStreamUpdate 的切分结果一致） */
data class SolveResult(
    val reasoning: String,
    val process: String,
    val finalAnswer: String,
    /** OCR 降级路径下实际发给模型的题干文本（OCR 结果 + 用户补充），vision 路径为 null */
    val questionText: String? = null,
)

fun splitSolveOutput(text: String): Pair<String, String> {
    if (text.isBlank()) return "" to ""
    val openIdx = text.indexOf(SolveOutputParser.OPEN_TAG)
    if (openIdx < 0) return text.trim() to ""

    val process = text.substring(0, openIdx).trim()
    val afterOpen = text.substring(openIdx + SolveOutputParser.OPEN_TAG.length)
    val closeIdx = afterOpen.indexOf(SolveOutputParser.CLOSE_TAG)
    // 容错 2：闭标记缺失（流式中断）时开标记后的全文都算精炼区
    val final = if (closeIdx >= 0) {
        afterOpen.substring(0, closeIdx).trim()
    } else {
        afterOpen.trim()
    }
    return process to final
}
