package me.rerere.ai.ui

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageSummaryTest {

    @Test
    fun `text message serialized with role prefix and truncation`() {
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("hello world")),
        )
        assertEquals("[USER]\nhello world", message.serializeForSummary())

        val long = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("x".repeat(50))),
        )
        val serialized = long.serializeForSummary(textLimit = 10)
        assertEquals("[ASSISTANT]\n" + "x".repeat(10) + "...[truncated]", serialized)
    }

    @Test
    fun `tool call serialized with input and output preview`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("I'll read the file."),
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "workspace_read_file",
                    input = """{"path":"/src/main.rs"}""",
                    output = listOf(UIMessagePart.Text("fn main() {}")),
                ),
            ),
        )

        val serialized = message.serializeForSummary()

        assertEquals(
            "[ASSISTANT]\nI'll read the file.\n" +
                "[tool workspace_read_file input: {\"path\":\"/src/main.rs\"} -> output: fn main() {}]",
            serialized,
        )
    }

    @Test
    fun `tool input and output truncated at limits`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "workspace_shell",
                    input = "i".repeat(20),
                    output = listOf(UIMessagePart.Text("o".repeat(20))),
                ),
            ),
        )

        val serialized = message.serializeForSummary(toolInputLimit = 5, toolOutputLimit = 8)

        assertEquals(
            "[ASSISTANT]\n[tool workspace_shell input: " + "i".repeat(5) + "...[truncated]" +
                " -> output: " + "o".repeat(8) + "...[truncated]]",
            serialized,
        )
    }

    @Test
    fun `attachments serialized as placeholders and reasoning skipped`() {
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Image(url = "file:///img.png"),
                UIMessagePart.Reasoning(reasoning = "thinking"),
                UIMessagePart.Document(url = "file:///a.pdf", fileName = "report.pdf"),
                UIMessagePart.Video(url = "file:///v.mp4"),
                UIMessagePart.Audio(url = "file:///a.mp3"),
            ),
        )

        assertEquals(
            "[USER]\n[image]\n[document: report.pdf]\n[video]\n[audio]",
            message.serializeForSummary(),
        )
    }

    @Test
    fun `empty text and unexecuted tool produce compact output`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("   "),
                UIMessagePart.Tool(toolCallId = "call-1", toolName = "noop", input = ""),
            ),
        )

        // 空文本不产生空行；工具无入参无结果只留工具名
        assertEquals("[ASSISTANT]\n[tool noop]", message.serializeForSummary())
    }

    @Test
    fun `server tool serialized with status and output`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.ServerTool(
                    toolCallId = "st-1",
                    toolName = "web_search",
                    input = null,
                    output = buildJsonObject {
                        put("hits", JsonPrimitive(2))
                    },
                    status = ServerToolStatus.COMPLETED,
                ),
            ),
        )

        assertEquals(
            "[ASSISTANT]\n[server_tool web_search status=completed -> output: {\"hits\":2}]",
            message.serializeForSummary(),
        )
    }
}
