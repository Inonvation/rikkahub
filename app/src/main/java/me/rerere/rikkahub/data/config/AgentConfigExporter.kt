package me.rerere.rikkahub.data.config

import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.contextLengthOrDefault
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.secret.SecretRefs
import me.rerere.rikkahub.utils.JsonInstantPretty
import java.io.File

/**
 * 把当前 [Settings]（DataStore 权威源）导出为 agent/ 统一配置（脱敏视图）。
 *
 * 关键保证：
 * - 显式 DTO 映射（非整包 JSON 拷贝）：密钥字段根本不进入 DTO，天然不可能泄露；
 *   嵌套的模型级 provider 覆盖（[Model.providerOverwrite]）同样递归脱敏；
 * - 覆盖度：Provider 余额/高级设置、模型基本+高级设置、Assistant 可见配置项
 *   （外观/上下文/正则/预设消息/本地工具/学习工具等）全部导出；
 * - 每个文件独立 try/catch，坏文件记 "error: ..." 而不会让整次导出失败；
 * - 原子写：先写 .tmp 再 rename；rename 失败（如 Windows 覆盖已存在文件）回退直接写；
 * - manifest.json 最后写，携带各文件校验状态，作为 config_view 的依据。
 *
 * 只读 MVP：本类只导出、绝不回写 DataStore；设置页路径不受任何影响。
 */
object AgentConfigExporter {

    /**
     * 导出互斥锁：页面「刷新导出」与 AI config_refresh 可能并发调用，
     * 串行化防止 .tmp 文件互相覆盖。文件写很快，持锁时间可忽略。
     */
    private val exportLock = Any()

    fun export(settings: Settings, agentRoot: File): AgentConfigExportResult =
        synchronized(exportLock) {
            exportLocked(settings, agentRoot)
        }

    private fun exportLocked(settings: Settings, agentRoot: File): AgentConfigExportResult {
        val exportedAt = System.currentTimeMillis()
        val files = LinkedHashMap<String, String>()

        fun write(relativePath: String, content: String): String {
            val target = File(agentRoot, relativePath)
            val tmp = File(target.parentFile, target.name + ".tmp")
            return runCatching {
                target.parentFile?.mkdirs()
                tmp.writeText(content, Charsets.UTF_8)
                if (!tmp.renameTo(target)) {
                    // rename 覆盖已存在文件在部分平台（Windows）会失败，回退直接写
                    target.writeText(content, Charsets.UTF_8)
                }
                "ok"
            }.getOrElse { e ->
                // 失败时清理中间产物，避免 .tmp 残留
                runCatching { tmp.delete() }
                "error: ${e.message}"
            }
        }

        files[AgentConfigPaths.PROVIDERS_FILE] = write(
            AgentConfigPaths.PROVIDERS_FILE,
            JsonInstantPretty.encodeToString(
                ProviderConfigFile(providers = settings.providers.map { it.toConfigDto() })
            ),
        )

        files[AgentConfigPaths.MCP_FILE] = write(
            AgentConfigPaths.MCP_FILE,
            JsonInstantPretty.encodeToString(
                McpConfigFile(servers = settings.mcpServers.map { it.toConfigDto() })
            ),
        )

        settings.assistants.forEach { assistant ->
            val relativePath = "${AgentConfigPaths.ASSISTANTS_DIR}/${assistant.id}.json"
            files[relativePath] = write(
                relativePath,
                JsonInstantPretty.encodeToString(
                    AssistantConfigFile(assistant = assistant.toConfigDto(settings))
                ),
            )
        }

        files[AgentConfigPaths.MANIFEST_FILE] = write(
            AgentConfigPaths.MANIFEST_FILE,
            JsonInstantPretty.encodeToString(
                AgentManifest(
                    source = "datastore",
                    settingsDataVersion = SettingsStore.CURRENT_DATA_VERSION,
                    exportedAt = exportedAt,
                    files = files.filterKeys { it != AgentConfigPaths.MANIFEST_FILE },
                )
            ),
        )

        return AgentConfigExportResult(exportedAt = exportedAt, files = files)
    }

