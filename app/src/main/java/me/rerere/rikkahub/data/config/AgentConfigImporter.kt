package me.rerere.rikkahub.data.config

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BalanceOption
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.parseAbilities
import me.rerere.rikkahub.data.ai.tools.parseBuiltInTools
import me.rerere.rikkahub.data.ai.tools.parseModalities
import me.rerere.rikkahub.data.ai.tools.parseModelType
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import kotlin.uuid.Uuid

/**
 * 把 agent/ 配置文件合并回 [Settings]（文件 → DataStore 应用）。
 *
 * 合并语义（对齐 SettingsSyncCodec 的 merge 惯例）：
 * - 按 id 对齐：文件里**显式出现**的字段覆盖本地非敏感字段；文件缺失的字段一律保留本地值
 *   （避免文件缺省字段把本地设置误清零/重置）；
 * - **密钥类字段（apiKey/token/headers/customBodies）一律保留本地值**（文件里只有 keystore:* 引用，无明文）；
 * - 文件未列出的本地条目保留；
 * - 解析失败的文件整体跳过（不影响其余文件），保证导入不破坏现有配置；
 * - [applyAssistants] 支持 [onlyAssistantId] 单文件应用（文件页「应用到设置」只影响当前助手）。
 */
object AgentConfigImporter {

    fun applyProviders(settings: Settings, agentRoot: File): Settings {
        val dto = readIfValid<ProviderConfigFile>(agentRoot, AgentConfigPaths.PROVIDERS_FILE)
            ?: return settings
        val providers = settings.providers.map { current ->
            dto.providers.firstOrNull { it.id == current.id.toString() }
                ?.let { mergeProvider(current, it) }
                ?: current
        }
        return settings.copy(providers = providers)
    }

    fun applyMcpServers(settings: Settings, agentRoot: File): Settings {
        val dto = readIfValid<McpConfigFile>(agentRoot, AgentConfigPaths.MCP_FILE) ?: return settings
        val servers = settings.mcpServers.map { current ->
            dto.servers.firstOrNull { it.id == current.id.toString() }
                ?.let { mergeMcp(current, it) }
                ?: current
        }
        return settings.copy(mcpServers = servers)
    }

    /**
     * 把 agent/config/assistants/ 下的助手文件合并回 Settings。
     *
     * @param onlyAssistantId 非空时只应用该 id 的助手文件（文件页「应用到设置」的单文件语义）；
     *                        null 时应用目录下全部助手文件。
     */
    fun applyAssistants(
        settings: Settings,
        agentRoot: File,
        onlyAssistantId: String? = null,
    ): Settings {
        val assistantDir = File(agentRoot, AgentConfigPaths.ASSISTANTS_DIR)
        if (!assistantDir.isDirectory) return settings
        val data = assistantDir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.mapNotNull { file ->
                runCatching {
                    val text = file.readText()
                    val dto = JsonInstant.decodeFromString<AssistantConfigFile>(text)
                    if (onlyAssistantId != null && dto.assistant.id != onlyAssistantId) {
                        null
                    } else {
                        AssistantFileData(dto.assistant, JsonInstant.decodeFromString<JsonObject>(text))
                    }
                }.getOrNull()
            }
            .orEmpty()
        if (data.isEmpty()) return settings
        val assistants = settings.assistants.map { current ->
            data.firstOrNull { it.dto.id == current.id.toString() }
                ?.let { mergeAssistant(current, it.dto, it.raw) }
                ?: current
        }
        return settings.copy(assistants = assistants)
    }

    // ---- Provider ----

