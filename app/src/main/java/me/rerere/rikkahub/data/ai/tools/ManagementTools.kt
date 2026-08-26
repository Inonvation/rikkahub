package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BalanceOption
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.management.ManagementAuditStore
import me.rerere.rikkahub.data.management.ManagementRollbackStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.ModeRefs
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderRepository
import me.rerere.knowledge.KnowledgeManager
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.uuid.Uuid

private val SEARCH_SECRET_KEYS = setOf("apiKey", "password", "privateKey")

private val SEARCH_TYPE_NAMES = setOf(
    "bing_local",
    "zhipu",
    "doubao",
    "tavily",
    "exa",
    "searxng",
    "linkup",
    "brave",
    "metaso",
    "ollama",
    "perplexity",
    "firecrawl",
    "jina",
    "bocha",
    "rikkahub",
    "grok",
    "tinyfish",
    "serper",
    "custom_js",
)

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

private fun JsonObject.float(key: String): Float? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()

private fun JsonObject.uuid(key: String): Uuid? =
    str(key)?.trim()?.takeIf { it.isNotEmpty() }?.let {
        runCatching { Uuid.parse(it) }.getOrNull()
    }

private fun JsonObject.strArray(key: String): List<String>? =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

private fun errorText(message: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text("Error: $message"))

private fun maskSecret(value: String): String =
    if (value.isBlank()) "(not set)" else "***"

// ---- 模型基本设置解析（model_add / model_update 共用，值来自 AI 参数） ----

internal fun parseModelType(value: String): ModelType? = when (value.lowercase()) {
    "chat" -> ModelType.CHAT
    "image" -> ModelType.IMAGE
    "embedding" -> ModelType.EMBEDDING
    "reranking" -> ModelType.RERANKING
    else -> null
}

internal fun parseAbilities(values: List<String>?): List<ModelAbility>? =
    values?.mapNotNull { v ->
        ModelAbility.entries.firstOrNull { it.name.equals(v, ignoreCase = true) }
    }

internal fun parseModalities(values: List<String>?): List<Modality>? =
    values?.mapNotNull { v ->
        Modality.entries.firstOrNull { it.name.equals(v, ignoreCase = true) }
    }

internal fun parseBuiltInTools(values: List<String>?): Set<BuiltInTools>? =
    values?.mapNotNull { it.toBuiltInTool() }?.toSet()

internal fun String.toBuiltInTool(): BuiltInTools? = when (lowercase()) {
    "search" -> BuiltInTools.Search
    "url_context" -> BuiltInTools.UrlContext
    "image_generation" -> BuiltInTools.ImageGeneration
    else -> null
}

internal fun BuiltInTools.configName(): String = when (this) {
    BuiltInTools.Search -> "search"
    BuiltInTools.UrlContext -> "url_context"
    BuiltInTools.ImageGeneration -> "image_generation"
}

internal fun parseCustomHeaders(element: JsonElement?): List<CustomHeader>? {
    if (element == null || element is JsonNull) return null
    val array = element as? JsonArray ?: return null
    return array.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val value = obj["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
        CustomHeader(name = name, value = value)
    }
}

// ---- model_add 智能默认：按 modelId 关键字推断模型基本设置 ----

/** modelId 智能推断结果（AI 未显式传参时使用）。 */
internal data class ModelBasicConfig(
    val type: ModelType = ModelType.CHAT,
    val abilities: List<ModelAbility> = emptyList(),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
)

/** 推理类模型关键字（命中则附加 REASONING ability）。 */
private val REASONING_KEYWORDS = listOf(
    "o1", "o3", "o4", "gpt-5", "thinking", "reasoner", "deepseek-r1", "r1-", "gemini-2.5",
    "claude-3-7", "claude-4", "grok-3", "qwq", "kimi-k",
)

/** 多模态输入关键字（命中则 inputModalities 含 image）。 */
private val VISION_KEYWORDS = listOf(
    "vision", "omni", "multimodal", "vl", "4o", "gemini", "claude", "nova-lite", "qwen2-vl", "llava",
)

internal fun inferModelBasicConfig(modelId: String): ModelBasicConfig {
    val lower = modelId.lowercase()
    val isEmbedding = lower.contains("embedding") || lower.startsWith("text-embedding")
    val isImageGen = lower.contains("dall-e") || lower.contains("flux") ||
        lower.contains("stable-diffusion") || lower.contains("imagen") || lower.contains("imagegen")
    val isRerank = lower.contains("rerank")

    if (isEmbedding) {
        return ModelBasicConfig(type = ModelType.EMBEDDING)
    }
    if (isImageGen) {
        return ModelBasicConfig(
            type = ModelType.IMAGE,
            outputModalities = listOf(Modality.IMAGE),
        )
    }
    if (isRerank) {
        return ModelBasicConfig(type = ModelType.RERANKING)
    }

    val abilities = buildList {
        add(ModelAbility.TOOL)
        if (REASONING_KEYWORDS.any { lower.contains(it) }) add(ModelAbility.REASONING)
    }
    val inputModalities = if (VISION_KEYWORDS.any { lower.contains(it) }) {
        listOf(Modality.TEXT, Modality.IMAGE)
    } else {
        listOf(Modality.TEXT)
    }
    return ModelBasicConfig(
        type = ModelType.CHAT,
        abilities = abilities,
        inputModalities = inputModalities,
        outputModalities = listOf(Modality.TEXT),
    )
}

/** 解析 balance 对象 {"enabled", "apiPath", "resultPath"}；未提供或全空返回 null（保持现值）。 */
internal fun parseBalance(element: JsonElement?): BalanceOption? {
    if (element == null || element !is JsonObject) return null
    val enabled = element["enabled"]?.jsonPrimitive?.booleanOrNull
    val apiPath = element["apiPath"]?.jsonPrimitive?.contentOrNull
    val resultPath = element["resultPath"]?.jsonPrimitive?.contentOrNull
    if (enabled == null && apiPath == null && resultPath == null) return null
    return BalanceOption(
        enabled = enabled ?: false,
        apiPath = apiPath ?: "/credits",
        resultPath = resultPath ?: "data.total_usage",
    )
}

/** 解析 Claude 缓存 TTL："5m"/"1h"（@SerialName）或枚举名；非法返回 null。 */
internal fun parsePromptCacheTtl(value: String?): ClaudePromptCacheTtl? {
    if (value.isNullOrBlank()) return null
    return when (value.trim().lowercase()) {
        "5m" -> ClaudePromptCacheTtl.FIVE_MINUTES
        "1h" -> ClaudePromptCacheTtl.ONE_HOUR
        else -> ClaudePromptCacheTtl.entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        }
    }
}

private fun ProviderSetting.typeName(): String = when (this) {
    is ProviderSetting.OpenAI -> "openai"
    is ProviderSetting.Google -> "google"
    is ProviderSetting.Claude -> "claude"
}

private fun ProviderSetting.summary(): String = buildString {
    appendLine("- id: $id")
    appendLine("  name: $name")
    appendLine("  type: ${typeName()}")
    appendLine("  enabled: $enabled")
    appendLine("  models: ${models.size}")
}

private fun ProviderSetting.detail(): String = buildString {
    appendLine("id: $id")
    appendLine("name: $name")
    appendLine("type: ${typeName()}")
    appendLine("enabled: $enabled")
    appendLine("models: ${models.size}")
    models.forEach { model ->
        appendLine("model: id=${model.id} modelId=${model.modelId} displayName=${model.displayName} type=${model.type}")
        appendLine("  abilities: ${model.abilities.joinToString { it.name.lowercase() }}")
        appendLine(
            "  input: ${model.inputModalities.joinToString { it.name.lowercase() }} " +
                "output: ${model.outputModalities.joinToString { it.name.lowercase() }}"
        )
        appendLine("  builtInTools: ${model.tools.joinToString { it.configName() }}")
        if (model.customHeaders.isNotEmpty()) {
            appendLine("  customHeaders: ${model.customHeaders.size} (masked)")
        }
    }
    when (this@detail) {
        is ProviderSetting.OpenAI -> {
            appendLine("baseUrl: $baseUrl")
            appendLine("apiKey: ${maskSecret(apiKey)}")
            appendLine("useResponseApi: $useResponseApi")
        }
        is ProviderSetting.Google -> {
            appendLine("baseUrl: $baseUrl")
            appendLine("apiKey: ${maskSecret(apiKey)}")
            appendLine("vertexAI: $vertexAI")
        }
        is ProviderSetting.Claude -> {
            appendLine("baseUrl: $baseUrl")
            appendLine("apiKey: ${maskSecret(apiKey)}")
            appendLine("promptCaching: $promptCaching")
        }
    }
}

private fun Settings.referencedModelIds(): Set<Uuid> = buildSet {
    listOfNotNull(
        chatModelId,
        fastModelId,
        titleModelId,
        imageGenerationModelId,
        translateModeId,
        suggestionModelId,
        ocrModelId,
        compressModelId,
        embeddingModelId,
        rerankModelId,
        promptOptimizeModelId,
        subAgentModelId,
    ).forEach(::add)
    assistants.forEach { assistant ->
        assistant.chatModelId?.let(::add)
    }
}

internal fun isValidModeRef(ref: String?, settings: Settings): Boolean {
    if (ref.isNullOrBlank()) return true
    if (ModeRefs.parseBuiltin(ref) != null) return true
    return settings.customModes.any {
        it.id == ref.removePrefix(ModeRefs.CUSTOM_PREFIX)
    }
}

