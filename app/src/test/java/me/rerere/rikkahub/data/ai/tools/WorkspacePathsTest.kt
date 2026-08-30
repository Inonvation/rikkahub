package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePathsTest {

    // ---- normalizeWorkspacePath：词法归一 ----

    @Test
    fun `normalize collapses dot segments and duplicate separators`() {
        assertEquals("/a/b", normalizeWorkspacePath("//a/./b/"))
        assertEquals("/a/b", normalizeWorkspacePath("/a/c/../b"))
        assertEquals("/a/b", normalizeWorkspacePath("/a/b/c/.."))
        assertEquals("/", normalizeWorkspacePath("///"))
    }

    @Test
    fun `normalize clamps absolute paths escaping root`() {
        assertEquals("/b", normalizeWorkspacePath("/../../b"))
        assertEquals("/", normalizeWorkspacePath("/a/../.."))
    }

    // ---- normalizeWorkspaceCwd：cwd 归一 ----

    @Test
    fun `cwd null blank root or escaping root resolves to null`() {
        assertEquals(null, normalizeWorkspaceCwd(null))
        assertEquals(null, normalizeWorkspaceCwd(""))
        assertEquals(null, normalizeWorkspaceCwd("   "))
        assertEquals(null, normalizeWorkspaceCwd("/"))
        assertEquals(null, normalizeWorkspaceCwd("/workspace"))
        assertEquals(null, normalizeWorkspaceCwd("/workspace/"))
        assertEquals(null, normalizeWorkspaceCwd("/workspace/docs/.."))
    }

    @Test
    fun `cwd absolute and relative forms normalize to absolute`() {
        assertEquals("/workspace/docs", normalizeWorkspaceCwd("/workspace/docs"))
        assertEquals("/workspace/docs", normalizeWorkspaceCwd("/workspace/docs/"))
        assertEquals("/workspace/docs/sub", normalizeWorkspaceCwd("docs/sub"))
        assertEquals("/tmp/scratch", normalizeWorkspaceCwd("/tmp/scratch"))
        assertEquals("/workspace/docs/sub", normalizeWorkspaceCwd("\\docs\\sub"))
    }

    // ---- resolveWorkspaceToolPath：相对/绝对解析 ----

    @Test
    fun `relative path resolves against cwd`() {
        assertEquals(
            "/workspace/docs/notes/a.md",
            resolveWorkspaceToolPath("notes/a.md", "/workspace/docs"),
        )
    }

    @Test
    fun `relative path falls back to workspace root when cwd is null`() {
        assertEquals(
            "/workspace/notes/a.md",
            resolveWorkspaceToolPath("notes/a.md", null),
        )
    }

    @Test
    fun `absolute path passes through with normalization`() {
        assertEquals("/workspace/b", resolveWorkspaceToolPath("/workspace/a/../b", "/workspace/docs"))
        assertEquals("/tmp/x", resolveWorkspaceToolPath("/tmp/./x", "/workspace/docs"))
    }

    @Test
    fun `relative dotdot escaping cwd resolves lexically`() {
        assertEquals(
            "/workspace/sibling/x",
            resolveWorkspaceToolPath("../sibling/x", "/workspace/docs"),
        )
        assertEquals(
            "/tmp/x",
            resolveWorkspaceToolPath("../../tmp/x", "/workspace/docs"),
        )
    }

    @Test
    fun `resolve trims whitespace and rejects blank and NUL`() {
        assertEquals("/workspace/x", resolveWorkspaceToolPath("  /workspace/x  ", null))
        try {
            resolveWorkspaceToolPath("   ", null)
            throw AssertionError("blank path should throw")
        } catch (_: IllegalArgumentException) {
        }
        try {
            resolveWorkspaceToolPath("a\u0000b", null)
            throw AssertionError("NUL path should throw")
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---- isUnderWorkspaceTree / isOutsideWorkspaceWritableRoots ----

    @Test
    fun `subtree check has no string prefix trap`() {
        assertTrue(isUnderWorkspaceTree("/workspace", "/workspace"))
        assertTrue(isUnderWorkspaceTree("/workspace/a", "/workspace"))
        assertFalse(isUnderWorkspaceTree("/workspacefoo", "/workspace"))
        assertFalse(isUnderWorkspaceTree("/tmp", "/workspace"))
    }

    @Test
    fun `writable roots are workspace and tmp only`() {
        assertFalse(isOutsideWorkspaceWritableRoots("/workspace"))
        assertFalse(isOutsideWorkspaceWritableRoots("/workspace/a/b"))
        assertFalse(isOutsideWorkspaceWritableRoots("/tmp/x"))
        assertTrue(isOutsideWorkspaceWritableRoots("/tmpfoo/x"))
        assertTrue(isOutsideWorkspaceWritableRoots("/etc/passwd"))
    }

    // ---- workspaceWriteNeedsApproval：写入审批判定 ----

    private val noOverrides = emptyMap<String, Boolean>()

    @Test
    fun `write inside cwd subtree needs no extra approval`() {
        assertFalse(
            workspaceWriteNeedsApproval(
                "notes/a.md", "workspace_write_file", noOverrides, "/workspace/docs", forceNoApproval = false,
            )
        )
        assertFalse(
            workspaceWriteNeedsApproval(
                "/workspace/docs/x", "workspace_write_file", noOverrides, "/workspace/docs", forceNoApproval = false,
            )
        )
    }

    @Test
    fun `write under workspace outside cwd needs approval`() {
        assertTrue(
            workspaceWriteNeedsApproval(
                "/workspace/other/x", "workspace_write_file", noOverrides, "/workspace/docs", forceNoApproval = false,
            )
        )
        assertTrue(
            workspaceWriteNeedsApproval(
                "../sibling/x", "workspace_edit_file", noOverrides, "/workspace/docs", forceNoApproval = false,
            )
        )
    }

    @Test
    fun `tmp writes stay approval free`() {
        assertFalse(
            workspaceWriteNeedsApproval(
                "/tmp/scratch.txt", "workspace_write_file", noOverrides, "/workspace/docs", forceNoApproval = false,
            )
        )
    }

    @Test
    fun `without cwd workspace root is the boundary`() {
        assertFalse(
            workspaceWriteNeedsApproval(
                "a.md", "workspace_write_file", noOverrides, null, forceNoApproval = false,
            )
        )
        assertTrue(
            workspaceWriteNeedsApproval(
                "/etc/passwd", "workspace_write_file", noOverrides, null, forceNoApproval = false,
            )
        )
    }

    @Test
    fun `outside writable roots forces approval even with forceNoApproval`() {
        assertTrue(
            workspaceWriteNeedsApproval(
                "/etc/passwd", "workspace_write_file", noOverrides, "/workspace/docs", forceNoApproval = true,
            )
        )
    }

    @Test
    fun `forceNoApproval skips cwd boundary but not writable roots`() {
        assertFalse(
            workspaceWriteNeedsApproval(
                "/workspace/other/x", "workspace_write_file", noOverrides, "/workspace/docs", forceNoApproval = true,
            )
        )
        assertTrue(
            workspaceWriteNeedsApproval(
                "/etc/passwd", "workspace_write_file", noOverrides, "/workspace/docs", forceNoApproval = true,
            )
        )
    }

    @Test
    fun `per workspace override can add but not remove cwd boundary approval`() {
        assertTrue(
            workspaceWriteNeedsApproval(
                "a.md", "workspace_write_file", mapOf("workspace_write_file" to true), "/workspace/docs", forceNoApproval = false,
            )
        )
        // 覆写关闭（false）不能豁免 cwd 外写入
        assertTrue(
            workspaceWriteNeedsApproval(
                "/workspace/other/x", "workspace_write_file", mapOf("workspace_write_file" to false), "/workspace/docs", forceNoApproval = false,
            )
        )
    }

    @Test
    fun `missing or unparseable path fails closed to approval`() {
        assertTrue(
            workspaceWriteNeedsApproval(
                null, "workspace_write_file", noOverrides, "/workspace/docs", forceNoApproval = false,
            )
        )
        assertTrue(
            workspaceWriteNeedsApproval(
                "", "workspace_write_file", noOverrides, "/workspace/docs", forceNoApproval = false,
            )
        )
        assertTrue(
            workspaceWriteNeedsApproval(
                "   ", "workspace_write_file", noOverrides, "/workspace/docs", forceNoApproval = false,
            )
        )
    }
}
