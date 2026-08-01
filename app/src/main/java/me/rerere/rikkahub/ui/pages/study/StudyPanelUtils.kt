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