    private fun ProviderSetting.toConfigDto(includeModels: Boolean = true): ProviderConfigDto {
        val type = when (this) {
            is ProviderSetting.OpenAI -> "openai"
            is ProviderSetting.Google -> "google"
            is ProviderSetting.Claude -> "claude"
        }
        val baseUrl = when (this) {
            is ProviderSetting.OpenAI -> baseUrl
            is ProviderSetting.Google -> baseUrl
            is ProviderSetting.Claude -> baseUrl
        }
        val authType = (this as? ProviderSetting.OpenAI)?.authType?.name?.lowercase()
        val hasSecret = when (this) {
            is ProviderSetting.OpenAI ->
                apiKey.isNotBlank() || codexCredentials?.accessToken.isNullOrBlank().not()
            is ProviderSetting.Google ->
                apiKey.isNotBlank() || privateKey.isNotBlank() || serviceAccountEmail.isNotBlank()
            is ProviderSetting.Claude -> apiKey.isNotBlank()
        }
        return ProviderConfigDto(
            id = id.toString(),
            type = type,
            name = name,
            enabled = enabled,
            builtIn = builtIn,
            baseUrl = baseUrl,
            authType = authType,
            apiKeyRef = if (hasSecret) SecretRefs.providerSecret(id.toString()) else null,
            balance = balanceOption.let {
                ProviderBalanceDto(enabled = it.enabled, apiPath = it.apiPath, resultPath = it.resultPath)
            },
            modelCount = models.size,
            models = if (includeModels) models.map { it.toConfigDto() } else emptyList(),
            chatCompletionsPath = (this as? ProviderSetting.OpenAI)?.chatCompletionsPath,
            embeddingsPath = (this as? ProviderSetting.OpenAI)?.embeddingsPath,
            rerankPath = (this as? ProviderSetting.OpenAI)?.rerankPath,
            useResponseApi = (this as? ProviderSetting.OpenAI)?.useResponseApi,
            includeHistoryReasoning = (this as? ProviderSetting.OpenAI)?.includeHistoryReasoning,
            promptCaching = (this as? ProviderSetting.Claude)?.promptCaching,
            // FIVE_MINUTES.apiValue 为 null（隐式默认），导出时归一为显式 "5m"，避免含义模糊
            promptCacheTtl = (this as? ProviderSetting.Claude)?.promptCacheTtl
                ?.let { it.apiValue ?: "5m" },
            vertexAI = (this as? ProviderSetting.Google)?.vertexAI,
            useServiceAccount = (this as? ProviderSetting.Google)?.useServiceAccount,
            location = (this as? ProviderSetting.Google)?.location,
            projectId = (this as? ProviderSetting.Google)?.projectId,
        )
    }

    private fun Model.toConfigDto(): ModelConfigDto = ModelConfigDto(
        id = id.toString(),
        modelId = modelId,
        displayName = displayName,
        type = type.name.lowercase(),
        contextLength = contextLengthOrDefault(),
        inputModalities = inputModalities.map { it.name.lowercase() }.sorted(),
        outputModalities = outputModalities.map { it.name.lowercase() }.sorted(),
        abilities = abilities.map { it.name.lowercase() }.sorted(),
        builtInTools = tools.map { it.serialName() }.sorted(),
        customHeadersRef = customHeaders.takeIf { it.isNotEmpty() }
            ?.let { SecretRefs.modelHeaders(id.toString()) },
        customBodiesRef = customBodies.takeIf { it.isNotEmpty() }
            ?.let { SecretRefs.modelBodies(id.toString()) },
        // 嵌套 provider 覆盖递归脱敏；不展开其 models，避免膨胀与深层递归
        providerOverwrite = providerOverwrite?.toConfigDto(includeModels = false),
    )

    private fun BuiltInTools.serialName(): String = when (this) {
        BuiltInTools.Search -> "search"
        BuiltInTools.UrlContext -> "url_context"
        BuiltInTools.ImageGeneration -> "image_generation"
    }

    private fun McpServerConfig.toConfigDto(): McpServerConfigDto {
        val (type, url) = when (this) {
            is McpServerConfig.SseTransportServer -> "sse" to url
            is McpServerConfig.StreamableHTTPServer -> "streamable_http" to url
        }
        val common = commonOptions
        val hasSensitive = common.headers.isNotEmpty() || common.oauth != null
        return McpServerConfigDto(
            id = id.toString(),
            name = common.name,
            type = type,
            url = url,
            enable = common.enable,
            toolCount = common.tools.size,
            oauthEnabled = common.oauth?.isAuthorized == true,
            headersRef = if (hasSensitive) SecretRefs.mcpSecrets(id.toString()) else null,
        )
    }

