package me.rerere.rikkahub.service

import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.AsyncTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class LiveUpdateStatusTest {

    private fun tool(
        name: String = "workspace_shell",
        input: String = "{}",
        output: List<UIMessagePart> = emptyList(),
        started: Boolean = false,
        finished: Boolean = false,
    ): UIMessagePart.Tool {
        val now = Clock.System.now()
        return UIMessagePart.Tool(
            toolCallId = Uuid.random().toString(),
            toolName = name,
            input = input,
            output = output,
            startedAt = if (started) now else null,
            finishedAt = if (finished) now else null,
        )
    }

    @Test
    fun `running tool beats text`() {
        val status = determineLiveUpdateStatus(
            listOf(
                UIMessagePart.Text("I will run it now"),
                tool(started = true),
            )
        )

        assertEquals(LiveUpdateKind.TOOL, status.kind)
        assertEquals("workspace_shell", status.toolName)
    }

    @Test
    fun `queued tool counts as tool`() {
        val status = determineLiveUpdateStatus(listOf(tool()))

        assertEquals(LiveUpdateKind.TOOL, status.kind)
    }

    @Test
    fun `finished parallel tool does not mask running tool`() {
        val completed = tool(
            name = "workspace_read_file",
            started = true,
            finished = true,
            output = listOf(UIMessagePart.Text("{}")),
        )
        val running = tool(name = "workspace_shell", started = true)

        val status = determineLiveUpdateStatus(listOf(completed, running))

        assertEquals(LiveUpdateKind.TOOL, status.kind)
        assertEquals("workspace_shell", status.toolName)
    }

    @Test
    fun `async shell still running is reported as tool`() {
        val asyncTool = tool(
            name = "workspace_shell_async",
            started = true,
            finished = true,
            output = listOf(UIMessagePart.Text("""{"taskId":"abc","status":"running"}""")),
        )

        val status = determineLiveUpdateStatus(
            listOf(asyncTool),
            asyncTaskStateProvider = { taskId ->
                if (taskId == "abc") AsyncTaskState.RUNNING else null
            },
        )

        assertEquals(LiveUpdateKind.TOOL, status.kind)
        assertEquals("workspace_shell_async", status.toolName)
    }

    @Test
    fun `finished async shell falls back to writing`() {
        val asyncTool = tool(
            name = "workspace_shell_async",
            started = true,
            finished = true,
            output = listOf(UIMessagePart.Text("""{"taskId":"abc","status":"running"}""")),
        )

        val status = determineLiveUpdateStatus(
            listOf(asyncTool, UIMessagePart.Text("Done")),
            asyncTaskStateProvider = { taskId ->
                if (taskId == "abc") AsyncTaskState.SUCCEEDED else null
            },
        )

        assertEquals(LiveUpdateKind.WRITING, status.kind)
    }

    @Test
    fun `server tool in progress is reported as tool`() {
        val status = determineLiveUpdateStatus(
            listOf(
                UIMessagePart.Text("searching"),
                UIMessagePart.ServerTool(
                    toolCallId = "server-1",
                    toolName = "web_search",
                    status = ServerToolStatus.IN_PROGRESS,
                ),
            )
        )

        assertEquals(LiveUpdateKind.TOOL, status.kind)
        assertEquals("web_search", status.toolName)
    }

    @Test
    fun `unfinished reasoning beats text`() {
        val status = determineLiveUpdateStatus(
            listOf(
                UIMessagePart.Reasoning("thinking", finishedAt = null),
                UIMessagePart.Text("partial"),
            )
        )

        assertEquals(LiveUpdateKind.THINKING, status.kind)
    }

    @Test
    fun `thinking records wall clock start anchor`() {
        val createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000)
        val status = determineLiveUpdateStatus(
            listOf(
                UIMessagePart.Reasoning("deep thought", createdAt = createdAt, finishedAt = null),
                UIMessagePart.Text("partial"),
            )
        )

        assertEquals(LiveUpdateKind.THINKING, status.kind)
        assertEquals(1_700_000_000_000L, status.thinkingStartedEpochMs)
    }

    @Test
    fun `finished reasoning maps to writing without anchor`() {
        val now = Clock.System.now()
        val status = determineLiveUpdateStatus(
            listOf(
                UIMessagePart.Reasoning("done thinking", createdAt = now, finishedAt = now),
                UIMessagePart.Text("hello"),
            )
        )

        assertEquals(LiveUpdateKind.WRITING, status.kind)
        assertNull(status.thinkingStartedEpochMs)
    }

    @Test
    fun `running tool beats unfinished reasoning`() {
        val status = determineLiveUpdateStatus(
            listOf(
                UIMessagePart.Reasoning("planning", finishedAt = null),
                tool(started = true),
            )
        )

        assertEquals(LiveUpdateKind.TOOL, status.kind)
    }

    @Test
    fun `text maps to writing`() {
        val status = determineLiveUpdateStatus(listOf(UIMessagePart.Text("hello")))

        assertEquals(LiveUpdateKind.WRITING, status.kind)
    }

    @Test
    fun `empty parts map to default`() {
        val status = determineLiveUpdateStatus(emptyList())

        assertEquals(LiveUpdateKind.DEFAULT, status.kind)
    }

    @Test
    fun `running tool records monotonic start anchor`() {
        val status = determineLiveUpdateStatus(
            listOf(
                UIMessagePart.Tool(
                    toolCallId = "t-1",
                    toolName = "workspace_shell",
                    input = "{}",
                    startedAtMs = 12_345L,
                )
            )
        )

        assertEquals(LiveUpdateKind.TOOL, status.kind)
        assertEquals(12_345L, status.toolStartedAtMs)
    }

    @Test
    fun `elapsed formats as m ss and h mm ss`() {
        assertEquals("0:00", formatElapsed(0))
        assertEquals("0:42", formatElapsed(42_000))
        assertEquals("12:05", formatElapsed(725_000))
        assertEquals("1:02:03", formatElapsed(3_723_000))
        // 负值（时钟异常）不应出现负数展示
        assertEquals("0:00", formatElapsed(-5_000))
    }

    @Test
    fun `chip text carries bare elapsed when anchored`() {
        assertEquals("Tool 0:42", chipWithElapsed("Tool", 42_000))
        assertEquals("思考中 1:02:03", chipWithElapsed("思考中", 3_723_000))
    }

    @Test
    fun `chip text stays bare label without anchor`() {
        assertEquals("Tool", chipWithElapsed("Tool", null))
    }
}