internal fun updateProviderModels(
    current: List<Model>,
    full: List<String>?,
    add: List<String>?,
    remove: List<String>?,
): List<Model> {
    val base = if (full != null) {
        full.map {
            val modelId = it.trim()
            Model(
                modelId = modelId,
                displayName = modelId,
                contextLength = ModelRegistry.contextLengthOrDefault(modelId),
            )
        }
    } else {
        current
    }
    val additions = add.orEmpty().map {
        val modelId = it.trim()
        Model(
            modelId = modelId,
            displayName = modelId,
            contextLength = ModelRegistry.contextLengthOrDefault(modelId),
        )
    }
    val removed = remove.orEmpty().toSet()
    return (base + additions).filterNot { it.modelId in removed }.distinctBy { it.modelId }
}

private fun convertProviderType(
    provider: ProviderSetting,
    type: String,
): ProviderSetting? {
    val apiKey = when (provider) {
        is ProviderSetting.OpenAI -> provider.apiKey
        is ProviderSetting.Google -> provider.apiKey
        is ProviderSetting.Claude -> provider.apiKey
    }
    val baseUrl = when (provider) {
        is ProviderSetting.OpenAI -> provider.baseUrl
        is ProviderSetting.Google -> provider.baseUrl
        is ProviderSetting.Claude -> provider.baseUrl
    }
    return when (type) {
        "openai" -> ProviderSetting.OpenAI(
            id = provider.id,
            enabled = provider.enabled,
            name = provider.name,
            models = provider.models,
            apiKey = apiKey,
            baseUrl = baseUrl,
            useResponseApi = (provider as? ProviderSetting.OpenAI)?.useResponseApi ?: false,
        )
        "google" -> ProviderSetting.Google(
            id = provider.id,
            enabled = provider.enabled,
            name = provider.name,
            models = provider.models,
            apiKey = apiKey,
            baseUrl = baseUrl,
        )
        "claude" -> ProviderSetting.Claude(
            id = provider.id,
            enabled = provider.enabled,
            name = provider.name,
            models = provider.models,
            apiKey = apiKey,
            baseUrl = baseUrl,
        )
        else -> null
    }
}

/** 管理模式写操作包装：成功记录审计 + 可回滚快照，异常记录 error 并上抛。 */
internal suspend fun audited(
    auditStore: ManagementAuditStore,
    tool: String,
    target: String,
    rollbackStore: ManagementRollbackStore? = null,
    captureSettings: (() -> Settings)? = null,
    block: suspend () -> List<UIMessagePart>,
): List<UIMessagePart> {
    val before = captureSettings?.invoke()
    return try {
        val result = block()
        auditStore.record(tool = tool, target = target, result = "ok")
        if (rollbackStore != null && before != null) {
            rollbackStore.record(
                settings = before,
                tool = tool,
                target = target,
            )
        }
        result
    } catch (e: Exception) {
        auditStore.record(
            tool = tool,
            target = target,
            result = "error",
            detail = e.message ?: e.javaClass.simpleName,
        )
        throw e
    }
}

