package me.rerere.rikkahub.ui.pages.study

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TranslationItem(val pos: String = "", val definition: String = "")

@Serializable
data class ExampleItem(val en: String = "", val zh: String = "")

private val studyJson = Json { ignoreUnknownKeys = true; isLenient = true }

fun parseTranslations(raw: String): List<TranslationItem> =
    runCatching { studyJson.decodeFromString<List<TranslationItem>>(raw) }.getOrDefault(emptyList())

fun parseExamples(raw: String): List<ExampleItem> =
    runCatching { studyJson.decodeFromString<List<ExampleItem>>(raw) }.getOrDefault(emptyList())

fun parseTags(raw: String): List<String> =
    runCatching { studyJson.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

fun parseKnowledgePoints(raw: String): List<String> =
    runCatching { studyJson.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

/**
 * 修复 AI 生成 JSON 时 LaTeX 反斜杠被 JSON 解析器错误解释的问题。
 * JSON 中 \f \t \b 会被当作控制字符（换页/制表/退格），导致 \frac → rac, \times → imes 等。
 * 注意：不替换 \n \r，因为文本中的正常换行不应被破坏。
 */
fun fixLatexEscapes(text: String): String {
    // 只修复跟在字母前的控制字符（说明它原本是 LaTeX 命令的一部分）
    val sb = StringBuilder()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '' && i + 1 < text.length && text[i + 1].isLetter() -> {
                sb.append("\\f") // form feed → \frac, \forall, \flat...
            }
            c == '\t' && i + 1 < text.length && text[i + 1].isLetter() -> {
                sb.append("\\t") // tab → \times, \to, \tan, \theta, \tau, \text...
            }
            c == '\b' && i + 1 < text.length && text[i + 1].isLetter() -> {
                sb.append("\\b") // backspace → \beta, \binom, \bmod, \big, \bar...
            }
            else -> sb.append(c)
        }
        i++
    }
    return sb.toString()
}

// 常见 LaTeX 命令 → Unicode 符号映射
private val LATEX_SYMBOL_MAP = mapOf(
    // 希腊字母
    "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
    "\\epsilon" to "ε", "\\varepsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η",
    "\\theta" to "θ", "\\vartheta" to "ϑ", "\\iota" to "ι", "\\kappa" to "κ",
    "\\lambda" to "λ", "\\mu" to "μ", "\\nu" to "ν", "\\xi" to "ξ",
    "\\pi" to "π", "\\varpi" to "ϖ", "\\rho" to "ρ", "\\varrho" to "ϱ",
    "\\sigma" to "σ", "\\varsigma" to "ς", "\\tau" to "τ", "\\upsilon" to "υ",
    "\\phi" to "φ", "\\varphi" to "ϕ", "\\chi" to "χ", "\\psi" to "ψ",
    "\\omega" to "ω",
    "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ", "\\Lambda" to "Λ",
    "\\Xi" to "Ξ", "\\Pi" to "Π", "\\Sigma" to "Σ", "\\Upsilon" to "Υ",
    "\\Phi" to "Φ", "\\Psi" to "Ψ", "\\Omega" to "Ω",
    // 运算符
    "\\times" to "×", "\\cdot" to "·", "\\div" to "÷", "\\pm" to "±",
    "\\mp" to "∓", "\\ast" to "∗", "\\star" to "⋆", "\\circ" to "∘",
    "\\oplus" to "⊕", "\\ominus" to "⊖", "\\otimes" to "⊗", "\\oslash" to "⊘",
    "\\odot" to "⊙", "\\wedge" to "∧", "\\vee" to "∨", "\\cap" to "∩",
    "\\cup" to "∪", "\\setminus" to "∖", "\\neg" to "¬",
    // 关系符
    "\\leq" to "≤", "\\geq" to "≥", "\\neq" to "≠", "\\approx" to "≈",
    "\\equiv" to "≡", "\\sim" to "∼", "\\simeq" to "≃", "\\propto" to "∝",
    "\\ll" to "≪", "\\gg" to "≫", "\\doteq" to "≐", "\\perp" to "⊥",
    "\\parallel" to "∥",
    // 箭头
    "\\to" to "→", "\\rightarrow" to "→", "\\leftarrow" to "←",
    "\\Rightarrow" to "⇒", "\\Leftarrow" to "⇐", "\\leftrightarrow" to "↔",
    "\\mapsto" to "↦", "\\uparrow" to "↑", "\\downarrow" to "↓",
    // 大运算符
    "\\sum" to "∑", "\\prod" to "∏", "\\int" to "∫", "\\oint" to "∮",
    "\\coprod" to "∐", "\\bigcap" to "⋂", "\\bigcup" to "⋃",
    // 集合
    "\\emptyset" to "∅", "\\varnothing" to "∅", "\\in" to "∈", "\\notin" to "∉",
    "\\subset" to "⊂", "\\supset" to "⊃", "\\subseteq" to "⊆", "\\supseteq" to "⊇",
    // 杂项
    "\\infty" to "∞", "\\partial" to "∂", "\\nabla" to "∇", "\\forall" to "∀",
    "\\exists" to "∃", "\\nexists" to "∄", "\\angle" to "∠", "\\triangle" to "△",
    "\\square" to "□", "\\Box" to "□", "\\Re" to "ℜ", "\\Im" to "ℑ",
    "\\ell" to "ℓ", "\\hbar" to "ℏ", "\\aleph" to "ℵ",
    "\\ldots" to "…", "\\cdots" to "⋯", "\\vdots" to "⋮", "\\ddots" to "⋱",
    "\\textdegree" to "°",
)

