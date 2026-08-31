package me.rerere.rikkahub.data.repository

import me.rerere.workspace.WorkspaceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

/** read_file 分片读取的底层：RandomAccessFile 区间导出（越界安全） */
class WorkspaceManagerRangeReadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun manager(): WorkspaceManager {
        val manager = WorkspaceManager(tmp.newFolder("base"))
        manager.ensureWorkspace("ws1")
        return manager
    }

    private fun exportRange(manager: WorkspaceManager, start: Long, length: Long): String {
        val out = ByteArrayOutputStream()
        manager.exportRootfsFileRange("ws1", "/workspace/f.txt", start, length, out)
        return out.toString(Charsets.UTF_8)
    }

    @Test
    fun `reads requested byte range`() {
        val manager = manager()
        manager.writeText("ws1", "f.txt", "hello world", overwrite = true)
        assertEquals("hello", exportRange(manager, 0, 5))
        assertEquals("world", exportRange(manager, 6, 5))
    }

    @Test
    fun `start beyond EOF yields empty output`() {
        val manager = manager()
        manager.writeText("ws1", "f.txt", "abc", overwrite = true)
        assertEquals("", exportRange(manager, 100, 10))
        assertEquals("", exportRange(manager, 3, 10))
    }

    @Test
    fun `length clamped to remaining bytes`() {
        val manager = manager()
        manager.writeText("ws1", "f.txt", "0123456789", overwrite = true)
        assertEquals("3456789", exportRange(manager, 3, 100))
    }

    @Test
    fun `accepts arbitrary workspace paths`() {
        val manager = manager()
        manager.writeText("ws1", "sub/dir/g.txt", "xyz", overwrite = true)
        val out = ByteArrayOutputStream()
        manager.exportRootfsFileRange("ws1", "/workspace/sub/dir/g.txt", 1, 2, out)
        assertEquals("yz", out.toString(Charsets.UTF_8))
    }

    @Test
    fun `rejects invalid range parameters`() {
        val manager = manager()
        manager.writeText("ws1", "f.txt", "abc", overwrite = true)
        assertRangeError(manager, -1, 10, "start")
        assertRangeError(manager, 0, 0, "length")
        assertRangeError(manager, 0, -5, "length")
        assertRangeError(manager, 0, WorkspaceManager.MAX_RANGE_READ_BYTES + 1, "length")
    }

    private fun assertRangeError(manager: WorkspaceManager, start: Long, length: Long, messagePart: String) {
        try {
            exportRange(manager, start, length)
            fail("expected IllegalArgumentException containing '$messagePart'")
        } catch (e: IllegalArgumentException) {
            assertEquals(true, e.message.orEmpty().contains(messagePart, ignoreCase = true))
        }
    }
}