fun createProviderAdminTools(
    settingsStore: SettingsStore,
    providerManager: ProviderManager,
    auditStore: ManagementAuditStore,
    rollbackStore: ManagementRollbackStore,
): List<Tool> {
    fun findProvider(id: String): ProviderSetting? =
        settingsStore.settingsFlow.value.providers.find { it.id.toString() == id }

    suspend fun testProviderText(provider: ProviderSetting): String {
        val model = provider.models.firstOrNull { it.type == ModelType.CHAT }
            ?: return "no chat model"
        return runCatching {
            val impl = providerManager.getProviderByType(provider)
            val result = impl.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.system("You are a helpful assistant."),
                    UIMessage.user("Reply with OK."),
                ),
                params = TextGenerationParams(model = model),
            )
            "replied: " + result.message.toText().take(200)
        }.getOrElse { "error: ${it.message}" }
    }

    return listOf(
        Tool(
            name = "provider_list",
            description = "List all configured AI providers with id, type, enabled state and model count. API keys are never returned.",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = {
                val settings = settingsStore.settingsFlow.value
                if (settings.providers.isEmpty()) {
                    listOf(UIMessagePart.Text("(no providers)"))
                } else {
                    listOf(UIMessagePart.Text(settings.providers.joinToString("\n") { it.summary() }))
                }
            },
        ),
        Tool(
            name = "provider_get",
            description = "Get detailed configuration of one provider. API keys are redacted; use provider_update to change them.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", "string")
                            put("description", "Provider id from provider_list")
                        })
                    },
                    required = listOf("id"),
                )
            },
            execute = { args ->
                val id = args.jsonObject.str("id").orEmpty()
                val provider = findProvider(id)
                    ?: return@Tool errorText("provider '$id' not found")
                listOf(UIMessagePart.Text(provider.detail()))
            },
        ),
        Tool(
            name = "provider_create",
            description = "Create a new provider. type must be openai, google or claude; name and apiKey are required; baseUrl and models are optional. Requires user approval.",
            needsApproval = { true },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("type", buildJsonObject { put("type", "string") })
                        put("name", buildJsonObject { put("type", "string") })
                        put("baseUrl", buildJsonObject { put("type", "string") })
                        put("apiKey", buildJsonObject { put("type", "string") })
                        put("enabled", buildJsonObject { put("type", "boolean") })
                        put("models", buildJsonObject {
                            put("type", "array")
                            put("description", "Optional model id list")
                        })
                        put("useResponseApi", buildJsonObject {
                            put("type", "boolean")
                            put("description", "OpenAI only")
                        })
                    },
                    required = listOf("type", "name", "apiKey"),
                )
            },
            execute = { args ->
                val input = args.jsonObject
                val type = input.str("type").orEmpty()
                val name = input.str("name")?.trim().orEmpty()
                val apiKey = input.str("apiKey").orEmpty()
                if (name.isEmpty() || apiKey.isEmpty()) {
                    return@Tool errorText("name and apiKey are required")
                }
                val settings = settingsStore.settingsFlow.value
                if (settings.providers.any { it.name.equals(name, ignoreCase = true) }) {
                    return@Tool errorText("provider name '$name' already exists")
                }
                val models = input.strArray("models")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.map { modelId ->
                        Model(
                            modelId = modelId,
                            displayName = modelId,
                            contextLength = ModelRegistry.contextLengthOrDefault(modelId),
                        )
                    }
                    ?: emptyList()
                val baseUrl = input.str("baseUrl")?.trim()?.ifEmpty { null }
                val enabled = input.bool("enabled") ?: true
                val provider: ProviderSetting = when (type) {
                    "openai" -> ProviderSetting.OpenAI(
                        name = name,
                        models = models,
                        apiKey = apiKey,
                        baseUrl = baseUrl ?: ProviderSetting.OpenAI().baseUrl,
                        enabled = enabled,
                        useResponseApi = input.bool("useResponseApi") ?: false,
                    )
                    "google" -> ProviderSetting.Google(
                        name = name,
                        models = models,
                        apiKey = apiKey,
                        baseUrl = baseUrl ?: ProviderSetting.Google().baseUrl,
                        enabled = enabled,
                    )
                    "claude" -> ProviderSetting.Claude(
                        name = name,
                        models = models,
                        apiKey = apiKey,
                        baseUrl = baseUrl ?: ProviderSetting.Claude().baseUrl,
                        enabled = enabled,
                    )
                    else -> return@Tool errorText("type must be openai, google or claude")
                }
                audited(
                    auditStore = auditStore,
                    tool = "provider_create",
                    target = name,
                    rollbackStore = rollbackStore,
                    captureSettings = { settingsStore.settingsFlow.value },
                ) {
                    settingsStore.updateProviders(settings.providers + provider)
                    listOf(UIMessagePart.Text("Provider \"$name\" created (id=${provider.id})."))
                }
            },
        ),
        Tool(
            name = "provider_update",
            description = "Update an existing provider. Supported types: openai, google, claude. type converts the provider type; omitted fields keep their current value. models replaces the whole list; addModels/removeModels adjust it. Advanced settings per type: OpenAI useResponseApi/includeHistoryReasoning/chatCompletionsPath/embeddingsPath/rerankPath, Claude promptCaching/promptCacheTtl, Google vertexAI/useServiceAccount/location/projectId; balance works for all types. Removing a model used by any assistant or global model setting is rejected. Requires user approval.",
            needsApproval = { true },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", "string")
                            put("description", "Provider id")
                        })
                        put("type", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional provider type conversion: openai, google or claude")
                        })
                        put("name", buildJsonObject { put("type", "string") })
                        put("baseUrl", buildJsonObject { put("type", "string") })
                        put("apiKey", buildJsonObject {
                            put("type", "string")
                            put("description", "Leave blank to keep the current key")
                        })
                        put("enabled", buildJsonObject { put("type", "boolean") })
                        put("models", buildJsonObject {
                            put("type", "array")
                            put("description", "Full replacement model id list")
                        })
                        put("addModels", buildJsonObject {
                            put("type", "array")
                            put("description", "Model ids to add")
                        })
                        put("removeModels", buildJsonObject {
                            put("type", "array")
                            put("description", "Model ids to remove")
                        })
                        put("useResponseApi", buildJsonObject {
                            put("type", "boolean")
                            put("description", "OpenAI only")
                        })
                        put("includeHistoryReasoning", buildJsonObject {
                            put("type", "boolean")
                            put("description", "OpenAI only")
                        })
                        put("chatCompletionsPath", buildJsonObject {
                            put("type", "string")
                            put("description", "OpenAI only, e.g. /chat/completions")
                        })
                        put("embeddingsPath", buildJsonObject {
                            put("type", "string")
                            put("description", "OpenAI only, e.g. /embeddings")
                        })
                        put("rerankPath", buildJsonObject {
                            put("type", "string")
                            put("description", "OpenAI only, e.g. /rerank")
                        })
                        put("balance", buildJsonObject {
                            put("type", "object")
                            put("description", "Balance fetch config: {\"enabled\": bool, \"apiPath\": str, \"resultPath\": str}")
                        })
                        put("promptCaching", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Claude only")
                        })
                        put("promptCacheTtl", buildJsonObject {
                            put("type", "string")
                            put("description", "Claude only: 5m or 1h")
                        })
                        put("vertexAI", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Google only")
                        })
                        put("useServiceAccount", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Google only")
                        })
                        put("location", buildJsonObject {
                            put("type", "string")
                            put("description", "Google only, e.g. us-central1")
                        })
                        put("projectId", buildJsonObject {
                            put("type", "string")
                            put("description", "Google only")
                        })
                    },
                    required = listOf("id"),
                )
            },
            execute = { args ->
                val input = args.jsonObject
                val id = input.str("id").orEmpty()
                val settings = settingsStore.settingsFlow.value
                val provider = findProvider(id)
                    ?: return@Tool errorText("provider '$id' not found")
                val targetProvider = input.str("type")?.let { type ->
                    if (type == provider.typeName()) {
                        provider
                    } else {
                        convertProviderType(provider, type)
                            ?: return@Tool errorText("type must be openai, google or claude")
                    }
                } ?: provider
                val fullModels = input.strArray("models")?.map { it.trim() }?.filter { it.isNotEmpty() }
                val addModels = input.strArray("addModels")?.map { it.trim() }?.filter { it.isNotEmpty() }
                val removeModels = input.strArray("removeModels")?.map { it.trim() }?.filter { it.isNotEmpty() }
                val newModels = updateProviderModels(targetProvider.models, fullModels, addModels, removeModels)
                val removedExisting = provider.models.filter { old ->
                    newModels.none { it.id == old.id }
                }
                if (removedExisting.any { it.id in settings.referencedModelIds() }) {
                    return@Tool errorText("cannot remove a model that is referenced by an assistant or a global model setting")
                }
                val name = input.str("name")?.trim()?.ifEmpty { null }
                val baseUrl = input.str("baseUrl")?.trim()?.ifEmpty { null }
                val apiKey = input.str("apiKey")?.takeIf { it.isNotBlank() }
                val enabled = input.bool("enabled")
                val useResponseApi = input.bool("useResponseApi")
                val includeHistoryReasoning = input.bool("includeHistoryReasoning")
                val promptCaching = input.bool("promptCaching")
                val promptCacheTtl = parsePromptCacheTtl(input.str("promptCacheTtl"))
                val vertexAI = input.bool("vertexAI")
                val useServiceAccount = input.bool("useServiceAccount")
                val location = input.str("location")?.trim()?.ifEmpty { null }
                val projectId = input.str("projectId")?.trim()?.ifEmpty { null }
                val chatCompletionsPath = input.str("chatCompletionsPath")?.trim()?.ifEmpty { null }
                val embeddingsPath = input.str("embeddingsPath")?.trim()?.ifEmpty { null }
                val rerankPath = input.str("rerankPath")?.trim()?.ifEmpty { null }
                val balance = parseBalance(input["balance"])
                val updated = when (targetProvider) {
                    is ProviderSetting.OpenAI -> targetProvider.copy(
                        name = name ?: targetProvider.name,
                        baseUrl = baseUrl ?: targetProvider.baseUrl,
                        apiKey = apiKey ?: targetProvider.apiKey,
                        enabled = enabled ?: targetProvider.enabled,
                        useResponseApi = useResponseApi ?: targetProvider.useResponseApi,
                        includeHistoryReasoning = includeHistoryReasoning ?: targetProvider.includeHistoryReasoning,
                        chatCompletionsPath = chatCompletionsPath ?: targetProvider.chatCompletionsPath,
                        embeddingsPath = embeddingsPath ?: targetProvider.embeddingsPath,
                        rerankPath = rerankPath ?: targetProvider.rerankPath,
                        balanceOption = balance ?: targetProvider.balanceOption,
                        models = newModels,
                    )
                    is ProviderSetting.Google -> targetProvider.copy(
                        name = name ?: targetProvider.name,
                        baseUrl = baseUrl ?: targetProvider.baseUrl,
                        apiKey = apiKey ?: targetProvider.apiKey,
                        enabled = enabled ?: targetProvider.enabled,
                        vertexAI = vertexAI ?: targetProvider.vertexAI,
                        useServiceAccount = useServiceAccount ?: targetProvider.useServiceAccount,
                        location = location ?: targetProvider.location,
                        projectId = projectId ?: targetProvider.projectId,
                        balanceOption = balance ?: targetProvider.balanceOption,
                        models = newModels,
                    )
                    is ProviderSetting.Claude -> targetProvider.copy(
                        name = name ?: targetProvider.name,
                        baseUrl = baseUrl ?: targetProvider.baseUrl,
                        apiKey = apiKey ?: targetProvider.apiKey,
                        enabled = enabled ?: targetProvider.enabled,
                        promptCaching = promptCaching ?: targetProvider.promptCaching,
                        promptCacheTtl = promptCacheTtl ?: targetProvider.promptCacheTtl,
                        balanceOption = balance ?: targetProvider.balanceOption,
                        models = newModels,
                    )
                }
                audited(
                    auditStore = auditStore,
                    tool = "provider_update",
                    target = provider.name,
                    rollbackStore = rollbackStore,
                    captureSettings = { settingsStore.settingsFlow.value },
                ) {
                    settingsStore.updateProviders(
                        settings.providers.map { if (it.id == provider.id) updated else it }
                    )
                    listOf(UIMessagePart.Text("Provider \"${updated.name}\" updated."))
                }
            },
        ),
        Tool(
            name = "provider_delete",
            description = "Delete a provider. Cannot delete the last provider or a provider whose models are referenced by an assistant or global model setting. Requires user approval.",
            needsApproval = { true },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject { put("type", "string") })
                    },
                    required = listOf("id"),
                )
            },
            execute = { args ->
                val id = args.jsonObject.str("id").orEmpty()
                val settings = settingsStore.settingsFlow.value
                val provider = findProvider(id)
                    ?: return@Tool errorText("provider '$id' not found")
                if (settings.providers.size <= 1) {
                    return@Tool errorText("cannot delete the last provider")
                }
                if (provider.models.any { it.id in settings.referencedModelIds() }) {
                    return@Tool errorText("cannot delete a provider whose models are still referenced")
                }
                audited(
                    auditStore = auditStore,
                    tool = "provider_delete",
                    target = provider.name,
                    rollbackStore = rollbackStore,
                    captureSettings = { settingsStore.settingsFlow.value },
                ) {
                    settingsStore.updateProviders(settings.providers.filterNot { it.id == provider.id })
                    listOf(UIMessagePart.Text("Provider \"${provider.name}\" deleted."))
                }
            },
        ),
        Tool(
            name = "model_add",
            description = "Add a model with full basic settings to a provider: type (chat/image/embedding/reranking), contextLength (tokens), abilities (tool/reasoning), input/output modalities (text/image), built-in tools (search/url_context/image_generation) and optional custom headers. When type/abilities/modalities are omitted they are inferred from the modelId (e.g. *-vision → image input, o1/thinking → reasoning). Requires user approval.",
            needsApproval = { true },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("providerId", buildJsonObject {
                            put("type", "string")
                            put("description", "Provider id from provider_list")
                        })
                        put("modelId", buildJsonObject {
                            put("type", "string")
                            put("description", "Model identifier, e.g. gpt-4o")
                        })
                        put("displayName", buildJsonObject { put("type", "string") })
                        put("contextLength", buildJsonObject {
                            put("type", "integer")
                            put("description", "Context length in tokens; defaults to the registered model capability or 128000")
                        })
                        put("type", buildJsonObject {
                            put("type", "string")
                            put("description", "chat / image / embedding / reranking (default chat)")
                        })
                        put("abilities", buildJsonObject {
                            put("type", "array")
                            put("description", "e.g. [\"tool\", \"reasoning\"]")
                        })
                        put("inputModalities", buildJsonObject {
                            put("type", "array")
                            put("description", "e.g. [\"text\", \"image\"]")
                        })
                        put("outputModalities", buildJsonObject {
                            put("type", "array")
                            put("description", "e.g. [\"text\"]")
                        })
                        put("builtInTools", buildJsonObject {
                            put("type", "array")
                            put("description", "e.g. [\"search\"]")
                        })
                        put("customHeaders", buildJsonObject {
                            put("type", "array")
                            put("description", "Optional list of {\"name\": ..., \"value\": ...} objects")
                        })
                    },
                    required = listOf("providerId", "modelId"),
                )
            },
            execute = { args ->
                val input = args.jsonObject
                val settings = settingsStore.settingsFlow.value
                val provider = findProvider(input.str("providerId").orEmpty())
                    ?: return@Tool errorText("provider not found")
                val modelId = input.str("modelId")?.trim().orEmpty()
                if (modelId.isEmpty()) return@Tool errorText("modelId is required")
                if (provider.models.any { it.modelId.equals(modelId, ignoreCase = true) }) {
                    return@Tool errorText("model '$modelId' already exists on this provider")
                }
                val inferred = inferModelBasicConfig(modelId)
                val model = Model(
                    modelId = modelId,
                    displayName = input.str("displayName")?.trim()?.ifEmpty { null } ?: modelId,
                    contextLength = input.int("contextLength")?.takeIf { it > 0 }
                        ?: ModelRegistry.contextLengthOrDefault(modelId),
                    type = input.str("type")?.let { parseModelType(it) } ?: inferred.type,
                    abilities = parseAbilities(input.strArray("abilities")) ?: inferred.abilities,
                    inputModalities = parseModalities(input.strArray("inputModalities")) ?: inferred.inputModalities,
                    outputModalities = parseModalities(input.strArray("outputModalities")) ?: inferred.outputModalities,
                    tools = parseBuiltInTools(input.strArray("builtInTools")) ?: emptySet(),
                    customHeaders = parseCustomHeaders(input["customHeaders"]) ?: emptyList(),
                )
                audited(
                    auditStore = auditStore,
                    tool = "model_add",
                    target = "${provider.name}/$modelId",
                    rollbackStore = rollbackStore,
                    captureSettings = { settingsStore.settingsFlow.value },
                ) {
                    settingsStore.updateProviders(
                        settings.providers.map { if (it.id == provider.id) provider.addModel(model) else it }
                    )
                    listOf(UIMessagePart.Text("Model \"$modelId\" added to provider \"${provider.name}\"."))
                }
            },
        ),
        Tool(
            name = "model_update",
            description = "Update basic settings of an existing model: displayName, contextLength (tokens), type, abilities, input/output modalities, built-in tools and custom headers. Omitted fields keep their current value. Requires user approval.",
            needsApproval = { true },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("providerId", buildJsonObject { put("type", "string") })
                        put("id", buildJsonObject {
                            put("type", "string")
                            put("description", "Model id from provider_get or config_read")
                        })
                        put("displayName", buildJsonObject { put("type", "string") })
                        put("contextLength", buildJsonObject {
                            put("type", "integer")
                            put("description", "Context length in tokens")
                        })
                        put("type", buildJsonObject {
                            put("type", "string")
                            put("description", "chat / image / embedding / reranking")
                        })
                        put("abilities", buildJsonObject {
                            put("type", "array")
                            put("description", "e.g. [\"tool\", \"reasoning\"]")
                        })
                        put("inputModalities", buildJsonObject {
                            put("type", "array")
                            put("description", "e.g. [\"text\", \"image\"]")
                        })
                        put("outputModalities", buildJsonObject {
                            put("type", "array")
                            put("description", "e.g. [\"text\"]")
                        })
                        put("builtInTools", buildJsonObject {
                            put("type", "array")
                            put("description", "e.g. [\"search\"]")
                        })
                        put("customHeaders", buildJsonObject {
                            put("type", "array")
                            put("description", "Optional list of {\"name\": ..., \"value\": ...} objects")
                        })
                    },
                    required = listOf("providerId", "id"),
                )
            },
            execute = { args ->
                val input = args.jsonObject
                val settings = settingsStore.settingsFlow.value
                val provider = findProvider(input.str("providerId").orEmpty())
                    ?: return@Tool errorText("provider not found")
                val id = input.str("id").orEmpty()
                val model = provider.models.firstOrNull { it.id.toString() == id }
                    ?: return@Tool errorText("model '$id' not found")
                val updatedModel = model.copy(
                    displayName = input.str("displayName")?.trim()?.ifEmpty { null } ?: model.displayName,
                    contextLength = input.int("contextLength")?.takeIf { it > 0 } ?: model.contextLength,
                    type = input.str("type")?.let { parseModelType(it) } ?: model.type,
                    abilities = parseAbilities(input.strArray("abilities")) ?: model.abilities,
                    inputModalities = parseModalities(input.strArray("inputModalities")) ?: model.inputModalities,
                    outputModalities = parseModalities(input.strArray("outputModalities")) ?: model.outputModalities,
                    tools = parseBuiltInTools(input.strArray("builtInTools")) ?: model.tools,
                    customHeaders = parseCustomHeaders(input["customHeaders"]) ?: model.customHeaders,
                )
                audited(
                    auditStore = auditStore,
                    tool = "model_update",
                    target = "${provider.name}/${model.modelId}",
                    rollbackStore = rollbackStore,
                    captureSettings = { settingsStore.settingsFlow.value },
                ) {
                    settingsStore.updateProviders(
                        settings.providers.map { if (it.id == provider.id) provider.editModel(updatedModel) else it }
                    )
                    listOf(UIMessagePart.Text("Model \"${updatedModel.modelId}\" updated."))
                }
            },
        ),
        Tool(
            name = "provider_test",
            description = "Test a provider by sending a minimal chat request to its first chat model. Returns the model reply or an error.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject { put("type", "string") })
                    },
                    required = listOf("id"),
                )
            },
            execute = { args ->
                val id = args.jsonObject.str("id").orEmpty()
                val provider = findProvider(id)
                    ?: return@Tool errorText("provider '$id' not found")
                listOf(UIMessagePart.Text("Provider \"${provider.name}\" ${testProviderText(provider)}"))
            },
        ),
        Tool(
            name = "provider_test_all",
            description = "Test every configured provider that has a chat model and return a compact status summary.",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = {
                val providers = settingsStore.settingsFlow.value.providers
                if (providers.isEmpty()) {
                    listOf(UIMessagePart.Text("(no providers)"))
                } else {
                    val lines = providers.map { provider ->
                        "- ${provider.name}: ${testProviderText(provider)}"
                    }
                    listOf(UIMessagePart.Text(lines.joinToString("\n")))
                }
            },
        ),
    )
}

