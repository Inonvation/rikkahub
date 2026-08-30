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

    @Test
    fun `create_folder is not reported as a change`() {
        val changes = extractTrustedFolderChanges(
            listOf(tool("1", "trusted_folder_create_folder", """{"path":"newdir"}""", textOutput("""{"ok":true}""")))
        )
        assertEquals(0, changes.size)
    }

    @Test
    fun `rename is EDITED with new path from output`() {
        val changes = extractTrustedFolderChanges(
            listOf(
                tool(
                    "1",
                    "trusted_folder_rename",
                    """{"path":"notes/old.md","new_name":"new.md"}""",
                    textOutput("""{"action":"renamed","path":"notes/new.md"}"""),
                )
            )
        )
        assertEquals(1, changes.size)
        assertEquals("notes/new.md", changes[0].path)
        assertEquals(FileChangeStatus.EDITED, changes[0].status)
    }

    @Test
    fun `move is EDITED with target path from output`() {
        val changes = extractTrustedFolderChanges(
            listOf(
                tool(
                    "1",
                    "trusted_folder_move",
                    """{"path":"notes/a.md","target_dir":"archive"}""",
                    textOutput("""{"action":"moved","path":"archive/a.md"}"""),
                )
            )
        )
        assertEquals(1, changes.size)
        assertEquals("archive/a.md", changes[0].path)
        assertEquals(FileChangeStatus.EDITED, changes[0].status)
    }

    @Test
    fun `edit and delete with error output are not reported`() {
        val changes = extractTrustedFolderChanges(
            listOf(
                tool("1", "trusted_folder_edit", """{"path":"notes/a.md"}""", textOutput("""{"error":true,"message":"不存在"}""")),
                tool("2", "trusted_folder_delete", """{"path":"notes/b.md"}""", textOutput("""{"error":"无权限"}""")),
            )
        )
        assertEquals(0, changes.size)
    }

    @Test
    fun `workspace edit with error output is not reported`() {
        val changes = extractFileChanges(
            listOf(tool("1", "workspace_edit_file", """{"path":"/workspace/a.txt"}""", textOutput("""{"error":true,"message":"old_text not found"}""")))
        )
        assertEquals(0, changes.size)
    }

    @Test
    fun `relative input path prefers resolved absolute path from output`() {
        // P0 后工具入参允许相对 cwd：提取应优先取输出的 resolved 绝对路径，
        // 否则定位/预览会把相对路径误判成 LINUX 区导致 "Path does not exist: <首段目录>"
        val changes = extractFileChanges(
            listOf(
                tool(
                    "1",
                    "workspace_write_file",
                    """{"path":"txt/todo.md"}""",
                    textOutput("""{"path":"/workspace/txt/todo.md","changeStatus":"added"}"""),
                )
            )
        )
        assertEquals(1, changes.size)
        assertEquals("/workspace/txt/todo.md", changes[0].path)
        assertEquals(FileChangeStatus.ADDED, changes[0].status)
    }

    @Test
    fun `relative input path falls back to input when output has no path`() {
        val changes = extractFileChanges(
            listOf(tool("1", "workspace_edit_file", """{"path":"docs/a.txt"}""", textOutput("""{"ok":true}""")))
        )
        assertEquals(1, changes.size)
        assertEquals("docs/a.txt", changes[0].path)
        assertEquals(FileChangeStatus.EDITED, changes[0].status)
    }

    @Test
    fun `non-json output text falls back to input path`() {
        val changes = extractFileChanges(
            listOf(tool("1", "workspace_write_file", """{"path":"/workspace/c.txt"}""", textOutput("plain text")))
        )
        assertEquals(1, changes.size)
        assertEquals("/workspace/c.txt", changes[0].path)
        assertEquals(FileChangeStatus.ADDED, changes[0].status)
    }

    @Test
    fun `workspace shell with error word in stdout is still parsed by diff keys`() {
        // workspace_shell 的 stdout 是命令自由输出，可能含 "error" 字样，不能按 error 键过滤
        val output = """{"addedFiles":["a.txt"],"stdout":"error: build failed","exitCode":1}"""
        val changes = extractFileChanges(
            listOf(tool("1", "workspace_shell", """{"command":"build"}""", textOutput(output)))
        )
        assertEquals(1, changes.size)
        assertEquals(FileChangeStatus.ADDED, changes[0].status)
    }

    @Test
    fun `delete_note is reported with title from output`() {
        val items = extractStudyItems(
            listOf(
                tool(
                    "1",
                    "delete_note",
                    """{"id":"x","confirm_title":"工作笔记"}""",
                    textOutput("""{"deleted":true,"id":"x","title":"工作笔记","type":"note"}"""),
                )
            )
        )
        assertEquals(1, items.size)
        assertEquals("已删笔记: 工作笔记", items[0].label)
    }

    @Test
    fun `update_note without title in input still reported via output`() {
        // 只改 content/tags 不传 title 的 update：输出带 title 即可显示
        val items = extractStudyItems(
            listOf(
                tool(
                    "1",
                    "update_note",
                    """{"id":"x","content":"new content"}""",
                    textOutput("""{"updated":true,"id":"x","title":"学习笔记"}"""),
                )
            )
        )
        assertEquals(1, items.size)
        assertEquals("笔记: 学习笔记", items[0].label)
    }

    @Test
    fun `delete with error output not reported in study items`() {
        val items = extractStudyItems(
            listOf(
                tool("1", "delete_note", """{"id":"x","confirm_title":"y"}""", textOutput("""{"error":true,"message":"确认标题不匹配"}"""))
            )
        )
        assertEquals(0, items.size)
    }
}