    private fun mergeProvider(current: ProviderSetting, dto: ProviderConfigDto): ProviderSetting {
        val models = mergeModels(current.models, dto.models)
        val balance = dto.balance?.let { BalanceOption(it.enabled, it.apiPath, it.resultPath) }
        return when (current) {
            is ProviderSetting.OpenAI -> current.copy(
                name = dto.name.ifBlank { current.name },
                enabled = dto.enabled,
                baseUrl = dto.baseUrl ?: current.baseUrl,
                balanceOption = balance ?: current.balanceOption,
                models = models,
                chatCompletionsPath = dto.chatCompletionsPath ?: current.chatCompletionsPath,
                embeddingsPath = dto.embeddingsPath ?: current.embeddingsPath,
                rerankPath = dto.rerankPath ?: current.rerankPath,
                useResponseApi = dto.useResponseApi ?: current.useResponseApi,
                includeHistoryReasoning = dto.includeHistoryReasoning ?: current.includeHistoryReasoning,
            )
            is ProviderSetting.Google -> current.copy(
                name = dto.name.ifBlank { current.name },
                enabled = dto.enabled,
                baseUrl = dto.baseUrl ?: current.baseUrl,
                balanceOption = balance ?: current.balanceOption,
                models = models,
                vertexAI = dto.vertexAI ?: current.vertexAI,
                useServiceAccount = dto.useServiceAccount ?: current.useServiceAccount,
                location = dto.location ?: current.location,
                projectId = dto.projectId ?: current.projectId,
            )
            is ProviderSetting.Claude -> current.copy(
                name = dto.name.ifBlank { current.name },
                enabled = dto.enabled,
                baseUrl = dto.baseUrl ?: current.baseUrl,
                balanceOption = balance ?: current.balanceOption,
                models = models,
                promptCaching = dto.promptCaching ?: current.promptCaching,
                promptCacheTtl = parseCacheTtl(dto.promptCacheTtl) ?: current.promptCacheTtl,
            )
        }
    }

    private fun mergeModels(current: List<Model>, dto: List<ModelConfigDto>): List<Model> {
        val byId = current.associateBy { it.id.toString() }
        val merged = dto.map { dtoModel ->
            val existing = byId[dtoModel.id]
            if (existing != null) {
                existing.copy(
                    modelId = dtoModel.modelId.ifBlank { existing.modelId },
                    displayName = dtoModel.displayName.ifBlank { existing.displayName },
                    contextLength = dtoModel.contextLength?.takeIf { it > 0 } ?: existing.contextLength,
                    type = parseModelType(dtoModel.type) ?: existing.type,
                    abilities = parseAbilities(dtoModel.abilities) ?: existing.abilities,
                    inputModalities = parseModalities(dtoModel.inputModalities) ?: existing.inputModalities,
                    outputModalities = parseModalities(dtoModel.outputModalities) ?: existing.outputModalities,
                    tools = parseBuiltInTools(dtoModel.builtInTools) ?: existing.tools,
                    // customHeaders/customBodies 保持本地（文件只有 ref）
                )
            } else {
                Model(
                    id = Uuid.random(),
                    modelId = dtoModel.modelId,
                    displayName = dtoModel.displayName,
                    contextLength = dtoModel.contextLength?.takeIf { it > 0 }
                        ?: ModelRegistry.contextLengthOrDefault(dtoModel.modelId),
                    type = parseModelType(dtoModel.type) ?: ModelType.CHAT,
                    abilities = parseAbilities(dtoModel.abilities) ?: emptyList(),
                    inputModalities = parseModalities(dtoModel.inputModalities)
                        ?: listOf(Modality.TEXT),
                    outputModalities = parseModalities(dtoModel.outputModalities)
                        ?: listOf(Modality.TEXT),
                    tools = parseBuiltInTools(dtoModel.builtInTools) ?: emptySet(),
                )
            }
        }
        val dtoIds = dto.mapTo(HashSet()) { it.id }
        return merged + current.filter { it.id.toString() !in dtoIds }
    }

    // ---- MCP ----

    private fun mergeMcp(current: McpServerConfig, dto: McpServerConfigDto): McpServerConfig {
        val common = current.commonOptions.copy(
            enable = dto.enable,
            name = dto.name.ifBlank { current.commonOptions.name },
        )
        return when (current) {
            is McpServerConfig.SseTransportServer ->
                current.copy(commonOptions = common, url = dto.url ?: current.url)
            is McpServerConfig.StreamableHTTPServer ->
                current.copy(commonOptions = common, url = dto.url ?: current.url)
        }
    }

    // ---- Assistant ----