fun createAssistantAdminTools(
    settingsStore: SettingsStore,
    auditStore: ManagementAuditStore,
    rollbackStore: ManagementRollbackStore,
): List<Tool> {
    fun assistantSummary(settings: Settings, assistant: Assistant): String = buildString {
        val model = assistant.chatModelId?.let { settings.providers.findModelById(it) }
        appendLine("- id: ${assistant.id}")
        appendLine("  name: ${assistant.name.ifBlank { "(unnamed)" }}")
        appendLine("  model: ${model?.displayName ?: "(unset)"}")
        appendLine("  defaultMode: ${assistant.defaultMode ?: "(follow global)"}")
        appendLine("  memory: ${assistant.enableMemory}")
        appendLine("  webSearch: ${assistant.enableWebSearch}")
        appendLine("  knowledgeBases: ${assistant.knowledgeBaseIds.size}")
    }

    return listOf(
        Tool(
            name = "assistant_list",
            description = "List all assistants with id, name, model, default mode and key toggles.",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = {
                val settings = settingsStore.settingsFlow.value
                listOf(UIMessagePart.Text(settings.assistants.joinToString("\n") { assistantSummary(settings, it) }))
            },
        ),
        Tool(
            name = "assistant_get",
            description = "Get detailed configuration of one assistant. Omit id to inspect the current assistant.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", "string")
                            put("description", "Optional assistant id")
                        })
                    }
                )
            },
            execute = { args ->
                val settings = settingsStore.settingsFlow.value
                val id = args.jsonObject.uuid("id")
                val target = when (id) {
                    null -> settings.getCurrentAssistant()
                    else -> settings.assistants.find { it.id == id }
                        ?: return@Tool errorText("assistant '$id' not found")
                }
                val text = buildString {
                    appendLine("id: ${target.id}")
                    appendLine("name: ${target.name.ifBlank { "(unnamed)" }}")
                    appendLine("modelId: ${target.chatModelId}")
                    appendLine("systemPrompt: ${target.systemPrompt.take(2000)}")
                    appendLine("temperature: ${target.temperature}")
                    appendLine("topP: ${target.topP}")
                    appendLine("maxTokens: ${target.maxTokens}")
                    appendLine("contextMessageLimit: ${target.contextMessageLimit}")
                    appendLine("contextTokenLimit: ${target.contextTokenLimit}")
                    appendLine("streamOutput: ${target.streamOutput}")
                    appendLine("enableMemory: ${target.enableMemory}")
                    appendLine("enableRecentChatsReference: ${target.enableRecentChatsReference}")
                    appendLine("enableWebSearch: ${target.enableWebSearch}")
                    appendLine("knowledgeBaseIds: ${target.knowledgeBaseIds.joinToString(", ")}")
                    appendLine("enabledStudyTools: ${target.enabledStudyTools.joinToString(", ")}")
                    appendLine("defaultMode: ${target.defaultMode}")
                }
                listOf(UIMessagePart.Text(text))
            },
        ),
        Tool(
            name = "assistant_update",
            description = "Update assistant fields. Omit id to update the current assistant. The update is approval-free only for the current assistant; other assistants require approval. Fields: name, chatModelId, systemPrompt, temperature, topP, maxTokens, contextMessageLimit, contextTokenLimit, streamOutput, enableMemory, useGlobalMemory, enableRecentChatsReference, enableWebSearch, knowledgeBaseIds, enabledStudyTools, defaultMode.",
            needsApproval = { args ->
                val currentId = settingsStore.settingsFlow.value.getCurrentAssistant().id
                val targetId = args.jsonObject.uuid("id") ?: currentId
                targetId != currentId
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject { put("type", "string") })
                        put("name", buildJsonObject { put("type", "string") })
                        put("chatModelId", buildJsonObject { put("type", "string") })
                        put("systemPrompt", buildJsonObject { put("type", "string") })
                        put("temperature", buildJsonObject { put("type", "number") })
                        put("topP", buildJsonObject { put("type", "number") })
                        put("maxTokens", buildJsonObject { put("type", "integer") })
                        put("contextMessageLimit", buildJsonObject { put("type", "integer") })
                        put("contextTokenLimit", buildJsonObject { put("type", "integer") })
                        put("streamOutput", buildJsonObject { put("type", "boolean") })
                        put("enableMemory", buildJsonObject { put("type", "boolean") })
                        put("useGlobalMemory", buildJsonObject { put("type", "boolean") })
                        put("enableRecentChatsReference", buildJsonObject { put("type", "boolean") })
                        put("enableWebSearch", buildJsonObject { put("type", "boolean") })
                        put("knowledgeBaseIds", buildJsonObject { put("type", "array") })
                        put("enabledStudyTools", buildJsonObject { put("type", "array") })
                        put("defaultMode", buildJsonObject { put("type", "string") })
                    }
                )
            },
            execute = { args ->
                val input = args.jsonObject
                val settings = settingsStore.settingsFlow.value
                val current = settings.getCurrentAssistant()
                val targetId = input.uuid("id") ?: current.id
                val target = settings.assistants.find { it.id == targetId }
                    ?: return@Tool errorText("assistant '$targetId' not found")
                val chatModelId = input.uuid("chatModelId")
                if (chatModelId != null && settings.providers.findModelById(chatModelId) == null) {
                    return@Tool errorText("model '$chatModelId' not found")
                }
                val temperature = input.float("temperature")
                if (temperature != null && (temperature < 0f || temperature > 2f)) {
                    return@Tool errorText("temperature must be between 0 and 2")
                }
                val topP = input.float("topP")
                if (topP != null && (topP < 0f || topP > 1f)) {
                    return@Tool errorText("topP must be between 0 and 1")
                }
                val maxTokens = input.int("maxTokens")
                if (maxTokens != null && maxTokens <= 0) {
                    return@Tool errorText("maxTokens must be positive")
                }
                val contextMessageLimit = input.int("contextMessageLimit")
                if (contextMessageLimit != null && contextMessageLimit < 0) {
                    return@Tool errorText("contextMessageLimit must be >= 0")
                }
                val contextTokenLimit = input.int("contextTokenLimit")
                if (contextTokenLimit != null && contextTokenLimit <= 0) {
                    return@Tool errorText("contextTokenLimit must be positive")
                }
                val knowledgeBaseIds = input.strArray("knowledgeBaseIds")
                    ?.mapNotNull { runCatching { Uuid.parse(it.trim()) }.getOrNull() }
                if (input.strArray("knowledgeBaseIds") != null &&
                    input.strArray("knowledgeBaseIds")!!.any { runCatching { Uuid.parse(it.trim()) }.isFailure }
                ) {
                    return@Tool errorText("knowledgeBaseIds contains an invalid UUID")
                }
                val defaultModeInput = input["defaultMode"]
                val defaultMode = when (defaultModeInput) {
                    null, is JsonNull -> null
                    else -> defaultModeInput.jsonPrimitive.contentOrNull
                }
                if (defaultMode != null && !isValidModeRef(defaultMode, settings)) {
                    return@Tool errorText("defaultMode '$defaultMode' is not a valid mode ref")
                }
                val updated = target.copy(
                    name = input.str("name")?.trim() ?: target.name,
                    chatModelId = chatModelId ?: target.chatModelId,
                    systemPrompt = input.str("systemPrompt") ?: target.systemPrompt,
                    temperature = temperature ?: target.temperature,
                    topP = topP ?: target.topP,
                    maxTokens = maxTokens ?: target.maxTokens,
                    contextMessageLimit = contextMessageLimit ?: target.contextMessageLimit,
                    contextTokenLimit = contextTokenLimit ?: target.contextTokenLimit,
                    streamOutput = input.bool("streamOutput") ?: target.streamOutput,
                    enableMemory = input.bool("enableMemory") ?: target.enableMemory,
                    useGlobalMemory = input.bool("useGlobalMemory") ?: target.useGlobalMemory,
                    enableRecentChatsReference =
                        input.bool("enableRecentChatsReference") ?: target.enableRecentChatsReference,
                    enableWebSearch = input.bool("enableWebSearch") ?: target.enableWebSearch,
                    knowledgeBaseIds = knowledgeBaseIds?.toSet() ?: target.knowledgeBaseIds,
                    enabledStudyTools = input.strArray("enabledStudyTools") ?: target.enabledStudyTools,
                    defaultMode = if (input.containsKey("defaultMode")) defaultMode else target.defaultMode,
                )
                audited(
                    auditStore = auditStore,
                    tool = "assistant_update",
                    target = updated.name.ifBlank { targetId.toString() },
                    rollbackStore = rollbackStore,
                    captureSettings = { settingsStore.settingsFlow.value },
                ) {
                    settingsStore.update { s ->
                        s.copy(
                            assistants = s.assistants.map {
                                if (it.id == target.id) updated else it
                            }
                        )
                    }
                    listOf(UIMessagePart.Text("Assistant \"${updated.name.ifBlank { "(unnamed)" }}\" updated."))
                }
            },
        ),
        Tool(
            name = "assistant_create",
            description = "Create a new assistant. Requires user approval.",
            needsApproval = { true },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("name", buildJsonObject { put("type", "string") })
                        put("chatModelId", buildJsonObject { put("type", "string") })
                        put("systemPrompt", buildJsonObject { put("type", "string") })
                    }
                )
            },
            execute = { args ->
                val input = args.jsonObject
                val settings = settingsStore.settingsFlow.value
                val modelId = input.uuid("chatModelId")
                if (modelId != null && settings.providers.findModelById(modelId) == null) {
                    return@Tool errorText("model '$modelId' not found")
                }
                val assistant = Assistant(
                    name = input.str("name")?.trim()?.ifEmpty { "New Assistant" } ?: "New Assistant",
                    chatModelId = modelId,
                    systemPrompt = input.str("systemPrompt").orEmpty(),
                )
                audited(
                    auditStore = auditStore,
                    tool = "assistant_create",
                    target = assistant.name,
                    rollbackStore = rollbackStore,
                    captureSettings = { settingsStore.settingsFlow.value },
                ) {
                    settingsStore.update { it.copy(assistants = it.assistants + assistant) }
                    listOf(UIMessagePart.Text("Assistant \"${assistant.name}\" created (id=${assistant.id})."))
                }
            },
        ),
        Tool(
            name = "assistant_duplicate",
            description = "Duplicate an existing assistant with a new id and a copy suffix. Requires user approval.",
            needsApproval = { true },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject { put("type", "string") })
                    },
                    required = listOf("id"),
                )
            },
            execute = { args ->
                val id = args.jsonObject.uuid("id")
                    ?: return@Tool errorText("id is required")
                val settings = settingsStore.settingsFlow.value
                val source = settings.assistants.find { it.id == id }
                    ?: return@Tool errorText("assistant '$id' not found")
                var name = source.name.ifBlank { "(unnamed)" } + " Copy"
                var index = 2
                while (settings.assistants.any { it.name == name }) {
                    name = source.name.ifBlank { "(unnamed)" } + " Copy $index"
                    index++
                }
                val copy = source.copy(id = Uuid.random(), name = name)
                audited(
                    auditStore = auditStore,
                    tool = "assistant_duplicate",
                    target = name,
                    rollbackStore = rollbackStore,
                    captureSettings = { settingsStore.settingsFlow.value },
                ) {
                    settingsStore.update { it.copy(assistants = it.assistants + copy) }
                    listOf(UIMessagePart.Text("Assistant \"$name\" created (id=${copy.id})."))
                }
            },
        ),
        Tool(
            name = "assistant_delete",
            description = "Delete an assistant. Cannot delete the current assistant or the last assistant. Requires user approval.",
            needsApproval = { true },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject { put("type", "string") })
                    },
                    required = listOf("id"),
                )
            },
            execute = { args ->
                val id = args.jsonObject.uuid("id")
                    ?: return@Tool errorText("id is required")
                val settings = settingsStore.settingsFlow.value
                if (settings.assistants.size <= 1) {
                    return@Tool errorText("cannot delete the last assistant")
                }
                if (settings.getCurrentAssistant().id == id) {
                    return@Tool errorText("cannot delete the current assistant")
                }
                val target = settings.assistants.find { it.id == id }
                    ?: return@Tool errorText("assistant '$id' not found")
                audited(
                    auditStore = auditStore,
                    tool = "assistant_delete",
                    target = target.name.ifBlank { id.toString() },
                    rollbackStore = rollbackStore,
                    captureSettings = { settingsStore.settingsFlow.value },
                ) {
                    settingsStore.update {
                        it.copy(assistants = it.assistants.filterNot { a -> a.id == id })
                    }
                    listOf(UIMessagePart.Text("Assistant \"${target.name.ifBlank { "(unnamed)" }}\" deleted."))
                }
            },
        ),
    )
}

