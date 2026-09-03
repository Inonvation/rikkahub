package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 回收站(软删除)功能正确性：移入/恢复/永久删除、重名与孤儿收养、损坏 manifest 自愈 */
class WorkspaceFileSystemTrashTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fileSystem() = WorkspaceFileSystem()

    @Test
    fun `move restore delete roundtrip`() {
        val root = tmp.newFolder("ws")
        val fs = fileSystem()
        fs.writeText(root, "src/main.txt", "hello")

        assertTrue(fs.moveToTrash(root, "src/main.txt"))
        assertFalse(File(root, "src/main.txt").exists())

        val trash = fs.listTrash(root)
        assertEquals(listOf("src/main.txt"), trash.map { it.path })

        assertTrue(fs.restoreFromTrash(root, "src/main.txt"))
        assertEquals("hello", fs.readText(root, "src/main.txt"))
        assertTrue(fs.listTrash(root).isEmpty())

        assertTrue(fs.moveToTrash(root, "src/main.txt"))
        assertTrue(fs.deleteFromTrash(root, "src/main.txt"))
        assertTrue(fs.listTrash(root).isEmpty())
        assertFalse(File(root, ".trash/src/main.txt").exists())
    }

    @Test
    fun `trashing same path twice keeps both entries`() {
        val root = tmp.newFolder("ws")
        val fs = fileSystem()

        fs.writeText(root, "a.txt", "first")
        assertTrue(fs.moveToTrash(root, "a.txt"))

        // 原路径被重新创建后再次删除, 不应覆盖第一条记录
        fs.writeText(root, "a.txt", "second")
        assertTrue(fs.moveToTrash(root, "a.txt"))

        val paths = fs.listTrash(root).map { it.path }.sorted()
        // 与 resolveConflict 一致: 后缀加在扩展名前, 即 "a (1).txt"
        assertEquals(listOf("a (1).txt", "a.txt"), paths)

        // 两条都能各自恢复, 且互不覆盖
        assertTrue(fs.restoreFromTrash(root, "a.txt"))
        assertTrue(fs.restoreFromTrash(root, "a (1).txt"))
        assertEquals("first", fs.readText(root, "a.txt"))
        assertEquals("second", fs.readText(root, "a (1).txt"))
    }

    @Test
    fun `trashing nested directory restores recursively`() {
        val root = tmp.newFolder("ws")
        val fs = fileSystem()
        fs.writeText(root, "proj/src/impl.kt", "code")

        assertTrue(fs.moveToTrash(root, "proj", recursive = true))
        assertFalse(File(root, "proj").exists())

        assertTrue(fs.restoreFromTrash(root, "proj"))
        assertEquals("code", fs.readText(root, "proj/src/impl.kt"))
    }

    @Test
    fun `corrupt manifest is quarantined and orphans are adopted`() {
        val root = tmp.newFolder("ws")
        val fs = fileSystem()

        // 制造一个已存在回收站但 manifest 损坏的现场(旧版本遗留)
        val trashDir = File(root, ".trash").apply { mkdirs() }
        File(trashDir, "orphan.txt").writeText("lost")
        File(trashDir, "manifest.json").writeText("{ not valid json")

        // 损坏的 manifest 不再抛错, 且孤儿文件被收养后可见
        val paths = fs.listTrash(root).map { it.path }
        assertEquals(listOf("orphan.txt"), paths)

        // 原损坏文件被隔离保留, 而非直接覆盖
        val backups = trashDir.listFiles().orEmpty().filter { it.name.startsWith("manifest.json.corrupt-") }
        assertEquals(1, backups.size)

        // 移入新文件不再报错, 且已有孤儿仍可恢复
        fs.writeText(root, "new.txt", "new")
        assertTrue(fs.moveToTrash(root, "new.txt"))
        assertTrue(fs.restoreFromTrash(root, "orphan.txt"))
        assertEquals("lost", fs.readText(root, "orphan.txt"))
    }

    @Test
    fun `restoring to occupied path renames instead of overwriting`() {
        val root = tmp.newFolder("ws")
        val fs = fileSystem()
        fs.writeText(root, "a.txt", "old")
        assertTrue(fs.moveToTrash(root, "a.txt"))

        fs.writeText(root, "a.txt", "new")
        assertTrue(fs.restoreFromTrash(root, "a.txt"))

        // 原路径被占用, 恢复自动改名, 不覆盖现有文件
        assertEquals("new", fs.readText(root, "a.txt"))
        assertEquals("old", fs.readText(root, "a (1).txt"))
    }

    @Test
    fun `restoring dotfile to occupied path keeps its name`() {
        val root = tmp.newFolder("ws")
        val fs = fileSystem()
        // 根因: resolveConflict 之前用 nameWithoutExtension 切名, 对 .gitignore 这类点文件
        // (最后一个点在下标 0) 会把名字切成空, 恢复时变成 " (1).gitignore"。
        fs.writeText(root, ".gitignore", "build/")
        assertTrue(fs.moveToTrash(root, ".gitignore"))

        fs.writeText(root, ".gitignore", "node_modules/")
        assertTrue(fs.restoreFromTrash(root, ".gitignore"))

        assertEquals("node_modules/", fs.readText(root, ".gitignore"))
        assertEquals("build/", fs.readText(root, ".gitignore (1)"))
    }
}
