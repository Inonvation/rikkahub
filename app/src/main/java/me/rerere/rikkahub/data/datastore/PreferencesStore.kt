package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.IOException
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.cost.CostCurrency
import me.rerere.rikkahub.data.ai.cost.ModelPricingConfig
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.PromptOptimizeDepth
import me.rerere.rikkahub.data.ai.prompts.PromptOptimizeScene
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.LEARNING_MODE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.ENGLISH_TUTOR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.MATH_TUTOR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.POLITICS_TUTOR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.MECHANICS_TUTOR_PROMPT
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV3Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV4Migration
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.toMutableStateFlow
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

private const val TAG = "PreferencesStore"

private val Context.settingsStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { context ->
        listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration(),
            PreferenceStoreV4Migration()
        )
    }
)

class SettingsStore(
    context: Context,
    scope: AppScope,
) : KoinComponent {
    companion object {
        // 版本号
        val VERSION = intPreferencesKey("data_version")
        const val CURRENT_DATA_VERSION = 4

        val ENABLE_HAPTIC_FEEDBACK = booleanPreferencesKey("enable_haptic_feedback")

        // UI设置
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

        // 模型选择
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val FAST_MODEL = stringPreferencesKey("fast_model")
        val TITLE_MODEL = stringPreferencesKey("title_model")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")
        val SUGGESTION_MODEL = stringPreferencesKey("suggestion_model")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        val TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")
        val EMBEDDING_MODEL = stringPreferencesKey("embedding_model")
        val RERANK_MODEL = stringPreferencesKey("rerank_model")
        val PROMPT_OPTIMIZE_MODEL = stringPreferencesKey("prompt_optimize_model")
        val PROMPT_OPTIMIZE_PROMPT = stringPreferencesKey("prompt_optimize_prompt")
        val PROMPT_OPTIMIZE_PROMPTS_BY_SCENE = stringPreferencesKey("prompt_optimize_prompts_by_scene")
        val PROMPT_OPTIMIZE_THINKING_BUDGET = intPreferencesKey("prompt_optimize_thinking_budget")
        val PROMPT_OPTIMIZE_THINKING_BUDGET_BY_SCENE = stringPreferencesKey("prompt_optimize_thinking_budget_by_scene")
        val PROMPT_OPTIMIZE_DEPTH_BY_SCENE = stringPreferencesKey("prompt_optimize_depth_by_scene")

        // 提供商
        val PROVIDERS = stringPreferencesKey("providers")

        // 助手
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")

        // 搜索
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")
        val SEARCH_ENABLED_SERVICES = stringPreferencesKey("search_enabled_services")

        // MCP
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")

        // WebDAV
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")

        // S3
        val S3_CONFIG = stringPreferencesKey("s3_config")

        // TTS
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")
        val DEFAULT_TTS_PLAYBACK_SPEED = floatPreferencesKey("default_tts_playback_speed")

        // ASR
        val ASR_PROVIDERS = stringPreferencesKey("asr_providers")
        val SELECTED_ASR_PROVIDER = stringPreferencesKey("selected_asr_provider")

        // Web Server
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
        val WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
        val WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
        val WEB_SERVER_LOCALHOST_ONLY = booleanPreferencesKey("web_server_localhost_only")

        // 提示词注入
        val MODE_INJECTIONS = stringPreferencesKey("mode_injections")
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        val QUICK_MESSAGES = stringPreferencesKey("quick_messages")

        // 技能排序
        val SKILL_ORDER = stringPreferencesKey("skill_order")

        // 备份提醒
        val BACKUP_REMINDER_CONFIG = stringPreferencesKey("backup_reminder_config")

        // 统计
        val LAUNCH_COUNT = intPreferencesKey("launch_count")

        // 赞助提醒
        val SPONSOR_ALERT_DISMISSED_AT = intPreferencesKey("sponsor_alert_dismissed_at")

        // 知识库文档预处理
        val PDF_OCR_ENABLED = booleanPreferencesKey("pdf_ocr_enabled")

        // 学习工具
        val STUDY_EDIT_ENABLED = booleanPreferencesKey("study_edit_enabled")
        val STUDY_DELETE_ENABLED = booleanPreferencesKey("study_delete_enabled")
        val STUDY_DELETE_APPROVAL_ENABLED = booleanPreferencesKey("study_delete_approval_enabled")
        val STUDY_STATS_ENABLED = booleanPreferencesKey("study_stats_enabled")
        val STUDY_TOOL_APPROVAL_OVERRIDES = stringPreferencesKey("study_tool_approval_overrides")

        // 子代理
        val SUB_AGENT_ENABLED = booleanPreferencesKey("enable_sub_agent")
        val SUB_AGENT_MODEL = stringPreferencesKey("sub_agent_model")
        val SUB_AGENT_TIMEOUT_SECONDS = longPreferencesKey("sub_agent_timeout_seconds")
        val SUB_AGENT_MAX_CONCURRENT = intPreferencesKey("sub_agent_max_concurrent")
        val SUB_AGENT_ALLOW_GUIDANCE = booleanPreferencesKey("sub_agent_allow_guidance")
        val SUB_AGENT_MAX_RETRIES = intPreferencesKey("sub_agent_max_retries")
        val SUB_AGENT_MAX_TOKENS = longPreferencesKey("sub_agent_max_tokens")

        // 任务计划
        val TODO_LIST_ENABLED = booleanPreferencesKey("enable_todo_list")

        // 行为层提示词
        val AGENT_BEHAVIOR_PROMPT_ENABLED = booleanPreferencesKey("enable_agent_behavior_prompt")

        // 会话费用
        val MODEL_PRICING = stringPreferencesKey("model_pricing")
        val COST_CURRENCY = stringPreferencesKey("cost_currency")
        val COST_USD_CNY_RATE = doublePreferencesKey("cost_usd_cny_rate")
    }

    private val dataStore = context.settingsStore

    val settingsFlowRaw = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }.map { preferences ->
            Settings(
                favoriteModels = preferences[FAVORITE_MODELS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                chatModelId = preferences[SELECT_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                fastModelId = preferences[FAST_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                titleModelId = preferences[TITLE_MODEL]?.let { Uuid.parse(it) },
                translateModeId = preferences[TRANSLATE_MODEL]?.let { Uuid.parse(it) }
                    ?: DEFAULT_AUTO_MODEL_ID,
                enableSuggestion = preferences[ENABLE_SUGGESTION] != false,
                suggestionModelId = preferences[SUGGESTION_MODEL]?.let { Uuid.parse(it) },
                imageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
                translatePrompt = preferences[TRANSLATION_PROMPT] ?: DEFAULT_TRANSLATION_PROMPT,
                translateThinkingBudget = preferences[TRANSLATE_THINKING_BUDGET] ?: 0,
                suggestionPrompt = preferences[SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
                ocrModelId = preferences[OCR_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                ocrPrompt = preferences[OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
                compressModelId = preferences[COMPRESS_MODEL]?.let { Uuid.parse(it) } ?: DEFAULT_AUTO_MODEL_ID,
                compressPrompt = preferences[COMPRESS_PROMPT] ?: DEFAULT_COMPRESS_PROMPT,
                embeddingModelId = preferences[EMBEDDING_MODEL]?.let { Uuid.parse(it) },
                rerankModelId = preferences[RERANK_MODEL]?.let { Uuid.parse(it) },
                promptOptimizeModelId = preferences[PROMPT_OPTIMIZE_MODEL]?.let { Uuid.parse(it) },
                promptOptimizePrompt = preferences[PROMPT_OPTIMIZE_PROMPT],
                promptOptimizePromptsByScene = preferences[PROMPT_OPTIMIZE_PROMPTS_BY_SCENE]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyMap(),
                promptOptimizeThinkingBudget = preferences[PROMPT_OPTIMIZE_THINKING_BUDGET] ?: 0,
                promptOptimizeThinkingBudgetByScene = preferences[PROMPT_OPTIMIZE_THINKING_BUDGET_BY_SCENE]?.let {
                    JsonInstant.decodeFromString<Map<String, Int>>(it)
                } ?: emptyMap(),
                promptOptimizeDepthByScene = preferences[PROMPT_OPTIMIZE_DEPTH_BY_SCENE]?.let {
                    JsonInstant.decodeFromString<Map<String, String>>(it)
                } ?: emptyMap(),
                assistantId = preferences[SELECT_ASSISTANT]?.let { Uuid.parse(it) }
                    ?: DEFAULT_ASSISTANT_ID,
                assistantTags = preferences[ASSISTANT_TAGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                providers = JsonInstant.decodeFromString(preferences[PROVIDERS] ?: "[]"),
                assistants = JsonInstant.decodeFromString(preferences[ASSISTANTS] ?: "[]"),
                dynamicColor = preferences[DYNAMIC_COLOR] != false,
                themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
                customThemes = preferences[CUSTOM_THEMES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                developerMode = preferences[DEVELOPER_MODE] == true,
                displaySetting = JsonInstant.decodeFromString(preferences[DISPLAY_SETTING] ?: "{}"),
                searchServices = preferences[SEARCH_SERVICES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: listOf(SearchServiceOptions.DEFAULT),
                searchCommonOptions = preferences[SEARCH_COMMON]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: SearchCommonOptions(),
                searchServiceSelected = preferences[SEARCH_SELECTED] ?: 0,
                enabledSearchServiceIds = preferences[SEARCH_ENABLED_SERVICES]?.let {
                    JsonInstant.decodeFromString<List<kotlin.uuid.Uuid>>(it)
                } ?: emptyList(),
                mcpServers = preferences[MCP_SERVERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webDavConfig = preferences[WEBDAV_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: WebDavConfig(),
                s3Config = preferences[S3_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: S3Config(),
                ttsProviders = preferences[TTS_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedTTSProviderId = preferences[SELECTED_TTS_PROVIDER]?.let { Uuid.parse(it) }
                    ?: DEFAULT_SYSTEM_TTS_ID,
                defaultTTSPlaybackSpeed = preferences[DEFAULT_TTS_PLAYBACK_SPEED]?.coerceIn(0.5f, 2.0f) ?: 1.0f,
                asrProviders = preferences[ASR_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedASRProviderId = preferences[SELECTED_ASR_PROVIDER]?.let { Uuid.parse(it) },
                modeInjections = preferences[MODE_INJECTIONS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                lorebooks = preferences[LOREBOOKS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                quickMessages = preferences[QUICK_MESSAGES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                skillOrder = preferences[SKILL_ORDER]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webServerEnabled = preferences[WEB_SERVER_ENABLED] == true,
                webServerPort = preferences[WEB_SERVER_PORT] ?: 8080,
                webServerJwtEnabled = preferences[WEB_SERVER_JWT_ENABLED] == true,
                webServerAccessPassword = preferences[WEB_SERVER_ACCESS_PASSWORD] ?: "",
                webServerLocalhostOnly = preferences[WEB_SERVER_LOCALHOST_ONLY] == true,
                backupReminderConfig = preferences[BACKUP_REMINDER_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: BackupReminderConfig(),
                launchCount = preferences[LAUNCH_COUNT] ?: 0,
                sponsorAlertDismissedAt = preferences[SPONSOR_ALERT_DISMISSED_AT] ?: 0,
                pdfOcrEnabled = preferences[PDF_OCR_ENABLED] == true,
                studyEditEnabled = preferences[STUDY_EDIT_ENABLED] != false,
                studyDeleteEnabled = preferences[STUDY_DELETE_ENABLED] == true,
                studyDeleteApprovalEnabled = preferences[STUDY_DELETE_APPROVAL_ENABLED] != false,
                studyStatsEnabled = preferences[STUDY_STATS_ENABLED] != false,
                studyToolApprovalOverrides = preferences[STUDY_TOOL_APPROVAL_OVERRIDES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyMap(),
                enableSubAgent = preferences[SUB_AGENT_ENABLED] == true,
                subAgentModelId = preferences[SUB_AGENT_MODEL]?.let { Uuid.parse(it) },
                subAgentTimeoutSeconds = preferences[SUB_AGENT_TIMEOUT_SECONDS]?.takeIf { it > 0 },
                subAgentMaxConcurrent = (preferences[SUB_AGENT_MAX_CONCURRENT] ?: 5).coerceIn(1, 64),
                subAgentAllowGuidance = preferences[SUB_AGENT_ALLOW_GUIDANCE] == true,
                subAgentMaxRetries = (preferences[SUB_AGENT_MAX_RETRIES] ?: 1).coerceIn(0, 3),
                subAgentMaxTokens = preferences[SUB_AGENT_MAX_TOKENS]?.takeIf { it > 0 },
                enableAgentBehaviorPrompt = preferences[AGENT_BEHAVIOR_PROMPT_ENABLED] != false,
                enableTodoList = preferences[TODO_LIST_ENABLED] != false,
                costCurrency = preferences[COST_CURRENCY]?.let {
                    runCatching { CostCurrency.valueOf(it) }.getOrNull()
                } ?: CostCurrency.RMB,
                costUsdCnyRate = preferences[COST_USD_CNY_RATE]?.takeIf { it > 0 } ?: 7.2,
                modelPricingOverrides = preferences[MODEL_PRICING]?.let {
                    runCatching { JsonInstant.decodeFromString<List<ModelPricingConfig>>(it) }.getOrNull()
                } ?: emptyList(),
            )
        }
        .map {
            var providers = it.providers.ifEmpty { DEFAULT_PROVIDERS }.toMutableList()
            DEFAULT_PROVIDERS.forEach { defaultProvider ->
                if (providers.none { it.id == defaultProvider.id }) {
                    providers.add(defaultProvider.copyProvider())
                }
            }
            providers = providers.map { provider ->
                val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
                if (defaultProvider != null) {
                    provider.copyProvider(
                        builtIn = defaultProvider.builtIn,
                        description = defaultProvider.description,
                        shortDescription = defaultProvider.shortDescription,
                    )
                } else provider
            }.toMutableList()
            val assistants = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
            DEFAULT_ASSISTANTS.forEach { defaultAssistant ->
                if (assistants.none { it.id == defaultAssistant.id }) {
                    assistants.add(defaultAssistant.copy())
                }
            }
            val ttsProviders = it.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
            DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
                if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
                    ttsProviders.add(defaultTTSProvider.copyProvider())
                }
            }
            it.copy(
                providers = providers,
                assistants = assistants,
                ttsProviders = ttsProviders,
            )
        }
        .map { settings ->
            // 去重并清理无效引用
            val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
            val validModeInjectionIds = settings.modeInjections.map { it.id }.toSet()
            val validLorebookIds = settings.lorebooks.map { it.id }.toSet()
            val validQuickMessageIds = settings.quickMessages.map { it.id }.toSet()
            val asrProviders = settings.asrProviders.distinctBy { it.id }
            settings.copy(
                providers = settings.providers.distinctBy { it.id }.map { provider ->
                    when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Google -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Claude -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )
                    }
                },
                assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
                    assistant.copy(
                        // 过滤掉不存在的 MCP 服务器 ID
                        mcpServers = assistant.mcpServers.filter { serverId ->
                            serverId in validMcpServerIds
                        }.toSet(),
                        // 过滤掉不存在的模式注入 ID
                        modeInjectionIds = assistant.modeInjectionIds.filter { id ->
                            id in validModeInjectionIds
                        }.toSet(),
                        // 过滤掉不存在的 Lorebook ID
                        lorebookIds = assistant.lorebookIds.filter { id ->
                            id in validLorebookIds
                        }.toSet(),
                        // 过滤掉不存在的快捷消息 ID
                        quickMessageIds = assistant.quickMessageIds.filter { id ->
                            id in validQuickMessageIds
                        }.toSet()
                    )
                },
                ttsProviders = settings.ttsProviders.distinctBy { it.id },
                asrProviders = asrProviders,
                selectedASRProviderId = settings.selectedASRProviderId
                    ?.takeIf { id -> asrProviders.any { provider -> provider.id == id } }
                    ?: asrProviders.firstOrNull()?.id,
                favoriteModels = settings.favoriteModels.filter { uuid ->
                    settings.providers.flatMap { it.models }.any { it.id == uuid }
                },
                modeInjections = settings.modeInjections.distinctBy { it.id },
                lorebooks = settings.lorebooks.distinctBy { it.id },
                quickMessages = settings.quickMessages.distinctBy { it.id },
                enabledSearchServiceIds = settings.enabledSearchServiceIds
                    .filter { id -> settings.searchServices.any { it.id == id } }
                    .takeIf { it.isNotEmpty() }
                    ?: settings.searchServices.getOrNull(settings.searchServiceSelected)?.let { listOf(it.id) }
                    ?: settings.searchServices.firstOrNull()?.let { listOf(it.id) }
                    ?: emptyList(),
            )
        }
        .onEach {
            get<PebbleEngine>().templateCache.invalidateAll()
        }

    val settingsFlow = settingsFlowRaw
        .distinctUntilChanged()
        .toMutableStateFlow(scope, Settings.dummy())

    suspend fun update(settings: Settings) {
        if(settings.init) {
            Log.w(TAG, "Cannot update dummy settings")
            return
        }
        settingsFlow.value = settings
        dataStore.edit { preferences ->
            preferences[ENABLE_HAPTIC_FEEDBACK] = settings.displaySetting.enableHapticFeedback
            preferences[DYNAMIC_COLOR] = settings.dynamicColor
            preferences[THEME_ID] = settings.themeId
            preferences[CUSTOM_THEMES] = JsonInstant.encodeToString(settings.customThemes)
            preferences[DEVELOPER_MODE] = settings.developerMode
            preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(settings.displaySetting)

            preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(settings.favoriteModels)
            preferences[SELECT_MODEL] = settings.chatModelId.toString()
            preferences[FAST_MODEL] = settings.fastModelId.toString()
            settings.titleModelId?.let {
                preferences[TITLE_MODEL] = it.toString()
            } ?: preferences.remove(TITLE_MODEL)
            preferences[TRANSLATE_MODEL] = settings.translateModeId.toString()
            preferences[ENABLE_SUGGESTION] = settings.enableSuggestion
            settings.suggestionModelId?.let {
                preferences[SUGGESTION_MODEL] = it.toString()
            } ?: preferences.remove(SUGGESTION_MODEL)
            preferences[IMAGE_GENERATION_MODEL] = settings.imageGenerationModelId.toString()
            preferences[TITLE_PROMPT] = settings.titlePrompt
            preferences[TRANSLATION_PROMPT] = settings.translatePrompt
            preferences[TRANSLATE_THINKING_BUDGET] = settings.translateThinkingBudget
            preferences[SUGGESTION_PROMPT] = settings.suggestionPrompt
            preferences[OCR_MODEL] = settings.ocrModelId.toString()
            preferences[OCR_PROMPT] = settings.ocrPrompt
            preferences[COMPRESS_MODEL] = settings.compressModelId.toString()
            preferences[COMPRESS_PROMPT] = settings.compressPrompt
            settings.embeddingModelId?.let { preferences[EMBEDDING_MODEL] = it.toString() }
            settings.rerankModelId?.let { preferences[RERANK_MODEL] = it.toString() }
            settings.promptOptimizeModelId?.let { preferences[PROMPT_OPTIMIZE_MODEL] = it.toString() }
                ?: preferences.remove(PROMPT_OPTIMIZE_MODEL)
            settings.promptOptimizePrompt?.let { preferences[PROMPT_OPTIMIZE_PROMPT] = it }
                ?: preferences.remove(PROMPT_OPTIMIZE_PROMPT)
            preferences[PROMPT_OPTIMIZE_PROMPTS_BY_SCENE] = JsonInstant.encodeToString(settings.promptOptimizePromptsByScene)
            preferences[PROMPT_OPTIMIZE_THINKING_BUDGET] = settings.promptOptimizeThinkingBudget
            preferences[PROMPT_OPTIMIZE_THINKING_BUDGET_BY_SCENE] = JsonInstant.encodeToString(settings.promptOptimizeThinkingBudgetByScene)
            preferences[PROMPT_OPTIMIZE_DEPTH_BY_SCENE] = JsonInstant.encodeToString(settings.promptOptimizeDepthByScene)

            preferences[PROVIDERS] = JsonInstant.encodeToString(settings.providers)

            preferences[ASSISTANTS] = JsonInstant.encodeToString(settings.assistants)
            preferences[SELECT_ASSISTANT] = settings.assistantId.toString()
            preferences[ASSISTANT_TAGS] = JsonInstant.encodeToString(settings.assistantTags)

            preferences[SEARCH_SERVICES] = JsonInstant.encodeToString(settings.searchServices)
            preferences[SEARCH_COMMON] = JsonInstant.encodeToString(settings.searchCommonOptions)
            preferences[SEARCH_SELECTED] = settings.searchServiceSelected.coerceIn(0, settings.searchServices.size - 1)
            preferences[SEARCH_ENABLED_SERVICES] = JsonInstant.encodeToString(
                settings.enabledSearchServiceIds.ifEmpty {
                    settings.searchServices.getOrNull(settings.searchServiceSelected)?.let { listOf(it.id) }
                        ?: settings.searchServices.firstOrNull()?.let { listOf(it.id) } ?: emptyList()
                }
            )

            preferences[MCP_SERVERS] = JsonInstant.encodeToString(settings.mcpServers)
            preferences[WEBDAV_CONFIG] = JsonInstant.encodeToString(settings.webDavConfig)
            preferences[S3_CONFIG] = JsonInstant.encodeToString(settings.s3Config)
            preferences[TTS_PROVIDERS] = JsonInstant.encodeToString(settings.ttsProviders)
            settings.selectedTTSProviderId?.let {
                preferences[SELECTED_TTS_PROVIDER] = it.toString()
            } ?: preferences.remove(SELECTED_TTS_PROVIDER)
            preferences[DEFAULT_TTS_PLAYBACK_SPEED] = settings.defaultTTSPlaybackSpeed.coerceIn(0.5f, 2.0f)
            preferences[ASR_PROVIDERS] = JsonInstant.encodeToString(settings.asrProviders)
            settings.selectedASRProviderId?.let {
                preferences[SELECTED_ASR_PROVIDER] = it.toString()
            } ?: preferences.remove(SELECTED_ASR_PROVIDER)
            preferences[MODE_INJECTIONS] = JsonInstant.encodeToString(settings.modeInjections)
            preferences[LOREBOOKS] = JsonInstant.encodeToString(settings.lorebooks)
            preferences[QUICK_MESSAGES] = JsonInstant.encodeToString(settings.quickMessages)
            preferences[SKILL_ORDER] = JsonInstant.encodeToString(settings.skillOrder)
            preferences[WEB_SERVER_ENABLED] = settings.webServerEnabled
            preferences[WEB_SERVER_PORT] = settings.webServerPort
            preferences[WEB_SERVER_JWT_ENABLED] = settings.webServerJwtEnabled
            preferences[WEB_SERVER_ACCESS_PASSWORD] = settings.webServerAccessPassword
            preferences[WEB_SERVER_LOCALHOST_ONLY] = settings.webServerLocalhostOnly
            preferences[BACKUP_REMINDER_CONFIG] = JsonInstant.encodeToString(settings.backupReminderConfig)
            preferences[LAUNCH_COUNT] = settings.launchCount
            preferences[SPONSOR_ALERT_DISMISSED_AT] = settings.sponsorAlertDismissedAt
            preferences[PDF_OCR_ENABLED] = settings.pdfOcrEnabled
            preferences[STUDY_EDIT_ENABLED] = settings.studyEditEnabled
            preferences[STUDY_DELETE_ENABLED] = settings.studyDeleteEnabled
            preferences[STUDY_DELETE_APPROVAL_ENABLED] = settings.studyDeleteApprovalEnabled
            preferences[STUDY_STATS_ENABLED] = settings.studyStatsEnabled
            preferences[STUDY_TOOL_APPROVAL_OVERRIDES] = JsonInstant.encodeToString(settings.studyToolApprovalOverrides)
            preferences[SUB_AGENT_ENABLED] = settings.enableSubAgent
            settings.subAgentModelId?.let {
                preferences[SUB_AGENT_MODEL] = it.toString()
            } ?: preferences.remove(SUB_AGENT_MODEL)
            settings.subAgentTimeoutSeconds?.takeIf { it > 0 }?.let {
                preferences[SUB_AGENT_TIMEOUT_SECONDS] = it
            } ?: preferences.remove(SUB_AGENT_TIMEOUT_SECONDS)
            preferences[SUB_AGENT_MAX_CONCURRENT] = settings.subAgentMaxConcurrent.coerceIn(1, 64)
            preferences[SUB_AGENT_ALLOW_GUIDANCE] = settings.subAgentAllowGuidance
            preferences[SUB_AGENT_MAX_RETRIES] = settings.subAgentMaxRetries.coerceIn(0, 3)
            settings.subAgentMaxTokens?.takeIf { it > 0 }?.let {
                preferences[SUB_AGENT_MAX_TOKENS] = it
            } ?: preferences.remove(SUB_AGENT_MAX_TOKENS)
            preferences[AGENT_BEHAVIOR_PROMPT_ENABLED] = settings.enableAgentBehaviorPrompt
            preferences[TODO_LIST_ENABLED] = settings.enableTodoList
            preferences[COST_CURRENCY] = settings.costCurrency.name
            preferences[COST_USD_CNY_RATE] = settings.costUsdCnyRate.coerceAtLeast(1.0)
            preferences[MODEL_PRICING] = JsonInstant.encodeToString(settings.modelPricingOverrides)
            preferences[VERSION] = CURRENT_DATA_VERSION
        }
    }

    suspend fun update(fn: (Settings) -> Settings) {
        update(fn(settingsFlow.value))
    }

    suspend fun updateAssistant(assistantId: Uuid) {
        dataStore.edit { preferences ->
            preferences[SELECT_ASSISTANT] = assistantId.toString()
        }
    }

    suspend fun updateAssistantModel(assistantId: Uuid, modelId: Uuid) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(chatModelId = modelId)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantReasoningLevel(assistantId: Uuid, reasoningLevel: ReasoningLevel) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(reasoningLevel = reasoningLevel)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantWebSearch(assistantId: Uuid, enabled: Boolean) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(enableWebSearch = enabled)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantKnowledgeQueryRewrite(assistantId: Uuid, enabled: Boolean) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(enableKnowledgeQueryRewrite = enabled)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantMcpServers(assistantId: Uuid, mcpServers: Set<Uuid>) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(mcpServers = mcpServers)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    suspend fun updateAssistantInjections(
        assistantId: Uuid,
        modeInjectionIds: Set<Uuid>,
        lorebookIds: Set<Uuid>,
        quickMessageIds: Set<Uuid> = emptySet(),
    ) {
        update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.id == assistantId) {
                        assistant.copy(
                            modeInjectionIds = modeInjectionIds,
                            lorebookIds = lorebookIds,
                            quickMessageIds = quickMessageIds,
                        )
                    } else {
                        assistant
                    }
                }
            )
        }
    }
}

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val customThemes: List<CustomTheme> = emptyList(),
    val developerMode: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val fastModelId: Uuid = Uuid.random(),
    val titleModelId: Uuid? = null,
    val imageGenerationModelId: Uuid = Uuid.random(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val translateThinkingBudget: Int = 0,
    val enableSuggestion: Boolean = true,
    val suggestionModelId: Uuid? = null,
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val compressModelId: Uuid = Uuid.random(),
    val compressPrompt: String = DEFAULT_COMPRESS_PROMPT,
    val embeddingModelId: Uuid? = null,
    val rerankModelId: Uuid? = null,
    val promptOptimizeModelId: Uuid? = null,
    val promptOptimizePrompt: String? = null,
    val promptOptimizePromptsByScene: Map<String, String> = emptyMap(),
    val promptOptimizeThinkingBudget: Int = 0,
    val promptOptimizeThinkingBudgetByScene: Map<String, Int> = emptyMap(),
    val promptOptimizeDepthByScene: Map<String, String> = emptyMap(),
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val enabledSearchServiceIds: List<kotlin.uuid.Uuid> = emptyList(),
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val s3Config: S3Config = S3Config(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val defaultTTSPlaybackSpeed: Float = 1.0f,
    val asrProviders: List<ASRProviderSetting> = emptyList(),
    val selectedASRProviderId: Uuid? = null,
    val modeInjections: List<PromptInjection.ModeInjection> = DEFAULT_MODE_INJECTIONS,
    val lorebooks: List<Lorebook> = emptyList(),
    val quickMessages: List<QuickMessage> = emptyList(),
    val skillOrder: List<String> = emptyList(),
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerLocalhostOnly: Boolean = false,
    val backupReminderConfig: BackupReminderConfig = BackupReminderConfig(),
    val launchCount: Int = 0,
    val sponsorAlertDismissedAt: Int = 0,
    val pdfOcrEnabled: Boolean = false,
    val studyEditEnabled: Boolean = true,
    val studyDeleteEnabled: Boolean = false,
    val studyDeleteApprovalEnabled: Boolean = true,
    val studyStatsEnabled: Boolean = true,
    val studyToolApprovalOverrides: Map<String, Boolean> = emptyMap(),
    val enableTodoList: Boolean = true,
    val enableSubAgent: Boolean = false,
    val subAgentModelId: Uuid? = null,
    val subAgentTimeoutSeconds: Long? = null,
    val subAgentMaxConcurrent: Int = 5,
    val subAgentAllowGuidance: Boolean = false,
    /** 子代理超时/瞬态失败自动重试次数（0..3，默认 1） */
    val subAgentMaxRetries: Int = 1,
    /** per-task token 预算（null=不限）。累计 usage 超限置 TOKEN_LIMIT 终止 */
    val subAgentMaxTokens: Long? = null,
    val enableAgentBehaviorPrompt: Boolean = true,
    /** 会话费用显示货币（默认人民币，用户可在费用配置窗修改，全局持久化） */
    val costCurrency: CostCurrency = CostCurrency.RMB,
    /** RMB 显示时的美元→人民币汇率 */
    val costUsdCnyRate: Double = 7.2,
    /** 用户自定的模型定价覆盖（精确 modelId 匹配，优先于内置预置表） */
    val modelPricingOverrides: List<ModelPricingConfig> = emptyList(),
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

@Serializable
enum class ChatFontFamily {
    @SerialName("default")
    DEFAULT,
    @SerialName("serif")
    SERIF,
    @SerialName("monospace")
    MONOSPACE,

    @SerialName("custom")
    CUSTOM,
}

@Serializable
data class DisplaySetting(
    val enableHapticFeedback: Boolean = true,
    val enableUiHapticFeedback: Boolean = true,
    val createNewConversationOnStart: Boolean = true,
    val enableMessageGenerationHapticEffect: Boolean = false,
    val enableMessageGenerationStartedAndFinishedHapticEffect: Boolean = false,
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val useAppIconStyleLoadingIndicator: Boolean = true,
    val showUserAvatar: Boolean = true,
    val showAssistantBubble: Boolean = false,
    val bubbleOpacity: Float = 1.0f,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showDateTimeInMessage: Boolean = false,
    val showTokenUsage: Boolean = true,
    val showThinkingContent: Boolean = true,
    val autoCloseThinking: Boolean = true,
    val doubleTapCollapseThinking: Boolean = true,
    val showMessageJumper: Boolean = true,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val skipCropImage: Boolean = true,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdateNotification: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = false,
    val showLineNumbers: Boolean = false,
    val ttsOnlyReadQuoted: Boolean = false,
    val ttsOnlyReadOutsideBrackets: Boolean = false,
    val autoPlayTTSAfterGeneration: Boolean = false,
    val pasteLongTextAsFile: Boolean = false,
    val pasteLongTextThreshold: Int = 1000,
    val sendOnEnter: Boolean = false,
    val enableAutoScroll: Boolean = true,
    val enableLatexRendering: Boolean = true,
    val enableBlurEffect: Boolean = false,
    val chatFontFamily: ChatFontFamily = ChatFontFamily.DEFAULT,
    val chatCustomFontPath: String = "",
    val chatCustomFontName: String = "",
    val enableVolumeKeyScroll: Boolean = false,
    val volumeKeyScrollRatio: Float = 1.0f,
)

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "rikkahub_backups",
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES
    ),
) {
    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
    }
}

@Serializable
data class BackupReminderConfig(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val lastBackupTime: Long = 0L,
)

/** 读取某场景的自定义优化模板：优先取按场景存储的，fallback 到旧版全局模板 */
internal fun Settings.promptOptimizePromptForScene(scene: PromptOptimizeScene): String? =
    promptOptimizePromptsByScene[scene.code] ?: promptOptimizePrompt

/** 某场景的思考预算（token）：按场景存储优先，fallback 到旧版全局字段 */
internal fun Settings.promptOptimizeThinkingBudgetForScene(scene: PromptOptimizeScene): Int =
    promptOptimizeThinkingBudgetByScene[scene.code] ?: promptOptimizeThinkingBudget

/** 设置某场景的思考预算；仅当与全局值相同时清空该场景条目（回退全局）。
 *  注意：AUTO=-1 / OFF=0 是合法取值，不能因为 budget<=0 就清空，否则选 AUTO/OFF 会被静默回退成全局值。 */
internal fun Settings.withPromptOptimizeThinkingBudget(scene: PromptOptimizeScene, budget: Int): Settings {
    val newMap = if (budget == promptOptimizeThinkingBudget) {
        promptOptimizeThinkingBudgetByScene - scene.code
    } else {
        promptOptimizeThinkingBudgetByScene + (scene.code to budget)
    }
    return copy(promptOptimizeThinkingBudgetByScene = newMap)
}

/** 保存某场景的自定义优化模板；空串/空白表示清除该场景自定义，回退内置提示词 */
internal fun Settings.withPromptOptimizePrompt(scene: PromptOptimizeScene, prompt: String): Settings {
    val trimmed = prompt.trim()
    val newMap = if (trimmed.isBlank()) {
        promptOptimizePromptsByScene - scene.code
    } else {
        promptOptimizePromptsByScene + (scene.code to trimmed)
    }
    return copy(promptOptimizePromptsByScene = newMap)
}

/** 某场景的优化深度（精简/中等/详细）；未按场景配置时回退默认【精简】 */
internal fun Settings.promptOptimizeDepthForScene(scene: PromptOptimizeScene): PromptOptimizeDepth {
    val code = promptOptimizeDepthByScene[scene.code]
    return PromptOptimizeDepth.entries.firstOrNull { it.code == code } ?: PromptOptimizeDepth.CONCISE
}

/** 保存某场景的优化深度；当所选深度恰为默认【精简】时清空该场景条目（回退默认） */
internal fun Settings.withPromptOptimizeDepth(scene: PromptOptimizeScene, depth: PromptOptimizeDepth): Settings {
    val newMap = if (depth == PromptOptimizeDepth.CONCISE) {
        promptOptimizeDepthByScene - scene.code
    } else {
        promptOptimizeDepthByScene + (scene.code to depth.code)
    }
    return copy(promptOptimizeDepthByScene = newMap)
}

fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

fun Settings.findModelById(uuid: Uuid?, fallback: Uuid? = null): Model? {
    if (uuid == null && fallback == null) return null
    return uuid?.let { this.providers.findModelById(it) }
        ?: fallback?.let { this.providers.findModelById(it) }
}

fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

fun Settings.getCurrentChatModel(): Model? {
    return findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
}

fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == assistantId } ?: this.assistants.first()
}

fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
}

fun Settings.getQuickMessagesOfAssistant(assistant: Assistant) =
    quickMessages.filter { it.id in assistant.quickMessageIds }

fun Settings.getSelectedTTSProvider(): TTSProviderSetting? {
    return selectedTTSProviderId?.let { id ->
        ttsProviders.find { it.id == id }
    } ?: ttsProviders.firstOrNull()
}

fun Settings.getSelectedASRProvider(): ASRProviderSetting? {
    return selectedASRProviderId?.let { id ->
        asrProviders.find { it.id == id }
    } ?: asrProviders.firstOrNull()
}

fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(models = emptyList())
    }
    return provider
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}

internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")

// 学科助手固定 UUID
internal val ENGLISH_TUTOR_ID = Uuid.parse("e1a2b3c4-d5e6-f7a8-b9c0-d1e2f3a4b5c6")
internal val MATH_TUTOR_ID = Uuid.parse("f1a2b3c4-d5e6-f7a8-b9c0-d1e2f3a4b5c7")
internal val POLITICS_TUTOR_ID = Uuid.parse("a1a2b3c4-d5e6-f7a8-b9c0-d1e2f3a4b5c8")
internal val MECHANICS_TUTOR_ID = Uuid.parse("b1a2b3c4-d5e6-f7a8-b9c0-d1e2f3a4b5c9")
internal val DAILY_CHAT_ID = Uuid.parse("c1a2b3c4-d5e6-f7a8-b9c0-d1e2f3a4b5c0")

internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "",
        systemPrompt = ""
    ),
    Assistant(
        id = Uuid.parse("3d47790c-c415-4b90-9388-751128adb0a0"),
        name = "",
        systemPrompt = """
            You are {{char}}, based on {{model_name}}. Current date: {{cur_date}}. User: {{user}}.

            Reply in the user's language. Use Markdown for formatting, LaTeX for math.
        """.trimIndent()
    ),
    // 英语导师
    Assistant(
        id = ENGLISH_TUTOR_ID,
        name = "英语导师",
        systemPrompt = ENGLISH_TUTOR_PROMPT,
        temperature = 0.3f,
        contextMessageLimit = 20,
        enableTimeReminder = false,
        localTools = listOf(),
        enabledStudyTools = listOf("save_vocabulary", "save_note"),
        studySubject = "english",
    ),
    // 数学导师
    Assistant(
        id = MATH_TUTOR_ID,
        name = "数学导师",
        systemPrompt = MATH_TUTOR_PROMPT,
        temperature = 0.3f,
        reasoningLevel = me.rerere.ai.core.ReasoningLevel.HIGH,
        contextMessageLimit = 20,
        enableTimeReminder = false,
        localTools = listOf(),
        enabledStudyTools = listOf("save_wrong_question", "save_note"),
        studySubject = "math",
    ),
    // 政治导师
    Assistant(
        id = POLITICS_TUTOR_ID,
        name = "政治导师",
        systemPrompt = POLITICS_TUTOR_PROMPT,
        temperature = 0.3f,
        contextMessageLimit = 20,
        enableTimeReminder = false,
        localTools = listOf(),
        enabledStudyTools = listOf("save_note", "save_knowledge_card", "quiz_user"),
        studySubject = "politics",
    ),
    // 机械原理导师
    Assistant(
        id = MECHANICS_TUTOR_ID,
        name = "机械原理导师",
        systemPrompt = MECHANICS_TUTOR_PROMPT,
        temperature = 0.3f,
        reasoningLevel = me.rerere.ai.core.ReasoningLevel.HIGH,
        contextMessageLimit = 20,
        enableTimeReminder = false,
        localTools = listOf(),
        enabledStudyTools = listOf("save_wrong_question", "save_note", "save_knowledge_card", "quiz_user"),
        studySubject = "mechanics",
    ),
    // 日常聊天
    Assistant(
        id = DAILY_CHAT_ID,
        name = "日常聊天",
        systemPrompt = """
            You are a friendly and supportive companion. Chat naturally with the user in Chinese.

            ## About the User
            The user is a graduate entrance exam (考研) student. They may be stressed or tired from studying.
            Be encouraging, warm, and understanding. Occasionally check in on how they're feeling.

            ## Style
            - Natural, conversational Chinese
            - Warm but not overly enthusiastic
            - Use occasional emoji, but don't overdo it
            - Keep responses concise (2-4 sentences usually)
            - Feel free to use humor when appropriate
            - Remember details about the user (via memory) to build rapport
        """.trimIndent(),
        temperature = 0.8f,
        enableMemory = true,
        enableTimeReminder = false,
        localTools = listOf(),
    ),
)

val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
private val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(
        id = DEFAULT_SYSTEM_TTS_ID,
        name = "",
    ),
    TTSProviderSetting.OpenAI(
        id = Uuid.parse("e36b22ef-ca82-40ab-9e70-60cad861911c"),
        name = "AiHubMix",
        baseUrl = "https://aihubmix.com/v1",
        model = "gpt-4o-mini-tts",
        voice = "alloy",
    )
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }

val DEFAULT_MODE_INJECTIONS = listOf(
    PromptInjection.ModeInjection(
        id = Uuid.parse("b87eaf16-f5cd-4ac1-9e4f-b11ae3a61d74"),
        content = LEARNING_MODE_PROMPT,
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "Learning Mode"
    ),
    PromptInjection.ModeInjection(
        id = Uuid.parse("e1e2e3e4-d5e6-f7a8-b9c0-d1e2f3a4b5c6"),
        content = """
            English study mode active. Be concise and direct — no filler words.
            - Word lookup: **{word}** /{pronunciation}/, definitions with Chinese, examples, memory aid, collocations, 考研提示. Call `save_vocabulary` after.
            - Translation: direct + 2-3 alternatives + key vocabulary. No preamble.
            - Exam questions: identify type → guide step-by-step → explain reasoning → summarize.
            - Grammar in Chinese, rest in English. Use Markdown.
        """.trimIndent(),
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "英语学习模式"
    ),
    PromptInjection.ModeInjection(
        id = Uuid.parse("f1e2e3e4-d5e6-f7a8-b9c0-d1e2f3a4b5c7"),
        content = """
            Math problem-solving mode active. Reply in Chinese, use LaTeX for all formulas.
            - 考点定位: knowledge point / importance(★) / common question type
            - 分步推导: step-by-step with theorem citations (e.g. "根据拉格朗日中值定理")
            - 最终答案: $$\boxed{answer}$$, then 易错点提示
            - Call `save_wrong_question` for representative problems, `save_note` for techniques.
        """.trimIndent(),
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "数学解题模式"
    ),
    PromptInjection.ModeInjection(
        id = Uuid.parse("a1e2e3e4-d5e6-f7a8-b9c0-d1e2f3a4b5c8"),
        content = """
            Politics study mode active. Reply in Chinese, use Markdown.
            - Knowledge points: 核心概念 → 详细解析 → 记忆口诀 → 易混辨析 → 真题链接. Call `save_knowledge_card`.
            - Essay frameworks: 题目类型 → 答题框架 → 关键词汇 → 范例. Call `save_note` with category "论述框架".
            - 抽背: call `quiz_user`, one question at a time, give feedback.
            - Link current events to exam points.
        """.trimIndent(),
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "政治学习模式"
    ),
    PromptInjection.ModeInjection(
        id = Uuid.parse("b1e2e3e4-d5e6-f7a8-b9c0-d1e2f3a4b5c9"),
        content = """
            Mechanics study mode active. Reply in Chinese, use LaTeX for all formulas.
            - Problem solving: 考点定位 → 分步推导(with theorem citations) → $$\boxed{answer}$$ → 易错点. Call `save_wrong_question`.
            - Concepts: 定义 → 工作原理 → 关键公式 → 应用场景 → 考试重点. Call `save_knowledge_card`.
            - 抽背: call `quiz_user`, one question at a time, give feedback.
            - Save techniques via `save_note` with category "解题思路", formulas via "公式推导".
        """.trimIndent(),
        position = InjectionPosition.AFTER_SYSTEM_PROMPT,
        name = "机械原理学习模式"
    ),
)