fun createSettingsAdminTools(
    settingsStore: SettingsStore,
    auditStore: ManagementAuditStore,
    rollbackStore: ManagementRollbackStore,
): List<Tool> {
    return listOf(
        Tool(
            name = "settings_admin_list",
            description = "List writable global setting keys and their types. Use settings_admin_set to change them.",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = {
                listOf(
                    UIMessagePart.Text(
                        """
                        default_mode: string|null
                        chat_model_id: uuid
                        fast_model_id: uuid
                        enable_mcp_manager: boolean
                        enable_sub_agent: boolean
                        enable_todo_list: boolean
                        enable_agent_behavior_prompt: boolean
                        pdf_ocr_enabled: boolean
                        study_edit_enabled: boolean
                        study_delete_enabled: boolean
                        study_delete_approval_enabled: boolean
                        """.trimIndent()
                    )
                )
            },
        ),
        Tool(
            name = "settings_admin_get",
            description = "Read the current value of writable global settings.",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = {
                val s = settingsStore.settingsFlow.value
                listOf(
                    UIMessagePart.Text(
                        buildString {
                            appendLine("default_mode: ${s.defaultMode}")
                            appendLine("chat_model_id: ${s.chatModelId}")
                            appendLine("fast_model_id: ${s.fastModelId}")
                            appendLine("enable_mcp_manager: ${s.enableMcpManager}")
                            appendLine("enable_sub_agent: ${s.enableSubAgent}")
                            appendLine("enable_todo_list: ${s.enableTodoList}")
                            appendLine("enable_agent_behavior_prompt: ${s.enableAgentBehaviorPrompt}")
                            appendLine("pdf_ocr_enabled: ${s.pdfOcrEnabled}")
                            appendLine("study_edit_enabled: ${s.studyEditEnabled}")
                            appendLine("study_delete_enabled: ${s.studyDeleteEnabled}")
                            appendLine("study_delete_approval_enabled: ${s.studyDeleteApprovalEnabled}")
                        }
                    )
                )
            },
        ),
        Tool(
            name = "settings_admin_set",
            description = "Set one allowlisted global setting. Requires user approval.",
            needsApproval = { true },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("key", buildJsonObject { put("type", "string") })
                        put("value", buildJsonObject {
                            put("type", "string")
                            put("description", "JSON value: string, number, boolean or null")
                        })
                    },
                    required = listOf("key", "value"),
                )
            },
            execute = { args ->
                val input = args.jsonObject
                val key = input.str("key").orEmpty()
                val value = input["value"]
                val settings = settingsStore.settingsFlow.value
                val result = when (key) {
                    "default_mode" -> {
                        val ref = when (value) {
                            null, is JsonNull -> null
                            else -> value.jsonPrimitive.contentOrNull
                        }
                        if (!isValidModeRef(ref, settings)) {
                            return@Tool errorText("default_mode '$ref' is not a valid mode ref")
                        }
                        settingsStore.update { it.copy(defaultMode = ref) }
                        "default_mode=$ref"
                    }
                    "chat_model_id", "fast_model_id" -> {
                        val id = value?.jsonPrimitive?.contentOrNull?.let {
                            runCatching { Uuid.parse(it.trim()) }.getOrNull()
                        } ?: return@Tool errorText("$key requires a valid UUID")
                        if (settings.providers.findModelById(id) == null) {
                            return@Tool errorText("model '$id' not found")
                        }
                        settingsStore.update {
                            if (key == "chat_model_id") it.copy(chatModelId = id) else it.copy(fastModelId = id)
                        }
                        "$key=$id"
                    }
                    "enable_mcp_manager", "enable_sub_agent", "enable_todo_list",
                    "enable_agent_behavior_prompt", "pdf_ocr_enabled",
                    "study_edit_enabled", "study_delete_enabled", "study_delete_approval_enabled" -> {
                        val enabled = value?.jsonPrimitive?.booleanOrNull
                            ?: return@Tool errorText("$key requires a boolean")
                        settingsStore.update {
                            when (key) {
                                "enable_mcp_manager" -> it.copy(enableMcpManager = enabled)
                                "enable_sub_agent" -> it.copy(enableSubAgent = enabled)
                                "enable_todo_list" -> it.copy(enableTodoList = enabled)
                                "enable_agent_behavior_prompt" -> it.copy(enableAgentBehaviorPrompt = enabled)
                                "pdf_ocr_enabled" -> it.copy(pdfOcrEnabled = enabled)
                                "study_edit_enabled" -> it.copy(studyEditEnabled = enabled)
                                "study_delete_enabled" -> it.copy(studyDeleteEnabled = enabled)
                                else -> it.copy(studyDeleteApprovalEnabled = enabled)
                            }
                        }
                        "$key=$enabled"
                    }
                    else -> return@Tool errorText("unknown setting key '$key'")
                }
                audited(
                    auditStore = auditStore,
                    tool = "settings_admin_set",
                    target = key,
                    rollbackStore = rollbackStore,
                    captureSettings = { settingsStore.settingsFlow.value },
                ) {
                    listOf(UIMessagePart.Text("Global setting $result updated."))
                }
            },
        ),
    )
}

