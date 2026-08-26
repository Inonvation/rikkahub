package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.rikkahub.data.ai.mcp.McpTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class McpDispatchToolsTest {

    private val serverId = Uuid.random()
    private val serverId2 = Uuid.random()

    private val tools = listOf(
        Triple(
            serverId,
            "github",
            McpTool(
                name = "search_issues",
                description = "Search GitHub issues".repeat(5),
                inputSchema = InputSchema.Obj(
                    properties = buildJsonObject {
                        put("query", buildJsonObject { put("type", "string") })
                        put("repo", buildJsonObject { put("type", "string") })
                        put("state", buildJsonObject { put("type", "string") })
                    },
                    required = listOf("query"),
                ),
                needsApproval = true,
            ),
        ),
        Triple(
            serverId,
            "github",
            McpTool(name = "get_issue", description = null, needsApproval = false),
        ),
    )

    @Test
    fun `formatMcpToolList groups by server and prints params`() {
        val text = formatMcpToolList(tools, null)
        assertTrue(text.contains("server=github"))
        assertTrue(text.contains("search_issues"))
        assertTrue(text.contains("[params: query,repo,state]"))
        assertTrue(text.contains("get_issue"))
        assertTrue(text.contains("(no description)"))
    }

    @Test
    fun `formatMcpToolList respects server filter`() {
        assertTrue(formatMcpToolList(tools, "github").contains("search_issues"))
        assertTrue(formatMcpToolList(tools, "nope").startsWith("No MCP tools available"))
    }

    @Test
    fun `formatMcpToolList shows server description when present`() {
        val text = formatMcpToolList(
            tools,
            null,
            serverDescriptions = mapOf(serverId to "GitHub repository search and issue management"),
        )
        assertTrue(text.contains("server=github — GitHub repository search and issue management"))
    }

    @Test
    fun `formatMcpToolList shows description with id on name collision`() {
        val s1 = Uuid.random()
        val s2 = Uuid.random()
        val ambiguous = listOf(
            Triple(s1, "github", McpTool(name = "search_issues", description = null, needsApproval = false)),
            Triple(s2, "github", McpTool(name = "get_issue", description = null, needsApproval = false)),
        )
        val text = formatMcpToolList(
            ambiguous,
            null,
            serverDescriptions = mapOf(s2 to "Second github server"),
        )
        assertTrue(text.contains("server=github [id=$s2] — Second github server"))
    }

    @Test
    fun `formatMcpToolList disambiguates same-name servers by id`() {
        val s1 = Uuid.random()
        val s2 = Uuid.random()
        val ambiguous = listOf(
            Triple(s1, "github", McpTool(name = "search_issues", description = null, needsApproval = false)),
            Triple(s2, "github", McpTool(name = "get_issue", description = null, needsApproval = false)),
        )
        val lines = formatMcpToolList(ambiguous, null).lines()
        val headers = lines.filter { it.startsWith("server=github [id=") }
        assertEquals(2, headers.size)
        assertEquals(
            setOf(s1.toString(), s2.toString()),
            headers.map { it.substringAfter("id=").removeSuffix("]") }.toSet(),
        )
        assertTrue(lines.any { it.contains("- search_issues") })
        assertTrue(lines.any { it.contains("- get_issue") })
    }

    @Test
    fun `resolveMcpTool matches by name and id`() {
        val byName = resolveMcpTool(tools, "GitHub", "search_issues")
        assertTrue(byName is McpResolveResult.Found)
        assertEquals("search_issues", (byName as McpResolveResult.Found).tool.third.name)

        val byId = resolveMcpTool(tools, serverId.toString(), "get_issue")
        assertTrue(byId is McpResolveResult.Found)
        assertEquals("get_issue", (byId as McpResolveResult.Found).tool.third.name)

        assertTrue(resolveMcpTool(tools, "github", "missing") is McpResolveResult.NotFound)
    }

    @Test
    fun `resolveMcpTool detects ambiguous server name`() {
        val ambiguous = tools + Triple(
            serverId2,
            "github",
            McpTool(name = "search_issues", description = "other", needsApproval = false),
        )
        val result = resolveMcpTool(ambiguous, "github", "search_issues")
        assertTrue(result is McpResolveResult.Ambiguous)
    }

    @Test
    fun `parseMcpArguments handles object string and missing`() {
        val obj = buildJsonObject { put("q", JsonPrimitive("x")) }
        assertEquals(obj, parseMcpArguments(obj))

        val fromString = parseMcpArguments(JsonPrimitive("""{"a":1}"""))
        assertEquals(1, fromString["a"]?.jsonPrimitive?.content?.toIntOrNull())

        assertTrue(parseMcpArguments(null).isEmpty())
        assertTrue(parseMcpArguments(JsonPrimitive("not-json")).isEmpty())
    }

    @Test
    fun `isValidMcpName accepts names and rejects blanks`() {
        assertTrue(isValidMcpName("search_issues"))
        assertTrue(isValidMcpName("1tool-2"))
        assertFalse(isValidMcpName(""))
        assertFalse(isValidMcpName("has space"))
        assertFalse(isValidMcpName("名称"))
    }

    @Test
    fun `isValidMcpServerName is lenient but rejects blank and control chars`() {
        assertTrue(isValidMcpServerName("github"))
        assertTrue(isValidMcpServerName("My Server"))
        assertTrue(isValidMcpServerName("我的服务器"))
        assertFalse(isValidMcpServerName(""))
        assertFalse(isValidMcpServerName("\u0000"))
    }
}
