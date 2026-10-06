package me.rerere.rikkahub.data.datastore.migration

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Capability
import me.rerere.rikkahub.data.model.ChatMode
import me.rerere.rikkahub.data.model.ChatModePolicy
import me.rerere.rikkahub.data.model.CustomModeConfig
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 已移除功能的持久化兼容：旧数据里残留的已删除取值必须被清理掉，且清理后的数据能用
 * 现行序列化器正常解码（助手/自定义模式不丢失）。覆盖两类：
 * - 「设备能力」：助手 localTools 的 device_doctor/storage_cleaner/freeze_apps、
 *   模式 capabilities 的 DEVICE_TOOLS
 * - 「HTML 转 Markdown」工具：助手 localTools 的 html_to_markdown
 *
 * 测试数据的构造方式：先用现行模型编码合法 JSON，再注入已删除的取值——
 * 等价于旧版本落盘的 JSON 形态。
 */
class RemovedFeatureCleanupTest {

    // ---- assistants ----

    @Test
    fun cleanAssistantsJson_removesRemovedToolsAndKeepsTheRest() {
        val dailyChatId = Uuid.parse("11111111-1111-1111-1111-111111111111")
        val tutorId = Uuid.parse("22222222-2222-2222-2222-222222222222")
        val base = JsonInstant.encodeToString(
            listOf(
                Assistant(id = dailyChatId, name = "日常聊天", localTools = listOf(LocalToolOption.TimeInfo)),
                Assistant(id = tutorId, name = "导师", localTools = listOf(LocalToolOption.Clipboard)),
            )
        )
        // 旧数据形态：第一个助手的 localTools 里混合了已删除的工具项
        val raw = withInjectedLocalTools(
            raw = base,
            index = 0,
            names = listOf(
                "device_doctor", "storage_cleaner", "freeze_apps", "html_to_markdown", "time_info",
            ),
        )
        assertTrue(raw.contains("device_doctor"))
        assertTrue(raw.contains("html_to_markdown"))

        val cleaned = RemovedFeatureCleanup.cleanAssistantsJson(raw)

        assertFalse(cleaned.contains("device_doctor"))
        assertFalse(cleaned.contains("storage_cleaner"))
        assertFalse(cleaned.contains("freeze_apps"))
        assertFalse(cleaned.contains("html_to_markdown"))
        val decoded = JsonInstant.decodeFromString<List<Assistant>>(cleaned)
        assertEquals(2, decoded.size)
        // 助手本体（id/name）与其余本地工具原样保留
        assertEquals(dailyChatId, decoded[0].id)
        assertEquals("日常聊天", decoded[0].name)
        assertEquals(listOf(LocalToolOption.TimeInfo), decoded[0].localTools)
        // 未被注入的助手不受影响
        assertEquals(tutorId, decoded[1].id)
        assertEquals(listOf(LocalToolOption.Clipboard), decoded[1].localTools)
    }

    @Test
    fun cleanAssistantsJson_withoutRemovedValues_returnsSameString() {
        val raw = JsonInstant.encodeToString(
            listOf(Assistant(name = "日常聊天", localTools = listOf(LocalToolOption.TimeInfo)))
        )
        // 顺带锚定现行序列化形态可正常往返（密封类判别键断言）
        assertEquals(
            listOf(LocalToolOption.TimeInfo),
            JsonInstant.decodeFromString<List<Assistant>>(raw).single().localTools
        )
        assertSame(raw, RemovedFeatureCleanup.cleanAssistantsJson(raw))
    }

    @Test
    fun cleanAssistantsJson_withMalformedJson_returnsOriginal() {
        val raw = """{"localTools": ["device_doctor""""
        assertEquals(raw, RemovedFeatureCleanup.cleanAssistantsJson(raw))
    }

    // ---- customModes ----

