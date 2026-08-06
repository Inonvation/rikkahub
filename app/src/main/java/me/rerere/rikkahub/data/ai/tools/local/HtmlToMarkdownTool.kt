package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

private const val HTML_TO_MARKDOWN_MAX_CHARS = 16 * 1024
private const val HTML_TO_MARKDOWN_MAX_INPUT_CHARS = 512 * 1024

/** 块级标签：输出中自成段落 */
private val BLOCK_TAGS = setOf(
    "p", "div", "section", "article", "header", "footer", "nav", "main", "aside",
    "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "blockquote",
    "pre", "table", "tr", "hr",
)

/** 内容无意义、直接丢弃的标签 */
private val IGNORED_TAGS = setOf(
    "script", "style", "noscript", "template", "head", "meta", "link",
    "iframe", "object", "embed", "form", "input", "button", "select", "textarea",
    "svg", "canvas", "video", "audio", "source",
)

internal fun buildHtmlToMarkdownTool(): Tool = Tool(
    name = "html_to_markdown",
    description = """
        Convert HTML source code to clean Markdown text.
        Useful for reading scraped web pages or raw HTML more efficiently.
        Returns the converted Markdown only; the original HTML is not preserved.
        Input larger than 512KB is truncated before conversion.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("html", buildJsonObject {
                    put("type", "string")
                    put("description", "The raw HTML source code to convert")
                })
            },
            required = listOf("html")
        )
    },
    execute = {
        val html = it.jsonObject["html"]?.jsonPrimitive?.contentOrNull ?: return@Tool listOf(
            UIMessagePart.Text("""{"error": "html argument is missing"}""")
        )
        listOf(UIMessagePart.Text(convertHtmlToMarkdown(html)))
    }
)

/**
 * 把 HTML 源码转成干净的 Markdown。供 html_to_markdown 工具与 scrape_web 自动转换复用。
 * 解析失败时返回 "Conversion error: ..." 而非抛异常。
 */
internal fun convertHtmlToMarkdown(html: String): String {
    val markdown = runCatching {
        Jsoup.parse(html.take(HTML_TO_MARKDOWN_MAX_INPUT_CHARS)).toMarkdown()
    }.getOrElse { err ->
        "Conversion error: ${err.message ?: err.javaClass.simpleName}"
    }
    return if (markdown.length > HTML_TO_MARKDOWN_MAX_CHARS) {
        markdown.take(HTML_TO_MARKDOWN_MAX_CHARS) +
            "\n\n[output truncated: converted text exceeds ${HTML_TO_MARKDOWN_MAX_CHARS / 1024}KB]"
    } else {
        markdown
    }
}

private fun Document.toMarkdown(): String {
    val out = body().childNodes().joinToString("") { renderNode(it, Ctx()) }
    return out.replace(Regex("\n{3,}"), "\n\n").trim()
}

/** 遍历上下文：pre = 是否处于 <pre>/<code> 内（保留原文、不套用格式） */
private data class Ctx(val pre: Boolean = false)

private fun renderNode(node: Node, ctx: Ctx): String = when (node) {
    is TextNode -> if (ctx.pre) node.wholeText else escapeText(node.wholeText.replace(Regex("\\s+"), " "))
    is Element -> renderElement(node, ctx)
    else -> ""
}

private fun renderElement(el: Element, ctx: Ctx): String {
    val tag = el.tagName().lowercase()
    if (tag in IGNORED_TAGS) return ""
    // pre 上下文内：原样输出，避免嵌套的 code/pre 重复套代码块
    if (ctx.pre) return renderNodeChildren(el, ctx)

    return when (tag) {
        "h1", "h2", "h3", "h4", "h5", "h6" ->
            "\n\n${"#".repeat(tag[1].digitToInt())} ${renderNodeChildren(el, ctx).trim()}\n\n"
        "p" ->
            "\n\n${renderNodeChildren(el, ctx).trim()}\n\n"
        "br" ->
            "\n"
        "hr" ->
            "\n\n---\n\n"
        "a" -> {
            val href = el.attr("href")
            val content = renderNodeChildren(el, ctx)
            if (href.isNotBlank()) "[$content]($href)" else content
        }
        "strong", "b" -> "**${renderNodeChildren(el, ctx)}**"
        "em", "i" -> "*${renderNodeChildren(el, ctx)}*"
        "pre" -> {
            val content = renderNodeChildren(el, ctx.copy(pre = true)).trim()
            "\n\n```\n$content\n```\n\n"
        }
        "code" -> {
            val content = renderNodeChildren(el, ctx.copy(pre = true)).trim()
            if (content.contains('\n')) "\n\n```\n$content\n```\n\n" else "`$content`"
        }
        "blockquote" -> {
            val lines = renderNodeChildren(el, ctx).lines()
            "\n\n" + lines.joinToString("\n") { "> $it" } + "\n\n"
        }
        "img" -> {
            val alt = el.attr("alt").replace(Regex("\\s+"), " ")
            val src = el.attr("src")
            if (src.isNotBlank()) "![$alt]($src)" else ""
        }
        "ul", "ol" -> renderList(el, ctx, ordered = tag == "ol")
        "li" -> renderListItem(el, ctx)
        "table" -> renderTable(el, ctx)
        else -> {
            val content = renderNodeChildren(el, ctx)
            if (tag in BLOCK_TAGS) "\n\n$content\n\n" else content
        }
    }
}

private fun renderNodeChildren(el: Element, ctx: Ctx): String =
    el.childNodes().joinToString("") { renderNode(it, ctx) }

private fun renderList(el: Element, ctx: Ctx, ordered: Boolean): String {
    // 注意: 不能用 el.select(":scope > li") —— jsoup 不支持 :scope 伪类会抛解析异常。
    // 改用 children() 取直接子元素并过滤出 <li>。
    val items = el.children().filter { it.tagName().lowercase() == "li" }
    if (items.isEmpty()) return renderNodeChildren(el, ctx)
    val body = buildString {
        var index = 1
        items.forEach { item ->
            val content = renderListItem(item, ctx)
            append(if (ordered) "${index++}. $content" else "- $content")
            append('\n')
        }
    }
    return "\n\n$body\n\n"
}

private fun renderListItem(el: Element, ctx: Ctx): String {
    val content = renderNodeChildren(el, ctx)
    // 嵌套列表/代码块的续行加缩进，保持 markdown 结构可读
    return content.replace(Regex("\n(?=[-*0-9>])"), "\n  ")
}

private fun renderTable(el: Element, ctx: Ctx): String {
    val rows = el.select("tr")
    if (rows.isEmpty()) return "\n\n${renderNodeChildren(el, ctx)}\n\n"
    val body = buildString {
        rows.forEach { row ->
            val cells = row.children().joinToString(" | ") { it.text().trim() }
            append("| $cells |").append('\n')
            if (row == rows.first()) {
                val count = row.children().size
                append("| " + List(count) { "---" }.joinToString(" | ") + " |").append('\n')
            }
        }
    }
    return "\n\n$body\n\n"
}

/** 转义 markdown 特殊字符；不转义星号/下划线/方括号，以免破坏强调与链接语法 */
private fun escapeText(text: String): String =
    text.replace("\\", "\\\\")
        .replace("#", "\\#")
        .replace("`", "\\`")
