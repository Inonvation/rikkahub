package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 快照 walk（并行子树 + 隐藏目录剪枝）的结果正确性 */
class WorkspaceFileSystemWalkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fileSystem() = WorkspaceFileSystem()

    @Test
    fun `collects all visible files at any depth`() {
        val root = tmp.newFolder("ws")
        root.resolve("a.txt").writeText("a")
        root.resolve("sub").mkdirs()
        root.resolve("sub/b.txt").writeText("b")
        root.resolve("sub/deep").mkdirs()
        root.resolve("sub/deep/c.log").writeText("c")
        root.resolve("sub/deep/d.txt").writeText("d")

        val paths = fileSystem().listAllFiles(root).map { it.path }.toSet()
        assertEquals(setOf("a.txt", "sub/b.txt", "sub/deep/c.log", "sub/deep/d.txt"), paths)
    }

    @Test
    fun `skips trash and l2s dirs but keeps plain dot-dirs`() {
        val root = tmp.newFolder("ws")
        root.resolve("visible.txt").writeText("v")
        root.resolve(".trash").mkdirs()
        root.resolve(".trash/hidden.txt").writeText("h")
        // 旧语义仅隐藏 .trash 与 .l2s.*：.git 这类点目录内容本就在快照里（保持兼容）
        root.resolve("proj/.git").mkdirs()
        root.resolve("proj/.git/config.txt").writeText("c")
        root.resolve("proj/src").mkdirs()
        root.resolve("proj/src/main.kt").writeText("k")
        root.resolve(".l2s.link").writeText("l")
        root.resolve(".l2s.cache").mkdirs()
        root.resolve(".l2s.cache/blob.txt").writeText("b")

        val paths = fileSystem().listAllFiles(root).map { it.path }.toSet()
        assertEquals(
            setOf("visible.txt", "proj/.git/config.txt", "proj/src/main.kt"),
            paths,
        )
    }

    @Test
    fun `large tree stays complete and deterministic in content`() {
        val root = tmp.newFolder("ws")
        // 若干顶层目录，模拟 node_modules 形态，充分触发并行分行
        repeat(8) { d ->
            val dir = root.resolve("pkg$d").apply { mkdirs() }
            repeat(25) { f ->
                dir.resolve("file$f.js").writeText("x$d$f")
            }
            dir.resolve("inner").mkdirs()
            dir.resolve("inner/deep$d.txt").writeText("d$d")
        }
        root.resolve("rootfile.json").writeText("r")

        val entries = fileSystem().listAllFiles(root)
        val expected = 8 * 26 + 1
        assertEquals(expected, entries.size)
        assertEquals(expected, entries.map { it.path }.distinct().size)
        assertTrue(entries.all { it.sizeBytes > 0 })
    }
}