    @Test
    fun cleanCustomModesJson_removesDeviceCapabilityAndKeepsTheRest() {
        val base = JsonInstant.encodeToString(
            listOf(
                CustomModeConfig(
                    name = "研究模式",
                    policy = ChatModePolicy(capabilities = setOf(Capability.WORKSPACE, Capability.SKILL_ADMIN)),
                )
            )
        )
        val raw = withInjectedModeCapability(base, "DEVICE_TOOLS")
        assertTrue(raw.contains("DEVICE_TOOLS"))

        val cleaned = RemovedFeatureCleanup.cleanCustomModesJson(raw)

        assertFalse(cleaned.contains("DEVICE_TOOLS"))
        val decoded = JsonInstant.decodeFromString<List<CustomModeConfig>>(cleaned)
        assertEquals(1, decoded.size)
        assertEquals("研究模式", decoded[0].name)
        assertEquals(setOf(Capability.WORKSPACE, Capability.SKILL_ADMIN), decoded[0].policy.capabilities)
    }

    // ---- builtinModeOverrides ----

    @Test
    fun cleanBuiltinModeOverridesJson_removesDeviceCapabilityAndKeepsTheRest() {
        val base = JsonInstant.encodeToString(
            mapOf(ChatMode.CREATIVE to ChatModePolicy(capabilities = setOf(Capability.WORKSPACE)))
        )
        val raw = withInjectedModeCapability(base, "DEVICE_TOOLS")
        assertTrue(raw.contains("DEVICE_TOOLS"))

        val cleaned = RemovedFeatureCleanup.cleanBuiltinModeOverridesJson(raw)

        assertFalse(cleaned.contains("DEVICE_TOOLS"))
        val decoded = JsonInstant.decodeFromString<Map<ChatMode, ChatModePolicy>>(cleaned)
        assertEquals(setOf(Capability.WORKSPACE), decoded.getValue(ChatMode.CREATIVE).capabilities)
    }

    // ---- 清理必要性锚点 ----

    @Test
    fun removedValuesBreakDecodeWithoutCleanup() {
        // 锚定本清理存在的理由：删除枚举/密封类条目后，含残留取值的旧数据无法解码
        // （自定义模式整表回退为空、助手被逐条隔离丢弃）。
        // 若未来序列化库放宽为容忍未知枚举/判别值，本测试会失败——可据此评估是否仍需清理。
        val modes = withInjectedModeCapability(
            raw = researchModeJson(),
            capability = "DEVICE_TOOLS",
        )
        assertTrue(
            "含 DEVICE_TOOLS 的模式 JSON 应无法直接解码",
            runCatching { JsonInstant.decodeFromString<List<CustomModeConfig>>(modes) }.isFailure,
        )

        for (removedTool in listOf("device_doctor", "html_to_markdown")) {
            val assistants = withInjectedLocalTools(
                raw = JsonInstant.encodeToString(
                    listOf(Assistant(name = "日常聊天", localTools = listOf(LocalToolOption.TimeInfo)))
                ),
                index = 0,
                names = listOf(removedTool),
            )
            assertTrue(
                "含 $removedTool 的助手 JSON 应无法直接解码",
                runCatching { JsonInstant.decodeFromString<List<Assistant>>(assistants) }.isFailure,
            )
        }
    }

    // ---- SettingsJsonMigrator（备份恢复 / 云同步路径）----