/**
 * 从文本中提取纯文本摘要，用于卡片/列表标题。
 * 公式（$...$、$$...$$、裸 LaTeX 命令）不渲染成图片/上下标，而是转成紧凑文字：
 * 去掉 $ 定界符，LaTeX 符号命令转 Unicode（\log→log、\sum→∑），\frac/\sqrt 转斜线形式，
 * 去反斜杠和花括号。这样标题始终是"卡片内容的总结"，不含突兀的公式样式。
 */
fun extractPlainText(text: String): String {
    if (text.isBlank()) return text
    var s = text
        .replace(Regex("\\$\\$[\\s\\S]*?\\$\\$"), " ") // 块公式替换为单个空格，避免粘连
        .replace(Regex("\\$[^\\n$]*?\\$")) { it.groupValues[0].removePrefix("$").removeSuffix("$").trim() }
    for ((cmd, symbol) in LATEX_SYMBOL_MAP.entries.sortedByDescending { it.key.length }) {
        s = s.replace(cmd, symbol)
    }
    s = Regex("\\\\frac\\s*\\{([^{}]*)\\}\\s*\\{([^{}]*)\\}").replace(s) { "${it.groupValues[1]}/${it.groupValues[2]}" }
    s = Regex("\\\\sqrt\\s*\\{([^{}]*)\\}").replace(s) { "√${it.groupValues[1]}" }
    s = s.replace(Regex("""\\([a-zA-Z]+)"""), { it.groupValues[1] })
    s = s.replace("{", "").replace("}", "")
    // 压缩连续空白
    return s.replace(Regex("\\s{2,}"), " ").trim()
}

// 白名单：裸 LaTeX 命令（未用 $ 包裹）才包成 $...$
private val BARE_MATH_COMMANDS = setOf(
    "\\log", "\\ln", "\\lg", "\\sin", "\\cos", "\\tan", "\\cot", "\\sec", "\\csc",
    "\\lim", "\\max", "\\min",
) + LATEX_SYMBOL_MAP.keys

private val COMMAND_REGEX = Regex("""\\[a-zA-Z]+""")

// 数学表达式内连续字符：字母/数字/运算符/`_`/`^`/`{}`/`.`/`,`；空格仅当两侧都是数学字符
private fun isMathExprChar(c: Char, prev: Char?): Boolean = when {
    c.isLetterOrDigit() || c in "=_+-*/()<>,.!~^{}_" -> true
    c == ' ' && prev != null && prev.isLetterOrDigit() -> true
    else -> false
}

/**
 * 把学习工具内容里"裸 LaTeX 命令"（如 `解：\log_2 x=3`）包成 $...$，使其能被 MarkdownBlock 识别为公式。
 * 只做白名单限定，且跳过已 $...$ / $$...$$ / \(...\) / \[...\] / 反引号包裹的内容，避免误伤普通反斜杠。
 */
fun wrapBareLatex(text: String): String {
    val sb = StringBuilder()
    var i = 0
    var inInlineMath = false // $...$
    var inBlockMath = false  // $$...$$
    var inParenMath = false  // \(...\)
    var inBracketMath = false // \[...\]
    var inCode = false       // `...` 或 ```...```
    while (i < text.length) {
        val c = text[i]
        val inAnyMath = inInlineMath || inBlockMath || inParenMath || inBracketMath
        when {
            // 反引号代码
            c == '`' -> {
                var n = 0
                while (i + n < text.length && text[i + n] == '`') n++
                inCode = !inCode
                repeat(n) { sb.append('`') }
                i += n
            }
            // $$ 块公式
            c == '$' && i + 1 < text.length && text[i + 1] == '$' -> {
                inBlockMath = !inBlockMath
                sb.append("$$")
                i += 2
            }
            // $ 行内公式
            c == '$' -> {
                inInlineMath = !inInlineMath
                sb.append('$')
                i++
            }
            // \( 与 \)
            c == '\\' && i + 1 < text.length && (text[i + 1] == '(' || text[i + 1] == ')') -> {
                inParenMath = text[i + 1] == '('
                sb.append('\\').append(text[i + 1])
                i += 2
            }
            // \[ 与 \]
            c == '\\' && i + 1 < text.length && (text[i + 1] == '[' || text[i + 1] == ']') -> {
                inBracketMath = text[i + 1] == '['
                sb.append('\\').append(text[i + 1])
                i += 2
            }
            // 反斜杠命令
            c == '\\' -> {
                if (inAnyMath || inCode) {
                    sb.append('\\')
                    i++
                    continue
                }
                val m = COMMAND_REGEX.find(text, i)
                if (m != null && m.range.first == i && m.value in BARE_MATH_COMMANDS) {
                    var j = m.range.last + 1
                    while (j < text.length && isMathExprChar(text[j], text.getOrNull(j - 1))) j++
                    val wrapped = text.substring(i, j).trim()
                    sb.append('$').append(wrapped).append('$')
                    i = j
                } else {
                    sb.append('\\')
                    i++
                }
            }
            else -> {
                sb.append(c)
                i++
            }
        }
    }
    return sb.toString()
}