private fun searchTypeName(options: SearchServiceOptions): String =
    JsonInstant.encodeToJsonElement<SearchServiceOptions>(options)
        .jsonObject["type"]?.jsonPrimitive?.contentOrNull
        ?: "unknown"

internal fun redactSearchJson(json: JsonObject): JsonObject = JsonObject(
    json.mapValues { (key, value) ->
        if (key in SEARCH_SECRET_KEYS && value is JsonPrimitive && value.content.isNotBlank()) {
            JsonPrimitive("***")
        } else {
            value
        }
    }
)

internal fun decodeSearchOptions(
    type: String,
    config: JsonObject,
    existing: SearchServiceOptions? = null,
): SearchServiceOptions {
    val full = config.toMutableMap()
    full["type"] = JsonPrimitive(type)
    existing?.let { full["id"] = JsonPrimitive(it.id.toString()) }
    return JsonInstant.decodeFromJsonElement<SearchServiceOptions>(JsonObject(full))
}

fun createDataAdminTools(
    settingsStore: SettingsStore,
    auditStore: ManagementAuditStore,
    conversationRepo: ConversationRepository,
    trustedFolderRepository: TrustedFolderRepository,
    rollbackStore: ManagementRollbackStore,
): List<Tool> {
    fun findService(id: String): SearchServiceOptions? =
        settingsStore.settingsFlow.value.searchServices.find { it.id.toString() == id }

    return listOf(
        Tool(
            name = "search_admin_list",
            description = "List configured search services with id, display name, type and enabled state.",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = {
                val settings = settingsStore.settingsFlow.value
                val text = if (settings.searchServices.isEmpty()) {
                    "(no search services)"
                } else {
                    settings.searchServices.joinToString("\n") { options ->
                        "- id: ${options.id} | name: ${options.displayName} | " +
                            "type: ${searchTypeName(options)} | " +
                            "enabled: ${options.id in settings.enabledSearchServiceIds}"
                    }
                }
                listOf(UIMessagePart.Text(text))
            },
        ),
        Tool(
            name = "search_admin_get",
            description = "Get full search service configuration as JSON. Secret fields are redacted. Use the redacted JSON as the config basis for search_admin_update.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject { put("type", "string") })
                    },
                    required = listOf("id"),
                )
            },
            execute = { args ->
                val id = args.jsonObject.str("id").orEmpty()
                val options = findService(id)
                    ?: return@Tool errorText("search service '$id' not found")
                val json = JsonInstant.encodeToJsonElement<SearchServiceOptions>(options).jsonObject
                listOf(UIMessagePart.Text(redactSearchJson(json).toString()))
            },
        ),
        Tool(
            name = "search_admin_add",
            description = "Add a search service. type is one of: bing_local, zhipu, doubao, tavily, exa, searxng, linkup, brave, metaso, ollama, perplexity, firecrawl, jina, bocha, rikkahub, grok, tinyfish, serper, custom_js. config is a JSON object matching that type's fields. Requires user approval.",
            needsApproval = { true },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("type", buildJsonObject {
                            put("type", "string")
                            put("description", "Search service type")
                        })
                        put("config", buildJsonObject {
                            put("type", "object")
                            put("description", "Fields for the selected type")
                        })
                    },
                    required = listOf("type", "config"),
                )
            },
            execute = { args ->
                val input = args.jsonObject
                val type = input.str("type").orEmpty()
                if (type !in SEARCH_TYPE_NAMES) {
                    return@Tool errorText("unknown search type '$type'")
                }
                val config = input["config"]?.jsonObject
                    ?: return@Tool errorText("config must be an object")
                val cleanConfig = JsonObject(config.toMutableMap().apply { remove("id") })
                val options = runCatching { decodeSearchOptions(type, cleanConfig) }
                    .getOrElse { return@Tool errorText("invalid config for '$type': ${it.message}") }
                audited(
                    auditStore = auditStore,
                    tool = "search_admin_add",
                    target = options.displayName,
                    rollbackStore = rollbackStore,
                    captureSettings = { settingsStore.settingsFlow.value },
                ) {
                    settingsStore.update {
                        it.copy(searchServices = it.searchServices + options)
                    }
                    listOf(UIMessagePart.Text("Search service \"${options.displayName}\" added (id=${options.id})."))
                }
            },
        ),
        Tool(
            name = "search_admin_update",
            description = "Update a search service by id. config is a full replacement JSON; apiKey, password and privateKey are preserved when omitted. Requires user approval.",
            needsApproval = { true },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject { put("type", "string") })
                        put("config", buildJsonObject { put("type", "object") })
                    },
                    required = listOf("id", "config"),
                )
            },
            execute = { args ->
                val input = args.jsonObject
                val id = input.str("id").orEmpty()
                val existing = findService(id)
                    ?: return@Tool errorText("search service '$id' not found")
                val config = input["config"]?.jsonObject
                    ?: return@Tool errorText("config must be an object")
                val existingJson = JsonInstant.encodeToJsonElement<SearchServiceOptions>(existing).jsonObject
                val merged = buildJsonObject {
                    existingJson.forEach { (key, value) ->
                        if (key in SEARCH_SECRET_KEYS &&
                            config[key] == null &&
                            value is JsonPrimitive && value.content.isNotBlank()
                        ) {
                            put(key, value)
                        }
                    }
                    config.forEach { (key, value) -> put(key, value) }
                }
                val updated = runCatching {
                    decodeSearchOptions(searchTypeName(existing), merged, existing)
                }.getOrElse { return@Tool errorText("invalid config: ${it.message}") }
                audited(
                    auditStore = auditStore,
                    tool = "search_admin_update",
                    target = existing.displayName,
                    rollbackStore = rollbackStore,
                    captureSettings = { settingsStore.settingsFlow.value },
                ) {
                    settingsStore.update {
                        it.copy(
                            searchServices = it.searchServices.map {
                                if (it.id == existing.id) updated else it
                            }
                        )
                    }
                    listOf(UIMessagePart.Text("Search service \"${updated.displayName}\" updated."))
                }
            },
        ),
        Tool(
            name = "search_admin_delete",
            description = "Delete a search service. Cannot delete the last service. Requires user approval.",
            needsApproval = { true },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject { put("type", "string") })
                    },
                    required = listOf("id"),
                )
            },
            execute = { args ->
                val id = args.jsonObject.str("id").orEmpty()
                val settings = settingsStore.settingsFlow.value
                if (settings.searchServices.size <= 1) {
                    return@Tool errorText("cannot delete the last search service")
                }
                val target = findService(id)
                    ?: return@Tool errorText("search service '$id' not found")
                val index = settings.searchServices.indexOf(target)
                val newServices = settings.searchServices.filterNot { it.id == target.id }
                val newSelected = when {
                    index < settings.searchServiceSelected ->
                        (settings.searchServiceSelected - 1).coerceIn(0, newServices.lastIndex)
                    index == settings.searchServiceSelected ->
                        settings.searchServiceSelected.coerceIn(0, newServices.lastIndex)
                    else -> settings.searchServiceSelected.coerceIn(0, newServices.lastIndex)
                }
                val newEnabled = settings.enabledSearchServiceIds
                    .filterNot { it == target.id }
                    .ifEmpty { listOf(newServices.first().id) }
                audited(
                    auditStore = auditStore,
                    tool = "search_admin_delete",
                    target = target.displayName,
                    rollbackStore = rollbackStore,
                    captureSettings = { settingsStore.settingsFlow.value },
                ) {
                    settingsStore.update {
                        it.copy(
                            searchServices = newServices,
                            searchServiceSelected = newSelected,
                            enabledSearchServiceIds = newEnabled,
                        )
                    }
                    listOf(UIMessagePart.Text("Search service \"${target.displayName}\" deleted."))
                }
            },
        ),
        Tool(
            name = "search_admin_set_enabled",
            description = "Enable or disable a configured search service for AI tools. At least one enabled service must remain. Requires user approval.",
            needsApproval = { true },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject { put("type", "string") })
                        put("enabled", buildJsonObject { put("type", "boolean") })
                    },
                    required = listOf("id", "enabled"),
                )
            },
            execute = { args ->
                val input = args.jsonObject
                val id = input.str("id").orEmpty()
                val enabled = input.bool("enabled")
                    ?: return@Tool errorText("enabled must be a boolean")
                val settings = settingsStore.settingsFlow.value
                val target = findService(id)
                    ?: return@Tool errorText("search service '$id' not found")
                if (!enabled &&
                    settings.enabledSearchServiceIds.size <= 1 &&
                    target.id in settings.enabledSearchServiceIds
                ) {
                    return@Tool errorText("at least one search service must remain enabled")
                }
                val newEnabled = if (enabled) {
                    settings.enabledSearchServiceIds + target.id
                } else {
                    settings.enabledSearchServiceIds - target.id
                }
                audited(
                    auditStore = auditStore,
                    tool = "search_admin_set_enabled",
                    target = target.displayName,
                    rollbackStore = rollbackStore,
                    captureSettings = { settingsStore.settingsFlow.value },
                ) {
                    settingsStore.update { it.copy(enabledSearchServiceIds = newEnabled) }
                    listOf(
                        UIMessagePart.Text(
                            "Search service \"${target.displayName}\" " +
                                (if (enabled) "enabled" else "disabled") + "."
                        )
                    )
                }
            },
        ),
        Tool(
            name = "search_admin_test",
            description = "Test a configured search service with a minimal query and return whether it succeeded.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject { put("type", "string") })
                    },
                    required = listOf("id"),
                )
            },
            execute = { args ->
                val id = args.jsonObject.str("id").orEmpty()
                val settings = settingsStore.settingsFlow.value
                val options = findService(id)
                    ?: return@Tool errorText("search service '$id' not found")
                val result = SearchService.getService(options).search(
                    params = buildJsonObject { put("query", JsonPrimitive("management test")) },
                    commonOptions = settings.searchCommonOptions,
                    serviceOptions = options,
                )
                val text = result.fold(
                    onSuccess = { "Search service \"${options.displayName}\" returned ${it.items.size} results." },
                    onFailure = { "Search service \"${options.displayName}\" failed: ${it.message}" },
                )
                listOf(UIMessagePart.Text(text))
            },
        ),
        Tool(
            name = "admin_inventory",
            description = "Read-only inventory of providers, models, assistants, modes, MCP servers, skills, search services, knowledge bases, workspaces, trusted folders and conversations.",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = {
                val settings = settingsStore.settingsFlow.value
                val current = settings.getCurrentAssistant()
                val trusted = trustedFolderRepository.currentSettings()
                val conversationCount = conversationRepo.countConversations()
                listOf(
                    UIMessagePart.Text(
                        buildString {
                            appendLine("providers: ${settings.providers.size}")
                            appendLine("models: ${settings.providers.sumOf { it.models.size }}")
                            appendLine("assistants: ${settings.assistants.size}")
                            appendLine("custom_modes: ${settings.customModes.size}")
                            appendLine("builtin_mode_overrides: ${settings.builtinModeOverrides.size}")
                            appendLine("mcp_servers: ${settings.mcpServers.size}")
                            appendLine(
                                "skills_enabled_for_current_assistant: " +
                                    current.enabledSkills.size
                            )
                            appendLine(
                                "knowledge_bases_bound: " +
                                    settings.assistants.flatMap { it.knowledgeBaseIds }.distinct().size
                            )
                            appendLine("workspaces_bound: ${settings.assistants.count { it.workspaceId != null }}")
                            appendLine("trusted_folder_projects: ${trusted.projects.size}")
                            appendLine("trusted_folder_active: ${trusted.activeProjectId != null}")
                            appendLine("conversations: $conversationCount")
                        }
                    )
                )
            },
        ),
    )
}

