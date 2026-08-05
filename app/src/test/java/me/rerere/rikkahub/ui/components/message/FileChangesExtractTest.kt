package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

/** extractFileChanges / extractTrustedFolderChanges：工具 input/output → 文件变更列表的纯提取 */
class FileChangesExtractTest {

    private fun tool(
        toolCallId: String,
        toolName: String,
        input: String = "{}",
        output: List<UIMessagePart> = emptyList(),
    ) = UIMessagePart.Tool(
        toolCallId = toolCallId,
        toolName = toolName,
        input = input,
        output = output,
    )

    private fun textOutput(text: String) = listOf(UIMessagePart.Text(text))

    @Test
    fun `write_file with changeStatus edited is EDITED`() {
        val changes = extractFileChanges(
            listOf(
                tool(
                    "1",
                    "workspace_write_file",
                    """{"path":"/workspace/a.txt"}""",
                    textOutput("""{"changeStatus":"edited"}"""),
                )
            )
        )
        assertEquals(1, changes.size)
        assertEquals("/workspace/a.txt", changes[0].path)
        assertEquals(FileChangeStatus.EDITED, changes[0].status)
    }

    @Test
    fun `write_file without changeStatus defaults to ADDED`() {
        val changes = extractFileChanges(
            listOf(tool("1", "workspace_write_file", """{"path":"/workspace/a.txt"}""", textOutput("""{"ok":true}""")))
        )
        assertEquals(FileChangeStatus.ADDED, changes[0].status)
    }

    @Test
    fun `edit_file is EDITED from input path`() {
        val changes = extractFileChanges(
            listOf(tool("1", "workspace_edit_file", """{"path":"/workspace/b.kt"}""", textOutput("""{"ok":true}""")))
        )
        assertEquals(FileChangeStatus.EDITED, changes[0].status)
        assertEquals("/workspace/b.kt", changes[0].path)
    }

    @Test
    fun `shell output with added modified removed files is extracted`() {
        val output = """{"addedFiles":["a.txt","b.txt"],"modifiedFiles":["c.txt"],"removedFiles":["d.txt"]}"""
        val changes = extractFileChanges(
            listOf(tool("1", "workspace_shell", """{"command":"ls"}""", textOutput(output)))
        )
        assertEquals(4, changes.size)
        assertEquals(FileChangeStatus.ADDED, changes[0].status)
        assertEquals(FileChangeStatus.EDITED, changes[2].status)
        assertEquals(FileChangeStatus.REMOVED, changes[3].status)
    }

    @Test
    fun `shell output without change keys is skipped`() {
        val changes = extractFileChanges(
            listOf(tool("1", "workspace_shell", "{}", textOutput("""{"stdout":"nothing changed"}""")))
        )
        assertEquals(0, changes.size)
    }

    @Test
    fun `shell output with large stdout is extracted via lightweight decode`() {
        val bigStdout = "x".repeat(128 * 1024)
        val output = """{"addedFiles":["big.txt"],"stdout":"$bigStdout","stderr":""}"""
        val changes = extractFileChanges(
            listOf(tool("1", "workspace_shell", """{"command":"build"}""", textOutput(output)))
        )
        assertEquals(1, changes.size)
        assertEquals("big.txt", changes[0].path)
    }

    @Test
    fun `duplicate path in output keeps later status`() {
        // 提取顺序为 addedFiles → modifiedFiles → removedFiles，同一路径后者胜（此处 removed 在后 → REMOVED）
        val output = """{"addedFiles":["a.txt"],"removedFiles":["a.txt"]}"""
        val changes = extractFileChanges(
            listOf(tool("1", "workspace_shell", "{}", textOutput(output)))
        )
        assertEquals(1, changes.size)
        assertEquals(FileChangeStatus.REMOVED, changes[0].status)
    }

    @Test
    fun `trusted folder write is ADDED`() {
        val changes = extractTrustedFolderChanges(
            listOf(tool("1", "trusted_folder_write", """{"path":"notes/a.md"}""", textOutput("""{"changeStatus":"added"}""")))
        )
        assertEquals(FileChangeStatus.ADDED, changes[0].status)
        assertEquals("notes/a.md", changes[0].path)
    }

    @Test
    fun `trusted folder edit and delete`() {
        val changes = extractTrustedFolderChanges(
            listOf(
                tool("1", "trusted_folder_edit", """{"path":"notes/a.md"}""", textOutput("""{"ok":true}""")),
                tool("2", "trusted_folder_delete", """{"path":"notes/b.md"}""", textOutput("""{"ok":true}""")),
            )
        )
        assertEquals(2, changes.size)
        assertEquals(FileChangeStatus.EDITED, changes[0].status)
        assertEquals(FileChangeStatus.REMOVED, changes[1].status)
    }
}
