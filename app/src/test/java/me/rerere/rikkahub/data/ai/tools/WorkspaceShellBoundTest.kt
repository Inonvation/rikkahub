package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** workspace_shell 展示层输出自截（head+tail）的行为 */
class WorkspaceShellBoundTest {

    @Test
    fun `short stream is not truncated`() {
        assertNull(boundShellStream("hello", execTruncated = false))
        assertNull(boundShellStream("hello", execTruncated = true))
    }

    @Test
    fun `at limit stream is not truncated`() {
        val text = "x".repeat(10 * 1024)
        assertNull(boundShellStream(text, execTruncated = false))
    }

    @Test
    fun `over limit keeps head and tail when exec preserved the stream`() {
        val text = "H".repeat(7 * 1024) + "M".repeat(10 * 1024) + "T".repeat(3 * 1024)
        val out = boundShellStream(text, execTruncated = false)
        assertTrue(out != null)
        assertEquals("H".repeat(7 * 1024), out!!.substring(0, 7 * 1024))
        assertTrue(out.endsWith("T".repeat(3 * 1024)))
        // 工具层标记格式：[N chars omitted]（无 middle 前缀——middle 是 SubAgentLoop 兜底层的格式）
        assertTrue(out.contains("[${10 * 1024} chars omitted]"))
        // 截断后整体仍在 10K 级别（head + 标记 + tail）
        assertTrue(out.length < text.length)
    }

    @Test
    fun `over limit with exec truncation keeps head only`() {
        // 执行层只保留了 128K 头部、没有真实尾部：不再伪造 tail，退化为 head-only
        val text = "x".repeat(20 * 1024)
        val out = boundShellStream(text, execTruncated = true)
        assertTrue(out != null)
        assertEquals(7 * 1024, out!!.length)
        assertTrue(!out.contains("[middle"))
    }

    @Test
    fun `per-stream flags are independent`() {
        // stdout/stderr 分流后：只有爆掉的那条流退化为 head-only，
        // 未爆的那条仍保留 head+tail 真实尾部（工具层按各自标志分别调用 boundShellStream）
        val untouched = "y".repeat(7 * 1024 + 1024)
        val huge = "z".repeat(20 * 1024)
        assertEquals(null, boundShellStream(untouched, execTruncated = false))
        val stdoutBounded = boundShellStream(huge, execTruncated = true)
        val stderrBounded = boundShellStream(huge, execTruncated = false)
        assertEquals(7 * 1024, stdoutBounded!!.length)
        assertTrue(!stdoutBounded.contains("chars omitted"))
        assertTrue(stderrBounded!!.contains("chars omitted"))
        assertTrue(stderrBounded.endsWith("z".repeat(3 * 1024)))
    }
}