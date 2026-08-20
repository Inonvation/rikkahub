package me.rerere.rikkahub.data.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.model.Capability
import me.rerere.rikkahub.data.model.ChatModePolicy
import me.rerere.rikkahub.data.model.CustomModeConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomModeSerializerTest {

    @Test
    fun exportsCustomModeWithCapabilities() {
        val mode = CustomModeConfig(
            id = "mode-1",
            name = "测试模式",
            description = "测试描述",
            policy = ChatModePolicy(capabilities = setOf(Capability.WORKSPACE, Capability.MCP_USE)),
        )

        val obj = Json.parseToJsonElement(CustomModeSerializer.exportToJson(mode)).jsonObject

        assertEquals("custom_mode", obj["type"]?.jsonPrimitive?.contentOrNull)
        val data = obj["data"]?.jsonObject
        assertEquals("mode-1", data?.get("id")?.jsonPrimitive?.contentOrNull)
        assertEquals("测试模式", data?.get("name")?.jsonPrimitive?.contentOrNull)
        val capabilities = data
            ?.get("policy")
            ?.jsonObject
            ?.get("capabilities")
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.toSet()
        assertEquals(setOf("WORKSPACE", "MCP_USE"), capabilities)
    }
}