    private fun Assistant.toConfigDto(settings: Settings): AssistantConfigDto {
        val chatModelRef = chatModelId?.let { modelId ->
            settings.providers
                .firstOrNull { provider -> provider.models.any { it.id == modelId } }
                ?.let { provider -> "${provider.id}:$modelId" }
        }
        return AssistantConfigDto(
            id = id.toString(),
            name = name,
            chatModelRef = chatModelRef,
            systemPrompt = systemPrompt,
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            streamOutput = streamOutput,
            enableMemory = enableMemory,
            useGlobalMemory = useGlobalMemory,
            enableAutoMemory = enableAutoMemory,
            enableWebSearch = enableWebSearch,
            workspaceId = workspaceId?.toString(),
            trustedFolderProjectId = trustedFolderProjectId,
            mcpServerIds = mcpServers.map { it.toString() }.sorted(),
            enabledSkills = enabledSkills.sorted(),
            knowledgeBaseIds = knowledgeBaseIds.map { it.toString() }.sorted(),
            modeInjectionIds = modeInjectionIds.map { it.toString() }.sorted(),
            lorebookIds = lorebookIds.map { it.toString() }.sorted(),
            defaultMode = defaultMode,
            tags = tags.map { it.toString() }.sorted(),
            avatar = when (val avatarValue = avatar) {
                is Avatar.Dummy -> null
                is Avatar.Emoji -> "emoji:${avatarValue.content}"
                is Avatar.Image -> "image:${avatarValue.url}"
            },
            useAssistantAvatar = useAssistantAvatar,
            background = background,
            backgroundOpacity = backgroundOpacity,
            useGradientBackground = useGradientBackground,
            contextMessageLimit = contextMessageLimit,
            contextTokenLimit = contextTokenLimit,
            messageTemplate = messageTemplate,
            reasoningLevel = reasoningLevel.name.lowercase(),
            regexes = regexes.map {
                AssistantRegexConfigDto(
                    id = it.id.toString(),
                    name = it.name,
                    enabled = it.enabled,
                    findRegex = it.findRegex,
                    replaceString = it.replaceString,
                    affectingScope = it.affectingScope.map { scope -> scope.name.lowercase() }.sorted(),
                    visualOnly = it.visualOnly,
                )
            },
            presetMessages = presetMessages.map {
                PresetMessageConfigDto(role = it.role.name.lowercase(), text = it.toText())
            },
            localTools = localTools.map { it.serialName() }.sorted(),
            quickMessageIds = quickMessageIds.map { it.toString() }.sorted(),
            customHeadersRef = customHeaders.takeIf { it.isNotEmpty() }
                ?.let { SecretRefs.assistantHeaders(id.toString()) },
            customBodiesRef = customBodies.takeIf { it.isNotEmpty() }
                ?.let { SecretRefs.assistantBodies(id.toString()) },
            enableTimeReminder = enableTimeReminder,
            allowConversationSystemPrompt = allowConversationSystemPrompt,
            allowConversationPromptInjection = allowConversationPromptInjection,
            enableKnowledgeQueryRewrite = enableKnowledgeQueryRewrite,
            enabledStudyTools = enabledStudyTools.sorted(),
            studySubject = studySubject,
            defaultWorkspaceCwd = defaultWorkspaceCwd,
        )
    }

    private fun LocalToolOption.serialName(): String = when (this) {
        LocalToolOption.JavascriptEngine -> "javascript_engine"
        LocalToolOption.HtmlToMarkdown -> "html_to_markdown"
        LocalToolOption.TimeInfo -> "time_info"
        LocalToolOption.Clipboard -> "clipboard"
        LocalToolOption.Tts -> "tts"
        LocalToolOption.AskUser -> "ask_user"
        LocalToolOption.ScreenTime -> "screen_time"
        LocalToolOption.Calendar -> "calendar"
        LocalToolOption.DeviceDoctor -> "device_doctor"
        LocalToolOption.StorageCleaner -> "storage_cleaner"
        LocalToolOption.FreezeApps -> "freeze_apps"
    }
}
