package me.rerere.ai.ui

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UIMessagePartTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `server tool round trip preserves provider payload`() {
        val part: UIMessagePart = UIMessagePart.ServerTool(
            toolCallId = "srvtoolu_123",
            toolName = "web_search",
            input = buildJsonObject { put("query", "Kotlin serialization") },
            output = buildJsonArray {
                add(buildJsonObject {
                    put("url", "https://example.com")
                    put("encrypted_content", "encrypted")
                })
            },
            status = ServerToolStatus.COMPLETED,
            metadata = buildJsonObject { put("provider", "claude") },
        )

        val encoded = json.encodeToString(part)
        val encodedObject = json.parseToJsonElement(encoded).jsonObject
        val restored = json.decodeFromString<UIMessagePart>(encoded) as UIMessagePart.ServerTool

        assertEquals("server_tool", encodedObject["type"]?.jsonPrimitive?.content)
        assertEquals("completed", encodedObject["status"]?.jsonPrimitive?.content)
        assertEquals(part, restored)
        assertTrue(restored.isFinished)
    }

    @Test
    fun `server tool tracks in progress state`() {
        val part = UIMessagePart.ServerTool(
            toolCallId = "ws_123",
            toolName = "web_search",
            status = ServerToolStatus.IN_PROGRESS,
        )

        assertEquals(ServerToolStatus.IN_PROGRESS, part.status)
        assertFalse(part.isFinished)
    }

    @Test
    fun `tool timing fields round trip`() {
        val startedAt = Clock.System.now()
        val finishedAt = startedAt + 5.seconds
        val startedAtMs = 1_000L
        val finishedAtMs = 6_000L
        val part: UIMessagePart = UIMessagePart.Tool(
            toolCallId = "tool_1",
            toolName = "workspace_shell",
            input = """{"command":"sleep 1"}""",
            startedAt = startedAt,
            finishedAt = finishedAt,
            startedAtMs = startedAtMs,
            finishedAtMs = finishedAtMs,
        )

        val encoded = json.encodeToString(part)
        val restored = json.decodeFromString<UIMessagePart>(encoded) as UIMessagePart.Tool

        assertEquals(part, restored)
        assertFalse(restored.isRunning)
        assertEquals(5_000L, restored.durationMs)
    }

    @Test
    fun `tool running state requires start without finish`() {
        val startedAt = Clock.System.now()
        val running = UIMessagePart.Tool(
            toolCallId = "tool_2",
            toolName = "workspace_shell",
            input = """{"command":"sleep 5"}""",
            startedAt = startedAt,
            startedAtMs = 1_000L,
        )

        assertTrue(running.isRunning)
    }

    @Test
    fun `tool duration falls back to wall clock when monotonic is missing`() {
        val startedAt = Clock.System.now()
        val finishedAt = startedAt + 5.seconds
        val tool = UIMessagePart.Tool(
            toolCallId = "tool_3",
            toolName = "workspace_shell",
            input = """{"command":"sleep 1"}""",
            startedAt = startedAt,
            finishedAt = finishedAt,
        )

        assertEquals(5_000L, tool.durationMs)
    }

    @Test
    fun `tool queued state before start`() {
        val queuedAt = Clock.System.now()
        val queuedAtMs = 1_000L
        val queued = UIMessagePart.Tool(
            toolCallId = "tool_q",
            toolName = "workspace_shell",
            input = """{"command":"sleep 1"}""",
            queuedAt = queuedAt,
            queuedAtMs = queuedAtMs,
        )

        assertTrue(queued.hasQueued)
        assertTrue(queued.isQueued)
        assertFalse(queued.hasStarted)
        assertFalse(queued.isRunning)
        assertFalse(queued.isFinished)
    }

    @Test
    fun `tool queued state cleared once started`() {
        val queuedAt = Clock.System.now()
        val startedAt = queuedAt + 1.seconds
        val started = UIMessagePart.Tool(
            toolCallId = "tool_q2",
            toolName = "workspace_shell",
            input = """{"command":"sleep 1"}""",
            queuedAt = queuedAt,
            startedAt = startedAt,
        )

        // 一旦开始执行，即使保留 queuedAt 也不再视为等待中
        assertTrue(started.hasQueued)
        assertFalse(started.isQueued)
        assertTrue(started.hasStarted)
    }

    @Test
    fun `tool queued fields round trip and merge preserve`() {
        val queuedAt = Clock.System.now()
        val a = UIMessagePart.Tool(
            toolCallId = "tool_q3",
            toolName = "workspace_shell",
            input = """{"command":"sleep 1"}""",
            queuedAt = queuedAt,
            queuedAtMs = 1234L,
        )
        val encoded = json.encodeToString<UIMessagePart>(a)
        val restored = json.decodeFromString<UIMessagePart>(encoded) as UIMessagePart.Tool

        assertEquals(a, restored)
        assertEquals(queuedAt, restored.queuedAt)
        assertEquals(1234L, restored.queuedAtMs)

        val merged = a.merge(UIMessagePart.Tool(toolCallId = "tool_q3", toolName = "", input = "{}"))
        assertEquals(queuedAt, merged.queuedAt)
        assertEquals(1234L, merged.queuedAtMs)
    }
}
