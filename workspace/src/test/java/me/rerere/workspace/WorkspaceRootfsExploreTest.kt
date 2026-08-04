package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceRootfsExploreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var skillsDir: File

    private val root = "explore-test"

    private fun createManager(): WorkspaceManager {
        skillsDir = tempFolder.newFolder("skills")
        val uploadDir = tempFolder.newFolder("upload")
        return WorkspaceManager(
            baseDir = tempFolder.newFolder("workspaces"),
            bindMounts = listOf(
                WorkspaceBindMount(source = skillsDir, target = "/skills"),
                WorkspaceBindMount(source = uploadDir, target = "/upload"),
            ),
        ).also { it.ensureWorkspace(root) }
    }

    @Test
    fun listWorkspaceAreaShowsFilesAndDirs() {
        val manager = createManager()
        File(manager.filesDir(root), "readme.md").writeText("# hi")
        File(manager.filesDir(root), "src").mkdirs()
        File(manager.filesDir(root), "src/main.kt").writeText("fun main() {}")

        val entries = manager.listFilesInRootfs(root, "/workspace")
        assertEquals(listOf("readme.md", "src"), entries.map { it.name }.sorted())
    }

    @Test
    fun listSubdirectoryResolvesRelativePath() {
        val manager = createManager()
        File(manager.filesDir(root), "src").mkdirs()
        File(manager.filesDir(root), "src/main.kt").writeText("fun main() {}")

        val entries = manager.listFilesInRootfs(root, "/workspace/src")
        assertEquals(listOf("main.kt"), entries.map { it.name })
    }

    @Test
    fun listSubdirectoryReturnsAbsolutePathWithoutDuplicatePrefix() {
        val manager = createManager()
        File(manager.filesDir(root), "sub").mkdirs()
        File(manager.filesDir(root), "sub/main.kt").writeText("fun main() {}")

        // 回归: 子目录下列出的 path 应为 /workspace/sub/main.kt, 而非前缀重复的 /workspace/sub/sub/main.kt
        val entries = manager.listFilesInRootfs(root, "/workspace/sub")
        assertEquals(listOf("/workspace/sub/main.kt"), entries.map { it.path })
    }

    @Test
    fun listBindMountArea() {
        val manager = createManager()
        File(skillsDir, "code-runner").mkdirs()
        File(skillsDir, "code-runner/SKILL.md").writeText("# skill")

        val entries = manager.listFilesInRootfs(root, "/skills")
        assertEquals(listOf("code-runner"), entries.map { it.name })
    }

    @Test
    fun listRootfsInterior() {
        val manager = createManager()
        File(manager.linuxDir(root), "etc").mkdirs()
        File(manager.linuxDir(root), "etc/hostname").writeText("host\n")

        val entries = manager.listFilesInRootfs(root, "/etc")
        assertEquals(listOf("hostname"), entries.map { it.name })
    }

    @Test
    fun globMatchesPathsRelativeToWorkspaceRoot() {
        val manager = createManager()
        File(manager.filesDir(root), "src").mkdirs()
        File(manager.filesDir(root), "src/main.kt").writeText("fun main() {}")
        File(manager.filesDir(root), "src/util.kt").writeText("fun util() {}")
        File(manager.filesDir(root), "notes.txt").writeText("hi")

        // 验证 root-relative 语义: pattern 相对 workspace root 而非起始目录
        val matches = manager.globInRootfs(root, "src/*.kt", "/workspace")
        assertEquals(listOf("/workspace/src/main.kt", "/workspace/src/util.kt"), matches.map { it.path }.sorted())
    }

    @Test
    fun globMatchesPathsRelativeToSearchPath() {
        val manager = createManager()
        File(manager.filesDir(root), "ai-output").mkdirs()
        File(manager.filesDir(root), "ai-output/txt").mkdirs()
        File(manager.filesDir(root), "ai-output/txt/carry-test.txt").writeText("x")
        File(manager.filesDir(root), "ai-output/txt/other.txt").writeText("y")

        // path 指向起始目录, pattern 用相对该目录的 *.txt 即可命中(修复前会落空)
        val matches = manager.globInRootfs(root, "*.txt", "/workspace/ai-output/txt")
        assertEquals(
            listOf("/workspace/ai-output/txt/carry-test.txt", "/workspace/ai-output/txt/other.txt"),
            matches.map { it.path }.sorted(),
        )
    }

    @Test
    fun grepFindsMatchInWorkspace() {
        val manager = createManager()
        File(manager.filesDir(root), "a.txt").writeText("hello\nTODO: fix this\n")

        val results = manager.grepInRootfs(root, "TODO", "/workspace")
        assertEquals(1, results.size)
        assertEquals("/workspace/a.txt", results.single().path)
        assertEquals(2, results.single().line)
    }
}