    @Test
    fun settingsJsonMigrator_cleansRemovedValuesFromWholeSettingsJson() {
        val assistants = withInjectedLocalTools(
            raw = JsonInstant.encodeToString(
                listOf(Assistant(name = "日常聊天", localTools = listOf(LocalToolOption.TimeInfo)))
            ),
            index = 0,
            names = listOf("device_doctor", "freeze_apps", "html_to_markdown", "time_info"),
        )
        val customModes = withInjectedModeCapability(
            raw = researchModeJson(),
            capability = "DEVICE_TOOLS",
        )
        val overrides = withInjectedModeCapability(
            raw = JsonInstant.encodeToString(
                mapOf(ChatMode.CREATIVE to ChatModePolicy(capabilities = setOf(Capability.WORKSPACE)))
            ),
            capability = "DEVICE_TOOLS",
        )
        val settingsJson = JsonInstant.encodeToString(
            JsonObject(
                mapOf(
                    "assistants" to JsonInstant.parseToJsonElement(assistants),
                    "customModes" to JsonInstant.parseToJsonElement(customModes),
                    "builtinModeOverrides" to JsonInstant.parseToJsonElement(overrides),
                )
            )
        )

        val migrated = SettingsJsonMigrator.migrate(settingsJson)

        assertFalse(migrated.contains("DEVICE_TOOLS"))
        assertFalse(migrated.contains("device_doctor"))
        assertFalse(migrated.contains("html_to_markdown"))
        val root = JsonInstant.parseToJsonElement(migrated).jsonObject
        val decodedAssistants = JsonInstant.decodeFromString<List<Assistant>>(root.getValue("assistants").toString())
        assertEquals(listOf(LocalToolOption.TimeInfo), decodedAssistants.single().localTools)
        val decodedModes = JsonInstant.decodeFromString<List<CustomModeConfig>>(root.getValue("customModes").toString())
        assertEquals(setOf(Capability.WORKSPACE), decodedModes.single().policy.capabilities)
        val decodedOverrides = JsonInstant.decodeFromString<Map<ChatMode, ChatModePolicy>>(
            root.getValue("builtinModeOverrides").toString()
        )
        assertEquals(setOf(Capability.WORKSPACE), decodedOverrides.getValue(ChatMode.CREATIVE).capabilities)
    }

    // ---- helpers ----

    /** 单个「研究模式」（capabilities = WORKSPACE）的序列化 JSON */
    private fun researchModeJson(): String = JsonInstant.encodeToString(
        listOf(
            CustomModeConfig(
                name = "研究模式",
                policy = ChatModePolicy(capabilities = setOf(Capability.WORKSPACE)),
            )
        )
    )

    /** 把 [raw]（List<Assistant>）中第 [index] 个助手的 localTools 替换为 [names]。
     *  LocalToolOption 是密封类，规范序列化形态为 {"type":"<name>"} 对象。 */
    private fun withInjectedLocalTools(raw: String, index: Int, names: List<String>): String {
        val array = JsonInstant.parseToJsonElement(raw) as JsonArray
        val updated = array.mapIndexed { i, item ->
            if (i != index) {
                item
            } else {
                val obj = item.jsonObject
                JsonObject(
                    obj.toMutableMap().apply {
                        this["localTools"] = JsonArray(
                            names.map { name -> JsonObject(mapOf("type" to JsonPrimitive(name))) }
                        )
                    }
                )
            }
        }
        return JsonInstant.encodeToString(JsonArray(updated))
    }

    /**
     * 给 [raw] 中每个模式的 `policy.capabilities`（customModes 数组元素或
     * builtinModeOverrides 对象值）追加一个已删除的能力名。
     */
    private fun withInjectedModeCapability(raw: String, capability: String): String {
        val element = JsonInstant.parseToJsonElement(raw)
        val policies = when (element) {
            is JsonArray -> element.map { it.jsonObject.getValue("policy").jsonObject }
            is JsonObject -> element.values.map { it.jsonObject }
            else -> error("unexpected shape")
        }
        val updatedPolicies = policies.map { policy ->
            JsonObject(
                policy.toMutableMap().apply {
                    this["capabilities"] = JsonArray(
                        (policy.getValue("capabilities") as JsonArray) + JsonPrimitive(capability)
                    )
                }
            )
        }
        val updated = when (element) {
            is JsonArray -> JsonArray(
                element.mapIndexed { i, item ->
                    JsonObject(item.jsonObject.toMutableMap().apply { this["policy"] = updatedPolicies[i] })
                }
            )

            is JsonObject -> JsonObject(
                element.keys.mapIndexed { i, key -> key to updatedPolicies[i] }.toMap()
            )
        }
        return JsonInstant.encodeToString(updated)
    }
}