    private fun mergeAssistant(current: Assistant, dto: AssistantConfigDto, raw: JsonObject): Assistant {
        // 字段存在性：文件里显式出现的字段才应用，缺失/为 null 的字段保留本地值，
        // 避免外部工具生成的缺字段文件把本地设置重置为 DTO 默认值。
        // 字段位于顶层 "assistant" 对象内（raw 是整个文件对象）。
        val fields = (raw["assistant"] as? JsonObject) ?: raw
        fun has(key: String) = fields.containsKey(key) && fields[key] !is JsonNull
        return current.copy(
            name = dto.name.ifBlank { current.name },
            chatModelId = if (has("chatModelRef")) {
                parseChatModelRef(dto.chatModelRef) ?: current.chatModelId
            } else {
                current.chatModelId
            },
            avatar = if (has("avatar")) parseAvatar(dto.avatar) ?: current.avatar else current.avatar,
            useAssistantAvatar = if (has("useAssistantAvatar")) dto.useAssistantAvatar else current.useAssistantAvatar,
            tags = if (has("tags")) {
                dto.tags.mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
            } else {
                current.tags
            },
            systemPrompt = dto.systemPrompt.ifBlank { current.systemPrompt },
            temperature = dto.temperature ?: current.temperature,
            topP = dto.topP ?: current.topP,
            maxTokens = dto.maxTokens ?: current.maxTokens,
            contextMessageLimit = if (has("contextMessageLimit")) dto.contextMessageLimit else current.contextMessageLimit,
            contextTokenLimit = if (has("contextTokenLimit")) dto.contextTokenLimit else current.contextTokenLimit,
            streamOutput = if (has("streamOutput")) dto.streamOutput else current.streamOutput,
            enableMemory = if (has("enableMemory")) dto.enableMemory else current.enableMemory,
            useGlobalMemory = if (has("useGlobalMemory")) dto.useGlobalMemory else current.useGlobalMemory,
            messageTemplate = if (has("messageTemplate")) {
                dto.messageTemplate.ifBlank { current.messageTemplate }
            } else {
                current.messageTemplate
            },
            presetMessages = if (has("presetMessages")) parsePresetMessages(dto.presetMessages) else current.presetMessages,
            quickMessageIds = if (has("quickMessageIds")) {
                dto.quickMessageIds.mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }.toSet()
                    .ifEmpty { current.quickMessageIds }
            } else {
                current.quickMessageIds
            },
            regexes = if (has("regexes")) parseRegexes(dto.regexes) else current.regexes,
            reasoningLevel = if (has("reasoningLevel")) {
                parseReasoningLevel(dto.reasoningLevel) ?: current.reasoningLevel
            } else {
                current.reasoningLevel
            },
            defaultMode = if (has("defaultMode")) dto.defaultMode else current.defaultMode,
            mcpServers = if (has("mcpServerIds")) {
                dto.mcpServerIds.mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }.toSet()
                    .ifEmpty { current.mcpServers }
            } else {
                current.mcpServers
            },
            localTools = if (has("localTools")) parseLocalTools(dto.localTools) else current.localTools,
            enableWebSearch = if (has("enableWebSearch")) dto.enableWebSearch else current.enableWebSearch,
            workspaceId = if (has("workspaceId")) {
                dto.workspaceId?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: current.workspaceId
            } else {
                current.workspaceId
            },
            trustedFolderProjectId = if (has("trustedFolderProjectId")) {
                dto.trustedFolderProjectId ?: current.trustedFolderProjectId
            } else {
                current.trustedFolderProjectId
            },
            defaultWorkspaceCwd = if (has("defaultWorkspaceCwd")) dto.defaultWorkspaceCwd else current.defaultWorkspaceCwd,
            background = if (has("background")) dto.background else current.background,
            backgroundOpacity = if (has("backgroundOpacity")) {
                dto.backgroundOpacity ?: current.backgroundOpacity
            } else {
                current.backgroundOpacity
            },
            useGradientBackground = if (has("useGradientBackground")) dto.useGradientBackground else current.useGradientBackground,
            modeInjectionIds = if (has("modeInjectionIds")) {
                dto.modeInjectionIds.mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }.toSet()
                    .ifEmpty { current.modeInjectionIds }
            } else {
                current.modeInjectionIds
            },
            lorebookIds = if (has("lorebookIds")) {
                dto.lorebookIds.mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }.toSet()
                    .ifEmpty { current.lorebookIds }
            } else {
                current.lorebookIds
            },
            enabledSkills = if (has("enabledSkills")) {
                dto.enabledSkills.toSet().ifEmpty { current.enabledSkills }
            } else {
                current.enabledSkills
            },
            enableTimeReminder = if (has("enableTimeReminder")) dto.enableTimeReminder else current.enableTimeReminder,
            allowConversationSystemPrompt = if (has("allowConversationSystemPrompt")) {
                dto.allowConversationSystemPrompt
            } else {
                current.allowConversationSystemPrompt
            },
            allowConversationPromptInjection = if (has("allowConversationPromptInjection")) {
                dto.allowConversationPromptInjection
            } else {
                current.allowConversationPromptInjection
            },
            knowledgeBaseIds = if (has("knowledgeBaseIds")) {
                dto.knowledgeBaseIds.mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }.toSet()
                    .ifEmpty { current.knowledgeBaseIds }
            } else {
                current.knowledgeBaseIds
            },
            enableKnowledgeQueryRewrite = if (has("enableKnowledgeQueryRewrite")) {
                dto.enableKnowledgeQueryRewrite
            } else {
                current.enableKnowledgeQueryRewrite
            },
            enabledStudyTools = if (has("enabledStudyTools")) {
                dto.enabledStudyTools.ifEmpty { current.enabledStudyTools }
            } else {
                current.enabledStudyTools
            },
            studySubject = if (has("studySubject")) {
                dto.studySubject.ifBlank { current.studySubject }
            } else {
                current.studySubject
            },
        )
    }

    // ---- 反向解析（配置文件字符串 → 领域模型） ----

    /** "providerId:modelId" → 模型 id（Uuid）；格式非法返回 null。 */
    private fun parseChatModelRef(ref: String?): Uuid? {
        if (ref.isNullOrBlank()) return null
        val modelId = ref.substringAfter(':', missingDelimiterValue = "")
        return runCatching { Uuid.parse(modelId) }.getOrNull()
    }

    /** "emoji:xxx" / "image:url" → Avatar；格式非法返回 null。 */
    private fun parseAvatar(value: String?): Avatar? {
        if (value.isNullOrBlank()) return null
        return when {
            value.startsWith("emoji:") -> Avatar.Emoji(value.removePrefix("emoji:"))
            value.startsWith("image:") -> Avatar.Image(value.removePrefix("image:"))
            else -> null
        }
    }

    /** "off"/"auto"/"low"/... → ReasoningLevel；非法返回 null。 */
    private fun parseReasoningLevel(value: String?): ReasoningLevel? {
        if (value.isNullOrBlank()) return null
        return ReasoningLevel.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }

    private fun parseRegexes(dtos: List<AssistantRegexConfigDto>): List<AssistantRegex> = dtos.map { dto ->
        AssistantRegex(
            id = runCatching { Uuid.parse(dto.id) }.getOrElse { Uuid.random() },
            name = dto.name,
            enabled = dto.enabled,
            findRegex = dto.findRegex,
            replaceString = dto.replaceString,
            affectingScope = dto.affectingScope.mapNotNull { scope ->
                AssistantAffectScope.entries.firstOrNull { it.name.equals(scope, ignoreCase = true) }
            }.toSet(),
            visualOnly = dto.visualOnly,
        )
    }

    private fun parsePresetMessages(dtos: List<PresetMessageConfigDto>): List<UIMessage> = dtos.map { dto ->
        val role = MessageRole.entries.firstOrNull { it.name.equals(dto.role, ignoreCase = true) }
            ?: MessageRole.USER
        UIMessage(
            role = role,
            parts = listOf(UIMessagePart.Text(dto.text)),
        )
    }

    private fun parseLocalTools(values: List<String>): List<LocalToolOption> = values.mapNotNull { v ->
        when (v.lowercase()) {
            "javascript_engine" -> LocalToolOption.JavascriptEngine
            "html_to_markdown" -> LocalToolOption.HtmlToMarkdown
            "time_info" -> LocalToolOption.TimeInfo
            "clipboard" -> LocalToolOption.Clipboard
            "tts" -> LocalToolOption.Tts
            "ask_user" -> LocalToolOption.AskUser
            "screen_time" -> LocalToolOption.ScreenTime
            "calendar" -> LocalToolOption.Calendar
            "device_doctor" -> LocalToolOption.DeviceDoctor
            "storage_cleaner" -> LocalToolOption.StorageCleaner
            "freeze_apps" -> LocalToolOption.FreezeApps
            else -> null
        }
    }

    // ---- 工具 ----

    private fun parseCacheTtl(value: String?): ClaudePromptCacheTtl? = when (value?.lowercase()) {
        "5m" -> ClaudePromptCacheTtl.FIVE_MINUTES
        "1h" -> ClaudePromptCacheTtl.ONE_HOUR
        else -> ClaudePromptCacheTtl.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }

    private inline fun <reified T> readIfValid(agentRoot: File, relativePath: String): T? {
        val file = File(agentRoot, relativePath)
        if (!file.isFile) return null
        return runCatching { JsonInstant.decodeFromString<T>(file.readText()) }.getOrNull()
    }

    /** DTO + 原始 JSON（字段存在性判断用）。 */
    private class AssistantFileData(val dto: AssistantConfigDto, val raw: JsonObject)
}
