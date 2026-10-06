package me.rerere.rikkahub.data.datastore.migration

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant

private const val TAG = "RemovedDeviceFeature"

/**
 * 「设备能力」功能已整体移除（设备工具族 / ChatMode 的 DEVICE_TOOLS 能力 / 助手「本地工具」中的
 * 设备诊断、存储清理、冻结应用开关）。
 *
 * 已保存的数据里可能残留下列取值，删除对应枚举/密封类条目后会导致整条数据解码失败
 * （助手被逐条隔离丢弃、自定义模式整表回退为空），因此在读取/恢复前先做一次 JSON 级清理：
 *
 * - 助手 JSON（`List<Assistant>`）：过滤 `localTools` 中的 `device_doctor`/`storage_cleaner`/`freeze_apps`
 * - `customModes`（`List<CustomModeConfig>`）：过滤 `policy.capabilities` 中的 `DEVICE_TOOLS`
 * - `builtinModeOverrides`（`Map<ChatMode, ChatModePolicy>`）：同上
 *
 * 只做取值过滤，不做类型解码，因此对坏数据天然安全；未命中时原样返回（快速路径零开销）。
 */
internal object RemovedDeviceFeatureCleanup {

    /** 已移除的 ChatMode 能力名（模式 capabilities 内） */
    private const val REMOVED_CAPABILITY = "DEVICE_TOOLS"

    /** 已移除的助手本地工具名（Assistant.localTools 内） */
    private val REMOVED_LOCAL_TOOLS = setOf("device_doctor", "storage_cleaner", "freeze_apps")

    fun containsRemovedValues(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return raw.contains(REMOVED_CAPABILITY) || REMOVED_LOCAL_TOOLS.any { raw.contains(it) }
    }

    // ---- 字符串级（DataStore V9 迁移用）----

    /** assistants JSON（`List<Assistant>`）：过滤每个助手的 localTools。 */
    fun cleanAssistantsJson(raw: String): String = cleanJson(raw, ::cleanAssistants)

    /** customModes JSON（`List<CustomModeConfig>`）：过滤每个模式的 capabilities。 */
    fun cleanCustomModesJson(raw: String): String = cleanJson(raw, ::cleanCustomModes)

    /** builtinModeOverrides JSON（`Map<ChatMode, ChatModePolicy>`）：过滤每个策略的 capabilities。 */
    fun cleanBuiltinModeOverridesJson(raw: String): String =
        cleanJson(raw, ::cleanBuiltinModeOverrides)

    // ---- JsonElement 级（SettingsJsonMigrator 复用）----

    /** `List<Assistant>`：过滤每个助手的 localTools 数组。 */
    fun cleanAssistants(element: JsonElement): JsonElement {
        val array = element as? JsonArray ?: return element
        var changed = false
        val cleaned = array.map { item ->
            val obj = item as? JsonObject ?: return@map item
            val localTools = obj["localTools"] as? JsonArray ?: return@map item
            val filtered = localTools.filterNot { it.isRemovedLocalTool() }
            if (filtered.size == localTools.size) {
                item
            } else {
                changed = true
                JsonObject(obj.toMutableMap().apply { this["localTools"] = JsonArray(filtered) })
            }
        }
        return if (changed) JsonArray(cleaned) else element
    }

    /** `List<CustomModeConfig>`：过滤每个模式 `policy.capabilities`。 */
    fun cleanCustomModes(element: JsonElement): JsonElement {
        val array = element as? JsonArray ?: return element
        var changed = false
        val cleaned = array.map { item ->
            val obj = item as? JsonObject ?: return@map item
            val policy = obj["policy"] as? JsonObject ?: return@map item
            val cleanedPolicy = cleanPolicyCapabilities(policy)
            if (cleanedPolicy === policy) {
                item
            } else {
                changed = true
                JsonObject(obj.toMutableMap().apply { this["policy"] = cleanedPolicy })
            }
        }
        return if (changed) JsonArray(cleaned) else element
    }

    /** `Map<ChatMode, ChatModePolicy>`：过滤每个策略的 capabilities。 */
    fun cleanBuiltinModeOverrides(element: JsonElement): JsonElement {
        val obj = element as? JsonObject ?: return element
        var changed = false
        val cleaned = obj.mapValues { (_, policy) ->
            val policyObj = policy as? JsonObject ?: return@mapValues policy
            val cleanedPolicy = cleanPolicyCapabilities(policyObj)
            if (cleanedPolicy === policyObj) policy else {
                changed = true
                cleanedPolicy
            }
        }
        return if (changed) JsonObject(cleaned) else element
    }

    // ---- 内部实现 ----

    private fun cleanJson(raw: String, transform: (JsonElement) -> JsonElement): String {
        if (!containsRemovedValues(raw)) return raw
        return try {
            val element = JsonInstant.parseToJsonElement(raw)
            val cleaned = transform(element)
            if (cleaned == element) raw else JsonInstant.encodeToString(cleaned)
        } catch (e: Exception) {
            // 坏数据不阻断读取：保留原样，交给现有幂等解码兜底（runCatching { Log } 保证单测可用）
            runCatching { Log.w(TAG, "clean failed, keep original: ${e.message}") }
            raw
        }
    }

    private fun JsonElement.isRemovedLocalTool(): Boolean {
        // 密封类元素的规范形态是 {"type":"device_doctor"}（type 为判别键）；
        // 同时兼容裸字符串形态，防御历史/手工数据。
        val name = when (this) {
            is JsonObject -> (this["type"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content

            is JsonPrimitive -> takeIf { it.isString }?.content
            else -> null
        }
        return name != null && name in REMOVED_LOCAL_TOOLS
    }

    private fun cleanPolicyCapabilities(policy: JsonObject): JsonObject {
        val capabilities = policy["capabilities"] as? JsonArray ?: return policy
        val filtered = capabilities.filterNot { element ->
            val primitive = element as? JsonPrimitive ?: return@filterNot false
            primitive.isString && primitive.content == REMOVED_CAPABILITY
        }
        if (filtered.size == capabilities.size) return policy
        return JsonObject(policy.toMutableMap().apply { this["capabilities"] = JsonArray(filtered) })
    }
}
