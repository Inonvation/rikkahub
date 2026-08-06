package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证 HTML→Markdown 转换的健壮性，重点覆盖列表（历史上 :scope 选择器报错）。
 */
class HtmlToMarkdownToolTest {

    @Test
    fun `ul list converts to markdown bullets`() {
        val html = "<ul><li>苹果</li><li>香蕉</li><li>橙子</li></ul>"
        val result = convertHtmlToMarkdown(html)
        assertEquals("- 苹果\n- 香蕉\n- 橙子", result)
    }

    @Test
    fun `ol list converts to markdown numbered list`() {
        val html = "<ol><li>第一步</li><li>第二步</li></ol>"
        val result = convertHtmlToMarkdown(html)
        assertEquals("1. 第一步\n2. 第二步", result)
    }

    @Test
    fun `nested list keeps indentation`() {
        // 规范嵌套：内层包在 <ul> 里，内层列表自带块级空行
        val html = "<ul><li>外层<ul><li>内层</li></ul></li></ul>"
        val result = convertHtmlToMarkdown(html)
        assertEquals("- 外层\n\n  - 内层", result)
    }

    @Test
    fun `list with links and emphasis still works`() {
        val html = "<ul><li><strong>粗体</strong> 和 <a href=\"https://x.com\">链接</a></li></ul>"
        val result = convertHtmlToMarkdown(html)
        assertEquals("- **粗体** 和 [链接](https://x.com)", result)
    }

    @Test
    fun `empty or invalid html does not crash`() {
        val result = convertHtmlToMarkdown("")
        // 空输入解析出空 body，不抛异常
        assert(result.isNullOrBlank() || result == "")
    }
}