fun createWorkspaceAdminTools(
    settingsStore: SettingsStore,
    workspaceRepository: WorkspaceRepository,
    auditStore: ManagementAuditStore,
    rollbackStore: ManagementRollbackStore,
): List<Tool> {
    fun currentAssistantId(): Uuid = settingsStore.settingsFlow.value.getCurrentAssistant().id

    return listOf(
        Tool(
            name = "workspace_admin_list",
            description = "List configured workspaces with id, name, root and shell status.",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = {
                val workspaces = workspaceRepository.listFlow().first()
                val text = if (workspaces.isEmpty()) {
                    "(no workspaces)"
                } else {
                    workspaces.joinToString("\n") { workspace ->
                        "- id: ${workspace.id} | name: ${workspace.name} | " +
                            "shell: ${workspace.shellStatus}"
                    }
                }
                listOf(UIMessagePart.Text(text))
            },
        ),
        Tool(
            name = "workspace_admin_bind",
            description = "Bind an existing workspace to an assistant. Omit assistantId to bind the current assistant. Requires approval only when modifying another assistant.",
            needsApproval = { args ->
                val currentId = currentAssistantId()
                val targetId = args.jsonObject.uuid("assistantId") ?: currentId
                targetId != currentId
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("assistantId", buildJsonObject { put("type", "string") })
                        put("workspaceId", buildJsonObject { put("type", "string") })
                    },
                    required = listOf("workspaceId"),
                )
            },
            execute = { args ->
                val input = args.jsonObject
                val assistantId = input.uuid("assistantId") ?: currentAssistantId()
                val workspaceId = input.uuid("workspaceId")
                    ?: return@Tool errorText("workspaceId is required")
                val workspace = workspaceRepository.getById(workspaceId.toString())
                    ?: return@Tool errorText("workspace '$workspaceId' not found")
                val settings = settingsStore.settingsFlow.value
                if (settings.assistants.none { it.id == assistantId }) {
                    return@Tool errorText("assistant '$assistantId' not found")
                }
                audited(
                    auditStore = auditStore,
                    tool = "workspace_admin_bind",
                    target = workspace.name,
                    rollbackStore = rollbackStore,
                    captureSettings = { settingsStore.settingsFlow.value },
                ) {
                    settingsStore.update { s ->
                        s.copy(
                            assistants = s.assistants.map {
                                if (it.id == assistantId) it.copy(workspaceId = workspaceId) else it
                            }
                        )
                    }
                    listOf(UIMessagePart.Text("Workspace \"${workspace.name}\" bound to assistant '$assistantId'."))
                }
            },
        ),
        Tool(
            name = "workspace_admin_unbind",
            description = "Unbind the workspace from an assistant. Omit assistantId to unbind the current assistant. Requires approval only when modifying another assistant.",
            needsApproval = { args ->
                val currentId = currentAssistantId()
                val targetId = args.jsonObject.uuid("assistantId") ?: currentId
                targetId != currentId
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("assistantId", buildJsonObject { put("type", "string") })
                    }
                )
            },
            execute = { args ->
                val input = args.jsonObject
                val assistantId = input.uuid("assistantId") ?: currentAssistantId()
                val settings = settingsStore.settingsFlow.value
                if (settings.assistants.none { it.id == assistantId }) {
                    return@Tool errorText("assistant '$assistantId' not found")
                }
                audited(
                    auditStore = auditStore,
                    tool = "workspace_admin_unbind",
                    target = assistantId.toString(),
                    rollbackStore = rollbackStore,
                    captureSettings = { settingsStore.settingsFlow.value },
                ) {
                    settingsStore.update { s ->
                        s.copy(
                            assistants = s.assistants.map {
                                if (it.id == assistantId) it.copy(workspaceId = null) else it
                            }
                        )
                    }
                    listOf(UIMessagePart.Text("Workspace unbound from assistant '$assistantId'."))
                }
            },
        ),
    )
}

