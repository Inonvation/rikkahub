package me.rerere.rikkahub.data.ai.transformers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ---- 环境块总预算分配（防三段 AGENTS/MEMORY 同时打满撑爆 system） ----

    @Test
    fun `small sections keep their full size`() {
        val (cwd, env, mem) = allocateEnvBudget(cwdChars = 500, envChars = 800, memoryChars = 300)
        assertEquals(Triple(500, 800, 300), Triple(cwd, env, mem))
    }

    @Test
    fun `per-section cap still applies before the shared budget`() {
        val (cwd, _, _) = allocateEnvBudget(cwdChars = 10_000, envChars = 0, memoryChars = 0)
        assertTrue("单段不得超过 MAX_AGENTS_INJECT_CHARS", cwd <= MAX_AGENTS_INJECT_CHARS)
    }

    @Test
    fun `shared budget drains in priority order cwd then env then memory`() {
        // 用小于单段上限的请求验证优先级：cwd 先占满，env 拿剩余，memory 归零
        val (cwd, env, mem) = allocateEnvBudget(
            cwdChars = 3_000,
            envChars = 3_000,
            memoryChars = 3_000,
            total = 5_000,
        )
        assertEquals(3_000, cwd)
        assertEquals(2_000, env)
        assertEquals(0, mem)
    }

    @Test
    fun `per-section cap and shared budget both apply`() {
        // 三段都远超上限：单段上限（4096）先于总预算（6144）生效，env 只能拿剩余 2048
        val (cwd, env, mem) = allocateEnvBudget(cwdChars = 20_000, envChars = 20_000, memoryChars = 20_000)
        assertEquals(MAX_AGENTS_INJECT_CHARS, cwd)
        assertEquals(MAX_ENV_TOTAL_CHARS - MAX_AGENTS_INJECT_CHARS, env)
        assertEquals(0, mem)
    }

    @Test
    fun `total allocation never exceeds the shared budget`() {
        val (cwd, env, mem) = allocateEnvBudget(cwdChars = 3_000, envChars = 3_000, memoryChars = 3_000)
        assertTrue(cwd + env + mem <= MAX_ENV_TOTAL_CHARS)
    }
}
