package me.rerere.rikkahub.data.ai.transformers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkspaceReminderTransformerTest {

    @Test
    fun `blank cwd falls back to workspace root`() {
        assertEquals("/workspace/AGENTS.md", resolveCwdAgentsPath(null))
        assertEquals("/workspace/AGENTS.md", resolveCwdAgentsPath(""))
        assertEquals("/workspace/AGENTS.md", resolveCwdAgentsPath("   "))
    }

    @Test
    fun `absolute cwd keeps its segments`() {
        assertEquals("/workspace/projects/foo/AGENTS.md", resolveCwdAgentsPath("/workspace/projects/foo"))
    }

    @Test
    fun `relative cwd resolves under workspace`() {
        assertEquals("/workspace/projects/foo/AGENTS.md", resolveCwdAgentsPath("projects/foo"))
    }

    @Test
    fun `empty and dot segments ignored, trailing slash tolerated`() {
        assertEquals("/workspace/a/b/AGENTS.md", resolveCwdAgentsPath("/workspace//a/./b/"))
    }

    @Test
    fun `parent segments collapse within workspace`() {
        assertEquals("/workspace/b/AGENTS.md", resolveCwdAgentsPath("/workspace/a/../b"))
    }

    @Test
    fun `escape out of workspace rejected`() {
        assertNull(resolveCwdAgentsPath("/workspace/../etc"))
        assertNull(resolveCwdAgentsPath("/etc"))
        assertNull(resolveCwdAgentsPath("/root/x"))
        assertNull(resolveCwdAgentsPath("/workspacefoo"))
        assertNull(resolveCwdAgentsPath("a/../.."))
        assertNull(resolveCwdAgentsPath("/.."))
    }

    @Test
    fun `global agents file path deduped`() {
        assertNull(resolveCwdAgentsPath("/workspace/.agent"))
        assertEquals("/workspace/.agent/notes/AGENTS.md", resolveCwdAgentsPath("/workspace/.agent/notes"))
    }
}
