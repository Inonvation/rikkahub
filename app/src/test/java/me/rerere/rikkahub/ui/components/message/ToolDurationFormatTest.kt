package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDurationFormatTest {
    private fun toolWithOutput(vararg outputs: String) = UIMessagePart.Tool(
        toolCallId = "t",
        toolName = "workspace_shell",
        input = """{"command":"echo hi"}""",
        output = outputs.map { UIMessagePart.Text(it) },
    )

    @Test
    fun `seconds keep one decimal`() {
        assertEquals("12.3s", formatToolDuration(12_300))
    }

    @Test
    fun `short minutes round to zero padded seconds`() {
        assertEquals("1m05s", formatToolDuration(65_000))
    }

    @Test
    fun `hours show hours minutes seconds`() {
        assertEquals("1h02m03s", formatToolDuration(3_723_000))
    }

    @Test
    fun `exit code zero is not a failure`() {
        val tool = toolWithOutput("""{"exitCode":0,"stdout":"ok"}""")
        assertFalse(toolFailed(tool))
        assertEquals(0, toolExitCode(tool))
    }

    @Test
    fun `nonzero exit code is a failure with code`() {
        val tool = toolWithOutput("""{"exitCode":1,"stderr":"boom"}""")
        assertTrue(toolFailed(tool))
        assertEquals(1, toolExitCode(tool))
    }

    @Test
    fun `blank error is ignored as failure`() {
        val tool = toolWithOutput("""{"error":null,"stdout":"fine"}""")
        assertFalse(toolFailed(tool))
    }

    @Test
    fun `real error message marks failure`() {
        val tool = toolWithOutput("""{"error":"tool blew up"}""")
        assertTrue(toolFailed(tool))
        assertNull(toolExitCode(tool))
    }

    @Test
    fun `failure is detected across multiple output segments`() {
        val tool = toolWithOutput("""{"stdout":"partial"}""", """{"exitCode":2}""")
        assertTrue(toolFailed(tool))
        assertEquals(2, toolExitCode(tool))
    }
}
