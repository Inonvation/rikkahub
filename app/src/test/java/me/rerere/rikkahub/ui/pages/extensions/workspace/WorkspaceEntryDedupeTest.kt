package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.workspace.WorkspaceFileEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/** 批量操作嵌套选择去重：只保留最外层目录节点，去掉被目录覆盖的子孙条目 */
class WorkspaceEntryDedupeTest {

    private fun entry(path: String, isDirectory: Boolean = false) = WorkspaceFileEntry(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = isDirectory,
        sizeBytes = 0L,
        updatedAt = 0L,
    )

    @Test
    fun `no directory keeps all entries`() {
        val entries = listOf(entry("a.txt"), entry("b.txt"), entry("sub/c.txt"))
        assertEquals(entries, dedupeNestedEntries(entries))
    }

    @Test
    fun `file under selected directory is dropped`() {
        val dir = entry("sub", isDirectory = true)
        val child = entry("sub/c.txt")
        val other = entry("a.txt")
        val result = dedupeNestedEntries(listOf(dir, child, other))

        assertEquals(listOf("sub", "a.txt"), result.map { it.path })
    }

    @Test
    fun `nested directories collapse to outermost`() {
        val outer = entry("a", isDirectory = true)
        val inner = entry("a/b", isDirectory = true)
        val leaf = entry("a/b/c.txt")
        val result = dedupeNestedEntries(listOf(leaf, inner, outer))

        assertEquals(listOf("a"), result.map { it.path })
    }

    @Test
    fun `sibling with same prefix is not dropped`() {
        // sub2 与 sub 前缀相同, 但 sub2/x.txt 不以 "sub/" 开头, 不应被误伤
        val dir = entry("sub", isDirectory = true)
        val sibling = entry("sub2/x.txt")
        val child = entry("sub/c.txt")
        val result = dedupeNestedEntries(listOf(dir, sibling, child))

        assertEquals(listOf("sub", "sub2/x.txt"), result.map { it.path })
    }

    @Test
    fun `dotfile inside directory is dropped with its parent`() {
        val dir = entry("proj", isDirectory = true)
        val dotfile = entry("proj/.gitignore")
        val result = dedupeNestedEntries(listOf(dir, dotfile))

        assertEquals(listOf("proj"), result.map { it.path })
    }
}
