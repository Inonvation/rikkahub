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
