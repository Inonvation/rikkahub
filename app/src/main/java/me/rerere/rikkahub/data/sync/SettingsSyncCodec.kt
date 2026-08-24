package me.rerere.rikkahub.data.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.utils.JsonInstant

/**
 * settings 同步白名单编解码器。
 *
 * `Settings` 内含 provider API key、webDavConfig/s3Config 凭据、同步配置自身等敏感字段，
 * 绝不能整包上云。本类以 JSON 层面操作，保证：
 * 1. [toSyncableJson]：只保留 [ALLOWLIST] 内的顶层 key，并对列表元素递归剔除密钥类字段。
 * 2. [fromSyncableJson]：远端非敏感配置覆盖本地；白名单之外的 key 与所有密钥字段回落到本机当前值。
 *
 * 默认策略：白名单之外一律不同步（explicit allowlist，比黑名单更安全）。
 */
object SettingsSyncCodec {

    /** 允许同步的 Settings 顶层字段名（其余一律剔除）。 */
    val ALLOWLIST: Set<String> = setOf(
        // UI / 主题
        "dynamicColor",
        "themeId",
        "customThemes",
        "developerMode",
        "displaySetting",
        // 模型选择 / 提示词
        "favoriteModels",
        "chatModelId",
        "fastModelId",
        "titleModelId",
        "imageGenerationModelId",
        "titlePrompt",
        "translateModeId",
        "translatePrompt",
        "translateThinkingBudget",
        "enableSuggestion",
        "suggestionModelId",
        "suggestionPrompt",
        "ocrModelId",
        "ocrPrompt",
        "compressModelId",
        "compressPrompt",
        "autoCompressEnabled",
        "autoCompressThreshold",
        "embeddingModelId",
        "rerankModelId",
        "promptOptimizeModelId",
        "promptOptimizePrompt",
        "promptOptimizePromptsByScene",
        "promptOptimizeThinkingBudget",
        "promptOptimizeThinkingBudgetByScene",
        "promptOptimizeDepthByScene",
        "assistantId",
        // Provider（apiKey 等密钥字段由 [SECRET_KEYS] 剔除）
        "providers",
        "assistants",
        "assistantTags",
        // 搜索
        "searchServices",
        "searchCommonOptions",
        "searchServiceSelected",
        "enabledSearchServiceIds",
        // MCP
        "mcpServers",
        "enableMcpManager",
        // TTS / ASR
        "ttsProviders",
        "selectedTTSProviderId",
        "defaultTTSPlaybackSpeed",
        "asrProviders",
        "selectedASRProviderId",
        // 注入 / 知识
        "modeInjections",
        "lorebooks",
        "quickMessages",
        "skillOrder",
        // WebServer（访问密码除外）
        "webServerEnabled",
        "webServerPort",
        "webServerJwtEnabled",
        "webServerLocalhostOnly",
        // 学习工具
        "pdfOcrEnabled",
        "studyEditEnabled",
        "studyDeleteEnabled",
        "studyDeleteApprovalEnabled",
        "studyStatsEnabled",
        "studyToolApprovalOverrides",
        // 子代理
        "enableTodoList",
        "enableSubAgent",
        "subAgentModelId",
        "subAgentTimeoutSeconds",
        "subAgentMaxConcurrent",
        "subAgentAllowGuidance",
        "subAgentMaxRetries",
        "subAgentMaxTokens",
        "enableAgentBehaviorPrompt",
        // AI 请求重试
        "aiRequestMaxRetries",
        // 费用
        "costCurrency",
        // 能力模式
        "defaultMode",
        "customModes",
        "builtinModeOverrides",
        "costUsdCnyRate",
        "modelPricingOverrides",
    )

    /** 递归剔除的密钥类字段名（出现在列表元素 / 嵌套对象中一律移除）。 */
    private val SECRET_KEYS: Set<String> = setOf(
        "apiKey",
        "privateKey",
        "serviceAccountEmail",
        "customHeaders",
        "headers",
        "oauth",
        "accessToken",
        "refreshToken",
        "clientSecret",
        "client_id",
        "client_secret",
    )

    /** 序列化为可同步 JSON：顶层 allowlist 过滤 + 递归剔除密钥字段。 */
    fun toSyncableJson(settings: Settings): String {
        val root = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(settings)).jsonObject
        val filtered = root.filterKeys { it in ALLOWLIST }
        return JsonInstant.encodeToString(sanitize(JsonObject(filtered)))
    }

    /** 远端 JSON 合并回 [local]：白名单 key 生效，白名单外与密钥字段保留 [local] 现值。 */
    fun fromSyncableJson(json: String, local: Settings): Settings {
        val remote = JsonObject(JsonInstant.parseToJsonElement(json).jsonObject.filterKeys { it in ALLOWLIST })
        val localRoot = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(local)).jsonObject
        val merged = mergeElement(remote, localRoot) as JsonObject
        return JsonInstant.decodeFromString(JsonInstant.encodeToString(merged))
    }

    /** 递归剔除 SECRET_KEYS 中的字段。 */
    private fun sanitize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.filterKeys { it !in SECRET_KEYS }.mapValues { (_, v) -> sanitize(v) }
        )

        is JsonArray -> JsonArray(element.map { sanitize(it) })
        else -> element
    }

    /**
     * 将 [remote]（已剔除密钥的远端配置）合并到 [local]（完整本机配置）：
     * - 密钥字段、白名单外的 key：保留 [local] 值。
     * - 列表元素：按对象 `id` 对齐，远端元素覆盖本地元素但保留本地密钥字段。
     * - 其余字段：远端覆盖本地。
     */
    private fun mergeElement(remote: JsonElement, local: JsonElement): JsonElement {
        return when {
            remote is JsonObject && local is JsonObject -> {
                val keys = remote.keys + local.keys
                JsonObject(
                    keys.associateWith { key ->
                        val rv = remote[key]
                        val lv = local[key]
                        when {
                            key in SECRET_KEYS -> lv
                            rv == null -> lv
                            lv == null -> rv
                            else -> mergeElement(rv, lv)
                        }!!
                    }
                )
            }

            remote is JsonArray && local is JsonArray -> {
                val localById = local.mapNotNull { el ->
                    (el as? JsonObject)?.get("id")?.let { it to el }
                }.toMap()
                val remoteMapped = remote.map { rel ->
                    if (rel is JsonObject) {
                        val id = rel["id"] as? JsonPrimitive
                        val lcl = id?.let { localById[it] }
                        if (lcl != null) mergeElement(rel, lcl) else rel
                    } else {
                        rel
                    }
                }
                JsonArray(remoteMapped)
            }

            else -> remote
        }
    }
}
