package me.rerere.rikkahub.utils

/**
 * 移除字符串中的Markdown格式
 * @return 移除Markdown格式后的纯文本
 */
fun String.stripMarkdown(): String {
    return this
        // 移除代码块 (```...``` 和 `...`)
        .replace(Regex("```[\\s\\S]*?```|`[^`]*?`"), "")
        // 移除图片和链接，但保留其文本内容
        .replace(Regex("!?\\[([^\\]]+)\\]\\([^\\)]*\\)"), "$1")
        // 移除加粗和斜体 (先处理两个星号的)
        .replace(Regex("\\*\\*([^*]+?)\\*\\*"), "$1")
        .replace(Regex("\\*([^*]+?)\\*"), "$1")
        // 移除下划线
        .replace(Regex("__([^_]+?)__"), "$1")
        .replace(Regex("_([^_]+?)_"), "$1")
        // 移除删除线
        .replace(Regex("~~([^~]+?)~~"), "$1")
        // 移除标题标记 (多行模式)
        .replace(Regex("(?m)^#+\\s*"), "")
        // 移除列表标记 (多行模式)
        .replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        .replace(Regex("(?m)^\\s*\\d+\\.\\s+"), "")
        // 移除引用标记 (多行模式)
        .replace(Regex("(?m)^>\\s*"), "")
        // 移除水平分割线
        .replace(Regex("(?m)^(\\s*[-*_]){3,}\\s*$"), "")
        // 将多个换行符压缩，以保留段落
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/**
 * 独占一行的加粗文本行：`**标题**`。
 *
 * 用 `[^*]+` 而非 `.+?`：后者非贪婪匹配会跨越中间的 `**`，
 * 一行出现多个加粗时（`**a** **b**`）会取到 `a** **b`，把星号残留直接显示到标题里。
 * 标题内部带星号的行（`**a*b**`）本就不是规整的阶段标题，不匹配、继续往上找更合理。
 */
private val THINKING_TITLE_PATTERN = Regex("^\\*\\*([^*]+)\\*\\*$")

/** markdown 标题行：`# 标题` ~ `###### 标题`。与加粗行一起覆盖两种常见的小标题写法。 */
private val THINKING_HEADING_PATTERN = Regex("^#{1,6}\\s*(\\S.*?)\\s*#*$")

/** 单行标题候选：加粗行或 markdown 标题行；都不是则返回 null。 */
private fun titleCandidateOf(line: String): String? {
    THINKING_TITLE_PATTERN.find(line)?.let { match ->
        val title = match.groupValues[1].trim()
        if (title.isNotBlank()) return title
    }
    THINKING_HEADING_PATTERN.find(line)?.let { match ->
        val title = match.groupValues[1].trim()
        if (title.isNotBlank()) return title
    }
    return null
}

/**
 * 提取思考内容的阶段标题：取**最后一个**独占一行的标题行（加粗行或 markdown 标题行）。
 *
 * 从后往前扫描是刻意的——模型的思考流里越靠后的标题行越接近"当前阶段"，
 * 取最后一个才能让标题跟着思考推进往前走。副作用是：模型一旦进入长正文、
 * 不再写新的标题行，标题就会停在上一个阶段不再变化（这是预期行为，不是 bug）。
 *
 * 流式中间态（最后一行还是半截的 `**分析中`）天然不匹配，标题会保持上一个完整标题，
 * 避免输出过程中标题闪烁。
 *
 * 代码围栏内的行会被跳过：思考里常出现代码块，而 `# 注释`、`# 标题` 一类的内容
 * 属于代码本身，误当成阶段标题会让头部显示与思考内容无关的东西。
 *
 * @return 标题文本；没有规整的标题行时返回 null
 */
fun String.extractThinkingTitle(): String? {
    val lines = this.lines()

    // 先从头扫一遍标记代码围栏区间：围栏内的行不参与标题匹配
    val inCodeBlock = BooleanArray(lines.size)
    var fenced = false
    for (i in lines.indices) {
        if (lines[i].trimStart().startsWith("```")) {
            inCodeBlock[i] = true
            fenced = !fenced
        } else {
            inCodeBlock[i] = fenced
        }
    }

    for (i in lines.indices.reversed()) {
        if (inCodeBlock[i]) continue
        val title = titleCandidateOf(lines[i].trim()) ?: continue
        return title
    }

    return null
}
