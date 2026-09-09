package me.rerere.rikkahub.data.ai

/**
 * 拍照搜题输出协议的三段切分纯函数。
 *
 * 协议 v2（新增题干块，根因：视觉直送路径此前没有「AI 读到的题面」可展示，
 * 用户无法判断模型有没有识别错题目——OCR 降级路径有 questionText 题干卡，直送路径没有）。
 * 模型输出结构（顺序固定）：
 *
 *   <problem_statement>
 *   ...AI 对题面的理解/转述（图片或重建题干题）...
 *   </problem_statement>
 *   ...完整解题过程（markdown）...
 *   <final_answer>
 *   ...精炼作答...
 *   </final_answer>
 *
 * 页面把三段分开渲染：题干卡（可对照原图纠错）/ 解题过程 / 精炼作答。
 * 纯文本题不要求 statement 块（题干就是用户原文，无识别错误可言）。
 *
 * 容错沿用既有思想（永不抛错、永不空屏）：
 * 1. 无 statement 开标签：statement 为空，其余按 final_answer 规则切（等同协议 v1 行为）。
 * 2. 有开标签无闭标签（流式中/模型未闭合）：开标签之后的全文暂计 statement——
 *    因为按协议 statement 位于回复最前，闭标签出现前的文本只可能是题面理解；
 *    流式每次按累积全文重切，闭标签一旦到达，后续内容自然进入 process。
 * 3. final_answer 规则不变：完整 / 缺闭（其后全文归 final）/ 无标记（全文归 process）。
 */
object SolveOutputParser {
    const val STATEMENT_OPEN = "<problem_statement>"
    const val STATEMENT_CLOSE = "</problem_statement>"
    const val OPEN_TAG = "<final_answer>"
    const val CLOSE_TAG = "</final_answer>"
}

/** 三段切分结果：statement=AI 识读题面，process=解题过程，finalAnswer=精炼作答 */
data class SolveBlocks(
    val statement: String,
    val process: String,
    val finalAnswer: String,
)

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
data class SolveStreamUpdate(
    val statement: String = "",
    val reasoning: String = "",
    val process: String = "",
    val finalAnswer: String = "",
)

/** solveQuestion 终态结果（与最后一次 SolveStreamUpdate 的切分结果一致） */
data class SolveResult(
    val statement: String = "",
    val reasoning: String = "",
    val process: String = "",
    val finalAnswer: String = "",
    /** OCR 降级路径下实际发给模型的题干文本（OCR 结果 + 用户补充），vision 路径为 null */
    val questionText: String? = null,
)

/**
 * 全量文本按协议切三段。输入是当前尝试的累积全文（流式中每次 chunk 后整体重切，
 * 与旧版本对 final_answer 的处理方式一致——切分纯函数、无状态、可单测）。
 */
fun splitSolveOutput(text: String): SolveBlocks {
    if (text.isBlank()) return SolveBlocks("", "", "")

    // ---- 第一段：statement（若协议要求且模型遵守） ----
    val stOpenIdx = text.indexOf(SolveOutputParser.STATEMENT_OPEN)
    var tail = text
    var statement = ""
    if (stOpenIdx >= 0) {
        val afterOpen = text.substring(stOpenIdx + SolveOutputParser.STATEMENT_OPEN.length)
        val stCloseIdx = afterOpen.indexOf(SolveOutputParser.STATEMENT_CLOSE)
        if (stCloseIdx >= 0) {
            // 开标签之前如有杂散文本（模型闲聊前缀），随开标签一并丢弃——协议要求
            // statement 是回复的第一块，开标签前不应有内容
            statement = afterOpen.substring(0, stCloseIdx).trim()
            tail = afterOpen.substring(stCloseIdx + SolveOutputParser.STATEMENT_CLOSE.length)
        } else {
            // 容错：开标签后未见闭标签 → 其后全文暂计 statement（流式未到/模型未闭合）
            statement = afterOpen.trim()
            tail = ""
        }
    }

    // ---- 第二段：process 与 finalAnswer（沿用以 final_answer 为中心的三层容错） ----
    if (tail.isBlank()) return SolveBlocks(statement, "", "")
    val openIdx = tail.indexOf(SolveOutputParser.OPEN_TAG)
    if (openIdx < 0) return SolveBlocks(statement, tail.trim(), "")

    val process = tail.substring(0, openIdx).trim()
    val afterOpen = tail.substring(openIdx + SolveOutputParser.OPEN_TAG.length)
    val closeIdx = afterOpen.indexOf(SolveOutputParser.CLOSE_TAG)
    // 容错：闭标记缺失（流式中断）时开标记后的全文都算精炼区
    val finalAnswer = if (closeIdx >= 0) {
        afterOpen.substring(0, closeIdx).trim()
    } else {
        afterOpen.trim()
    }
    return SolveBlocks(statement, process, finalAnswer)
}

/** 题干外壳内「可视为自然语言」的判定用：含中日韩文字即非纯公式 */
private val CJK_REGEX = Regex("[\u4e00-\u9fff\u3040-\u30ff\uac00-\ud7af]")

/**
 * 题干展示前剥离「恰好整段包裹」的块级 LaTeX 外壳（根因：部分模型/OCR 在转述题面时
 * 把整段题干输出进单个 display-math（`$$…$$` 或 `\[…\]`），渲染层会把这整段当块级公式
 * 排版——中文/英文正文变成数学斜体、markdown 标记（加粗/列表/段落）全部失效，
 * 用户看到的就是"题干只是一团 latex 块"。
 *
 * 边界与取舍：
 * - 只在"外壳恰好覆盖整段、且内部含自然语言（中文/日文/韩文、`\text`、或 ≥2 个词）"时
 *   剥壳，内部再交给 markdown + 行内 latex 渲染；整段就是纯公式（如 `$$\frac{1}{x}=2$$`）
 *   时保持原样走块级公式排版——剥掉外壳反而会让无定界公式退化回纯文本。
 * - 无外壳 / 不成对（流式中途）的文本原样返回，本函数只作用于展示层，
 *   不改变落库原文（编辑态仍可查看与修正原始题面）。
 */
fun unwrapWrappedLatexBlock(raw: String): String {
    val t = raw.trim()
    if (t.length <= 4) return raw
    val inner = when {
        t.startsWith("$$") && t.endsWith("$$") ->
            t.substring(2, t.length - 2).trim()
        t.startsWith("\\[") && t.endsWith("\\]") ->
            t.substring(2, t.length - 2).trim()
        else -> return raw
    }
    if (inner.isBlank()) return raw
    val wordCount = inner.split(Regex("\\s+")).count { it.any(Char::isLetter) }
    val hasNaturalLanguage = inner.contains(CJK_REGEX) ||
        inner.contains("\\text") ||
        wordCount >= 2
    return if (hasNaturalLanguage) inner else raw
}
