package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** analyzeMarkdownHealth 断链/空笔记体检逻辑 */
class TrustedFolderHealthTest {

    @Test
    fun `valid wikilink is not broken`() {
        val report = analyzeMarkdownHealth(
            listOf(
                "a.md" to "link to [[b]]",
                "b.md" to "content",
            )
        )
        assertTrue(report.brokenLinks.isEmpty())
        assertEquals(2, report.totalNotes)
    }

    @Test
    fun `broken wikilink is detected with source and target`() {
        val report = analyzeMarkdownHealth(
            listOf(
                "a.md" to "link to [[nonexistent]]",
                "b.md" to "content",
            )
        )
        assertEquals(1, report.brokenLinks.size)
        assertEquals("a.md", report.brokenLinks[0].source)
        assertEquals("nonexistent", report.brokenLinks[0].target)
        assertEquals("[[nonexistent]]", report.brokenLinks[0].link)
    }

    @Test
    fun `alias and heading and block anchors do not break the target`() {
        val report = analyzeMarkdownHealth(
            listOf(
                "a.md" to "[[b|显示文本]] and [[c#标题]] and [[d^块id]]",
                "b.md" to "x",
                "c.md" to "y",
                "d.md" to "z",
            )
        )
        assertTrue(report.brokenLinks.isEmpty())
    }

    @Test
    fun `attachment link with extension is ignored`() {
        val report = analyzeMarkdownHealth(
            listOf(
                "a.md" to "see [[img.png]] and [[note.md]]",
                "note.md" to "ok",
            )
        )
        assertTrue(report.brokenLinks.isEmpty())
    }

    @Test
    fun `path based link resolves by basename`() {
        val report = analyzeMarkdownHealth(
            listOf(
                "a.md" to "[[sub/b]]",
                "sub/b.md" to "x",
            )
        )
        assertTrue(report.brokenLinks.isEmpty())
    }

    @Test
    fun `empty note and frontmatter-only note detected`() {
        val report = analyzeMarkdownHealth(
            listOf(
                "empty.md" to "",
                "frontmatter-only.md" to "---\ntags: [a]\n---\n",
                "ok.md" to "# title\ncontent",
            )
        )
        assertEquals(listOf("empty.md", "frontmatter-only.md"), report.emptyNotes)
    }
}
