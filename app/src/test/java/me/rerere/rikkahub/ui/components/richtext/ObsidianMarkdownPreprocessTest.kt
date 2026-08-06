package me.rerere.rikkahub.ui.components.richtext

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 信任文件夹笔记预览的 Obsidian 预处理纯函数测试 */
class ObsidianMarkdownPreprocessTest {

    @Test
    fun stripFrontmatter_removesYamlHeader() {
        val content = "---\ntitle: 测试\ntags: [a]\n---\n\n正文内容"
        assertEquals("正文内容", stripFrontmatter(content))
    }

    @Test
    fun parseFrontmatter_extractsScalarProperty() {
        val (props, body) = parseFrontmatter("---\ntitle: 测试笔记\n---\n\n正文")
        assertEquals(listOf(FrontmatterProperty("title", "测试笔记")), props)
        assertEquals("正文", body)
    }

    @Test
    fun parseFrontmatter_extractsInlineArrayAsList() {
        val (props, body) = parseFrontmatter("---\ntags: [a, b]\n---\n正文")
        assertEquals(listOf(FrontmatterProperty("tags", "a,b", isList = true)), props)
        assertEquals("正文", body)
    }

    @Test
    fun parseFrontmatter_extractsBlockList() {
        val (props, _) = parseFrontmatter("---\ntags:\n  - a\n  - b\n---\n正文")
        assertEquals(listOf(FrontmatterProperty("tags", "a,b", isList = true)), props)
    }

    @Test
    fun parseFrontmatter_stripsQuotes() {
        val (props, _) = parseFrontmatter("---\ntitle: \"带引号标题\"\n---\n正文")
        assertEquals(listOf(FrontmatterProperty("title", "带引号标题")), props)
    }

    @Test
    fun parseFrontmatter_returnsOriginalWhenNoHeader() {
        val content = "普通文本\n---\n分割线"
        val (props, body) = parseFrontmatter(content)
        assertTrue(props.isEmpty())
        assertEquals(content, body)
    }

    @Test
    fun parseFrontmatter_returnsOriginalWhenHeaderUnclosed() {
        val content = "---\ntitle: x"
        val (props, body) = parseFrontmatter(content)
        assertTrue(props.isEmpty())
        assertEquals(content, body)
    }

    @Test
    fun stripFrontmatter_keepsContentWithoutHeader() {
        val content = "普通文本\n---\n分割线"
        assertEquals(content, stripFrontmatter(content))
    }

    @Test
    fun stripComments_removesInlineAndMultiline() {
        val content = "前%%隐藏%%后\n中间\n%%跨行\n注释%%尾"
        assertEquals("前后\n中间\n尾", stripComments(content))
    }

    @Test
    fun extractFootnotes_pullsDefinitionsAndKeepsBody() {
        val content = "正文引用[^1]继续\n[^1]: 这是脚注内容\n[^2]: 第二脚注\n更多正文"
        val (body, footnotes) = extractFootnotes(content)
        assertEquals("正文引用[^1]继续\n更多正文", body)
        assertEquals("[^1] ：这是脚注内容\n[^2] ：第二脚注", footnotes)
    }

    @Test
    fun convertInlineFootnotes_turnsInlineIntoReference() {
        val (body, defs) = convertInlineFootnotes("正文^[脚注内容]继续")
        assertEquals("正文[^1]继续", body)
        assertEquals(listOf("[^1] ：脚注内容"), defs)
    }

    @Test
    fun convertInlineFootnotes_avoidsIdConflict() {
        // 正文已有 [^1] 引用，行内脚注从 2 开始，避免序号冲突
        val (body, defs) = convertInlineFootnotes("引用[^1]和^[第二个脚注]")
        assertEquals("引用[^1]和[^2]", body)
        assertEquals(listOf("[^2] ：第二个脚注"), defs)
    }

    @Test
    fun convertInlineFootnotes_keepsContentWithoutInline() {
        val content = "普通文本"
        val (body, defs) = convertInlineFootnotes(content)
        assertEquals(content, body)
        assertTrue(defs.isEmpty())
    }

    @Test
    fun expandNoteEmbeds_inlinesNoteSkipsImage() = runBlocking {
        val resolver: suspend (String) -> String? = { name ->
            when (name) {
                "A" -> "![[B]] 和 ![[img.png]]"
                "B" -> "B 内容"
                else -> null
            }
        }
        val out = expandNoteEmbeds("开头 ![[A]] 结尾", resolver, emptySet(), 0)
        assertTrue("应内联 B 的内容", out.contains("B 内容"))
        assertTrue("图片嵌入应保留给图片解析", out.contains("![[img.png]]"))
        assertFalse("A 本身应被展开替换", out.contains("![[A]]"))
    }

    @Test
    fun expandNoteEmbeds_preventsCycle() = runBlocking {
        val resolver: suspend (String) -> String? = { name ->
            when (name) {
                "A" -> "引用 ![[B]]"
                "B" -> "引用 ![[A]]"
                else -> null
            }
        }
        val out = expandNoteEmbeds("![[A]]", resolver, emptySet(), 0)
        // A→B→A 循环被 expanding 集截断，不会无限展开
        assertTrue(out.contains("引用"))
    }
}
