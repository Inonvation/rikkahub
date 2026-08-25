package me.rerere.rikkahub.ui.components.message.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具审批「目的说明」与 Tool part 序列化兼容测试。
 */
class ToolApprovalPurposeTest {

    @Test
    fun configWriteShowsPathAndApplyFlag() {
        val args = buildJsonObject {
            put("path", "config/providers.json")
            put("content", "{\"schemaVersion\":1}")
            put("applyToSettings", true)
        }
        val purpose = toolApprovalPurpose(
            toolName = "config_write",
            description = "Write a config file under agent/",
            arguments = args,
        )
        assertTrue(purpose.contains("修改统一配置文件"))
        assertTrue(purpose.contains("config/providers.json"))
        assertTrue(purpose.contains("并同步到应用设置"))
    }

    @Test
    fun modelAddShowsModelId() {
        val args = buildJsonObject { put("modelId", "gpt-4o-vision") }
        val purpose = toolApprovalPurpose(
            toolName = "model_add",
            description = "Add a model with full basic settings",
            arguments = args,
        )
        assertTrue(purpose.contains("添加新模型"))
        assertTrue(purpose.contains("gpt-4o-vision"))
    }

    @Test
    fun unmappedToolFallsBackToDescription() {
        val purpose = toolApprovalPurpose(
            toolName = "mcp__server__custom_tool",
            description = "Do something on a remote MCP server",
            arguments = buildJsonObject {},
        )
        assertEquals("Do something on a remote MCP server", purpose)
    }

    @Test
    fun toolPartDeserializesWithoutDescriptionField() {
        // 旧格式消息（无 description 字段）必须向后兼容
        val oldJson = """{"toolCallId":"abc","toolName":"config_write","input":"{}"}"""
        val part = JsonInstant.decodeFromString<UIMessagePart.Tool>(oldJson)
        assertEquals("", part.description)
        assertEquals("config_write", part.toolName)
    }

    @Test
    fun toolPartRoundTripsDescription() {
        val part = UIMessagePart.Tool(
            toolCallId = "abc",
            toolName = "config_write",
            description = "Write a config file",
            input = "{}",
        )
        val json = JsonInstant.encodeToString(UIMessagePart.Tool.serializer(), part)
        val decoded = JsonInstant.decodeFromString<UIMessagePart.Tool>(json)
        assertEquals("Write a config file", decoded.description)
    }
}