fun createTrustedFolderAdminTools(
    trustedFolderRepository: TrustedFolderRepository,
    auditStore: ManagementAuditStore,
): List<Tool> {
    return listOf(
        Tool(
            name = "trusted_folder_admin_list",
            description = "List trusted folder projects with id, name and active state.",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = {
                val settings = trustedFolderRepository.currentSettings()
                val text = if (settings.projects.isEmpty()) {
                    "(no trusted folder projects)"
                } else {
                    settings.projects.joinToString("\n") { project ->
                        "- id: ${project.id} | name: ${project.name} | " +
                            "active: ${project.id == settings.activeProjectId}"
                    }
                }
                listOf(UIMessagePart.Text(text))
            },
        ),
        Tool(
            name = "trusted_folder_admin_activate",
            description = "Activate an existing trusted folder project. Affects all assistants, so it requires approval.",
            needsApproval = { true },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("id", buildJsonObject { put("type", "string") })
                    },
                    required = listOf("id"),
                )
            },
            execute = { args ->
                val id = args.jsonObject.str("id").orEmpty()
                val settings = trustedFolderRepository.currentSettings()
                val project = settings.projects.find { it.id == id }
                    ?: return@Tool errorText("trusted folder project '$id' not found")
                audited(auditStore, "trusted_folder_admin_activate", project.name) {
                    trustedFolderRepository.setActiveProject(id)
                    listOf(UIMessagePart.Text("Trusted folder \"${project.name}\" activated."))
                }
            },
        ),
        Tool(
            name = "trusted_folder_admin_deactivate",
            description = "Deactivate the currently active trusted folder project. Requires approval.",
            needsApproval = { true },
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = {
                audited(auditStore, "trusted_folder_admin_deactivate", "active project") {
                    trustedFolderRepository.setActiveProject(null)
                    listOf(UIMessagePart.Text("Trusted folder deactivated."))
                }
            },
        ),
    )
}

fun createKnowledgeAdminTools(
    settingsStore: SettingsStore,
    knowledgeManager: KnowledgeManager,
    auditStore: ManagementAuditStore,
    rollbackStore: ManagementRollbackStore,
): List<Tool> {
    fun currentAssistantId(): Uuid = settingsStore.settingsFlow.value.getCurrentAssistant().id

    return listOf(
        Tool(
            name = "knowledge_admin_list",
            description = "List knowledge bases with id, name, document count and chunk count.",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = {
                val bases = knowledgeManager.baseRepository.getAllWithDocumentCount().first()
                val text = if (bases.isEmpty()) {
                    "(no knowledge bases)"
                } else {
                    bases.joinToString("\n") { base ->
                        "- id: ${base.id} | name: ${base.name} | " +
                            "documents: ${base.documentCount} | chunks: ${base.chunkCount}"
                    }
                }
                listOf(UIMessagePart.Text(text))
            },
        ),
        Tool(
            name = "knowledge_admin_bind",
            description = "Bind a knowledge base to an assistant. Omit assistantId to bind the current assistant. Requires approval only when modifying another assistant.",
            needsApproval = { args ->
                val currentId = currentAssistantId()
                val targetId = args.jsonObject.uuid("assistantId") ?: currentId
                targetId != currentId
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("assistantId", buildJsonObject { put("type", "string") })
                        put("knowledgeBaseId", buildJsonObject { put("type", "string") })
                    },
                    required = listOf("knowledgeBaseId"),
                )
            },
            execute = { args ->
                val input = args.jsonObject
                val assistantId = input.uuid("assistantId") ?: currentAssistantId()
                val baseId = input.uuid("knowledgeBaseId")
                    ?: return@Tool errorText("knowledgeBaseId is required")
                val base = knowledgeManager.baseRepository.getById(baseId.toString())
                    ?: return@Tool errorText("knowledge base '$baseId' not found")
                val settings = settingsStore.settingsFlow.value
                val target = settings.assistants.find { it.id == assistantId }
                    ?: return@Tool errorText("assistant '$assistantId' not found")
                val newIds = target.knowledgeBaseIds + baseId
                audited(
                    auditStore = auditStore,
                    tool = "knowledge_admin_bind",
                    target = base.name,
                    rollbackStore = rollbackStore,
                    captureSettings = { settingsStore.settingsFlow.value },
                ) {
                    settingsStore.update { s ->
                        s.copy(
                            assistants = s.assistants.map {
                                if (it.id == assistantId) it.copy(knowledgeBaseIds = newIds) else it
                            }
                        )
                    }
                    listOf(UIMessagePart.Text("Knowledge base \"${base.name}\" bound to assistant '$assistantId'."))
                }
            },
        ),
        Tool(
            name = "knowledge_admin_unbind",
            description = "Unbind a knowledge base from an assistant. Omit assistantId to unbind the current assistant. Requires approval only when modifying another assistant.",
            needsApproval = { args ->
                val currentId = currentAssistantId()
                val targetId = args.jsonObject.uuid("assistantId") ?: currentId
                targetId != currentId
            },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("assistantId", buildJsonObject { put("type", "string") })
                        put("knowledgeBaseId", buildJsonObject { put("type", "string") })
                    },
                    required = listOf("knowledgeBaseId"),
                )
            },
            execute = { args ->
                val input = args.jsonObject
                val assistantId = input.uuid("assistantId") ?: currentAssistantId()
                val baseId = input.uuid("knowledgeBaseId")
                    ?: return@Tool errorText("knowledgeBaseId is required")
                val base = knowledgeManager.baseRepository.getById(baseId.toString())
                    ?: return@Tool errorText("knowledge base '$baseId' not found")
                val settings = settingsStore.settingsFlow.value
                val target = settings.assistants.find { it.id == assistantId }
                    ?: return@Tool errorText("assistant '$assistantId' not found")
                val newIds = target.knowledgeBaseIds - baseId
                audited(
                    auditStore = auditStore,
                    tool = "knowledge_admin_unbind",
                    target = base.name,
                    rollbackStore = rollbackStore,
                    captureSettings = { settingsStore.settingsFlow.value },
                ) {
                    settingsStore.update { s ->
                        s.copy(
                            assistants = s.assistants.map {
                                if (it.id == assistantId) it.copy(knowledgeBaseIds = newIds) else it
                            }
                        )
                    }
                    listOf(UIMessagePart.Text("Knowledge base \"${base.name}\" unbound from assistant '$assistantId'."))
                }
            },
        ),
    )
}

fun createConversationAdminTools(
    settingsStore: SettingsStore,
    conversationRepo: ConversationRepository,
    auditStore: ManagementAuditStore,
    currentConversationId: Uuid,
): List<Tool> {
    return listOf(
        Tool(
            name = "conversation_admin_list",
            description = "List recent conversations of an assistant. Omit assistantId to list the current assistant's conversations.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("assistantId", buildJsonObject { put("type", "string") })
                    }
                )
            },
            execute = { args ->
                val settings = settingsStore.settingsFlow.value
                val assistantId = args.jsonObject.uuid("assistantId")
                    ?: settings.getCurrentAssistant().id
                val page = conversationRepo.getConversationsOfAssistantPage(
                    assistantId = assistantId,
                    offset = 0,
                    limit = 20,
                )
                val text = if (page.items.isEmpty()) {
                    "(no conversations)"
                } else {
                    page.items.joinToString("\n") { conversation ->
                        "- id: ${conversation.id} | title: " +
                            conversation.title.ifBlank { "(untitled)" } +
                            " | pinned: ${conversation.isPinned}"
                    } + if (page.nextOffset != null) "\n(next page: $page.nextOffset)" else ""
                }
                listOf(UIMessagePart.Text(text))
            },
        ),
        Tool(
            name = "conversation_admin_delete",
            description = "Permanently delete a conversation by id. Cannot delete the current conversation. Requires approval.",
            needsApproval = { true },
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("conversationId", buildJsonObject { put("type", "string") })
                    },
                    required = listOf("conversationId"),
                )
            },
            execute = { args ->
                val id = args.jsonObject.uuid("conversationId")
                    ?: return@Tool errorText("conversationId is required")
                if (id == currentConversationId) {
                    return@Tool errorText("cannot delete the current conversation")
                }
                val conversation = conversationRepo.getConversationById(id)
                    ?: return@Tool errorText("conversation '$id' not found")
                audited(auditStore, "conversation_admin_delete", conversation.title.ifBlank { id.toString() }) {
                    conversationRepo.deleteConversation(conversation)
                    listOf(UIMessagePart.Text("Conversation \"${conversation.title.ifBlank { "(untitled)" }}\" deleted."))
                }
            },
        ),
    )
}

fun createRollbackTools(
    rollbackStore: ManagementRollbackStore,
    settingsStore: SettingsStore,
    auditStore: ManagementAuditStore,
): List<Tool> {
    return listOf(
        Tool(
            name = "management_undo",
            description = "Revert the most recent settings-backed management write (provider, assistant, global settings, search service, workspace binding or knowledge binding). Requires approval.",
            needsApproval = { true },
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = {
                val snapshot = rollbackStore.undo()
                    ?: return@Tool errorText("nothing to undo")
                settingsStore.update(snapshot.settings)
                auditStore.record(
                    tool = "management_undo",
                    target = snapshot.tool,
                    result = "ok",
                )
                listOf(
                    UIMessagePart.Text(
                        "Reverted ${snapshot.tool} (${snapshot.target}) to its previous state."
                    )
                )
            },
        ),
    )
}

fun createAuditTools(
    auditStore: ManagementAuditStore,
): List<Tool> {
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return listOf(
        Tool(
            name = "audit_list",
            description = "List recent management write operations stored in memory for this app session.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "Max entries, defaults to 50")
                        })
                    }
                )
            },
            execute = { args ->
                val limit = args.jsonObject.int("limit") ?: 50
                val entries = auditStore.recent(limit)
                val text = if (entries.isEmpty()) {
                    "(no management write operations in this session)"
                } else {
                    entries.joinToString("\n") { entry ->
                        "[${formatter.format(Date(entry.timestamp))}] " +
                            "${entry.tool} -> ${entry.target} [${entry.result}]"
                    }
                }
                listOf(UIMessagePart.Text(text))
            },
        ),
    )
}
