package me.rerere.rikkahub.data.config

import me.rerere.ai.provider.BalanceOption
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpOAuthState
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.AssistantRegex
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

class AgentConfigExporterTest {

    private fun tempAgentRoot(): Pair<File, () -> Unit> {
        val dir = Files.createTempDirectory("agent-config-test").toFile()
        return dir to { dir.deleteRecursively() }
    }

    private fun sampleSettings(): Settings {
        val modelId = Uuid.random()
        val providerId = Uuid.random()
        val openai = ProviderSetting.OpenAI(
            id = providerId,
            name = "My OpenAI",
            apiKey = "sk-verysecret123",
            baseUrl = "https://my-proxy.example.com/v1",
            authType = OpenAIAuthType.API_KEY,
            balanceOption = BalanceOption(enabled = true, apiPath = "/credits", resultPath = "data.total_usage"),
            useResponseApi = true,
            includeHistoryReasoning = true,
            models = listOf(
                Model(
                    id = modelId,
                    modelId = "gpt-4o",
                    displayName = "GPT-4o",
                    type = ModelType.CHAT,
                    inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
                    outputModalities = listOf(Modality.TEXT),
                    abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
                    tools = setOf(BuiltInTools.Search),
                    customHeaders = listOf(CustomHeader("Authorization", "Bearer model-secret")),
                    providerOverwrite = ProviderSetting.Claude(
                        name = "nested-claude",
                        apiKey = "nested-secret",
                        baseUrl = "https://nested.example.com/v1",
                    ),
                )
            ),
        )
        val google = ProviderSetting.Google(
            name = "Vertex",
            apiKey = "",
            privateKey = "private-key-123",
            serviceAccountEmail = "svc@example.com",
            useServiceAccount = true,
            baseUrl = "https://example.com/v1",
        )
        val claude = ProviderSetting.Claude(
            name = "Claude",
            apiKey = "sk-ant-456",
            baseUrl = "https://api.anthropic.com/v1",
        )
        val assistantId = Uuid.random()
        val assistant = Assistant(
            id = assistantId,
            name = "测试助手",
            chatModelId = modelId,
            systemPrompt = "You are a test assistant.",
            temperature = 0.7f,
            enableMemory = true,
            enableWebSearch = true,
            workspaceId = Uuid.random(),
            mcpServers = setOf(Uuid.random()),
            enabledSkills = setOf("locale-tui-localization", "publish-release"),
            knowledgeBaseIds = setOf(Uuid.random()),
            avatar = Avatar.Emoji("🧪"),
            useAssistantAvatar = true,
            reasoningLevel = ReasoningLevel.MEDIUM,
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    name = "cleanup",
                    enabled = true,
                    findRegex = "\\s+",
                    replaceString = " ",
                    affectingScope = setOf(AssistantAffectScope.USER),
                )
            ),
            presetMessages = listOf(UIMessage.user("remind me to test")),
            localTools = listOf(LocalToolOption.Calendar, LocalToolOption.TimeInfo),
            customHeaders = listOf(CustomHeader("X-Test", "assistant-header-value")),
            contextMessageLimit = 50,
            enableKnowledgeQueryRewrite = true,
            enabledStudyTools = listOf("save_note"),
            studySubject = "english",
        )
        val mcp = McpServerConfig.StreamableHTTPServer(
            id = Uuid.random(),
            commonOptions = McpCommonOptions(
                enable = true,
                name = "test-mcp",
                headers = listOf("Authorization" to "Bearer secret-token-999"),
                tools = listOf(McpTool(name = "search")),
                oauth = McpOAuthState(enabled = true, accessToken = "oauth-token-abc"),
            ),
            url = "https://mcp.example.com/mcp",
        )
        val mcpSse = McpServerConfig.SseTransportServer(
            id = Uuid.random(),
            commonOptions = McpCommonOptions(enable = false, name = "plain-sse"),
            url = "https://sse.example.com",
        )
        return Settings(
            providers = listOf(openai, google, claude),
            assistants = listOf(assistant),
            mcpServers = listOf(mcp, mcpSse),
        )
    }

    @Test
    fun exportWritesSchemaFilesAndDecodes() {
        val (root, cleanup) = tempAgentRoot()
        try {
            val result = AgentConfigExporter.export(sampleSettings(), root)

            assertTrue(result.ok)

            val providers = root.resolve(AgentConfigPaths.PROVIDERS_FILE)
            val mcp = root.resolve(AgentConfigPaths.MCP_FILE)
            val manifest = root.resolve(AgentConfigPaths.MANIFEST_FILE)
            assertTrue(providers.isFile)
            assertTrue(mcp.isFile)
            assertTrue(manifest.isFile)

            val providerFile = JsonInstant.decodeFromString<ProviderConfigFile>(providers.readText())
            val mcpFile = JsonInstant.decodeFromString<McpConfigFile>(mcp.readText())
            val manifestFile = JsonInstant.decodeFromString<AgentManifest>(manifest.readText())

            assertEquals(AGENT_CONFIG_SCHEMA_VERSION, providerFile.schemaVersion)
            assertEquals(AGENT_CONFIG_SCHEMA_VERSION, mcpFile.schemaVersion)
            assertEquals(AGENT_CONFIG_SCHEMA_VERSION, manifestFile.schemaVersion)
            assertEquals(3, providerFile.providers.size)
            assertEquals(2, mcpFile.servers.size)
            assertEquals("streamable_http", mcpFile.servers[0].type)
            assertEquals(1, mcpFile.servers[0].toolCount)
            assertTrue(mcpFile.servers[0].oauthEnabled)
            assertEquals(manifestFile.files.values.toSet(), setOf("ok"))
        } finally {
            cleanup()
        }
    }

    @Test
    fun exportNeverContainsSecrets() {
        val (root, cleanup) = tempAgentRoot()
        try {
            AgentConfigExporter.export(sampleSettings(), root)

            val providersText = root.resolve(AgentConfigPaths.PROVIDERS_FILE).readText()
            val mcpText = root.resolve(AgentConfigPaths.MCP_FILE).readText()
            val assistantDir = root.resolve(AgentConfigPaths.ASSISTANTS_DIR)
            val assistantText = assistantDir.listFiles()!!.first().readText()

            // 密钥明文绝不出现（含模型级 headers 与嵌套 provider 覆盖）
            assertFalse(providersText.contains("sk-verysecret123"))
            assertFalse(providersText.contains("private-key-123"))
            assertFalse(providersText.contains("svc@example.com"))
            assertFalse(providersText.contains("sk-ant-456"))
            assertFalse(providersText.contains("Bearer model-secret"))
            assertFalse(providersText.contains("nested-secret"))
            assertFalse(mcpText.contains("Bearer secret-token-999"))
            assertFalse(mcpText.contains("oauth-token-abc"))

            // 密钥位置改为引用占位
            assertTrue(providersText.contains("keystore:provider:"))
            assertTrue(providersText.contains("keystore:model:"))
            assertTrue(mcpText.contains("keystore:mcp:"))
            assertTrue(providersText.contains("apiKeyRef"))

            // assistant 文件不含敏感配置（自定义头只给引用）
            assertFalse(assistantText.contains("assistant-header-value"))
            assertTrue(assistantText.contains("keystore:assistant:"))
        } finally {
            cleanup()
        }
    }

    @Test
    fun exportCoversBalanceModelAdvancedAndAssistantConfig() {
        val (root, cleanup) = tempAgentRoot()
        try {
            AgentConfigExporter.export(sampleSettings(), root)

            // 解析后断言（读写识别正确性：导出 JSON 必须能 decode 回 DTO）
            val providerFile = JsonInstant.decodeFromString<ProviderConfigFile>(
                root.resolve(AgentConfigPaths.PROVIDERS_FILE).readText()
            )
            val assistantDto = JsonInstant.decodeFromString<AssistantConfigFile>(
                root.resolve(AgentConfigPaths.ASSISTANTS_DIR).listFiles()!!.single().readText()
            ).assistant
            val providersText = root.resolve(AgentConfigPaths.PROVIDERS_FILE).readText()
            val assistantText = root.resolve(AgentConfigPaths.ASSISTANTS_DIR)
                .listFiles()!!.single().readText()

            // Provider：余额与高级设置
            val openai = providerFile.providers.first { it.type == "openai" }
            assertEquals(true, openai.balance?.enabled)
            assertEquals("/credits", openai.balance?.apiPath)
            assertEquals("data.total_usage", openai.balance?.resultPath)
            assertEquals(true, openai.useResponseApi)
            assertEquals(true, openai.includeHistoryReasoning)
            assertTrue(providersText.contains("\"balance\""))

            // Model：基本设置 + 内置工具 + 嵌套 provider 覆盖（脱敏）
            val model = openai.models.single()
            assertEquals(listOf("image", "text"), model.inputModalities)
            assertEquals(listOf("reasoning", "tool"), model.abilities)
            assertEquals(listOf("search"), model.builtInTools)
            assertNotNull(model.customHeadersRef)
            assertTrue(model.customHeadersRef!!.contains("keystore:model:"))
            val nested = model.providerOverwrite
            assertNotNull(nested)
            assertEquals("claude", nested!!.type)
            assertNotNull(nested.apiKeyRef)
            assertFalse(providersText.contains("nested-secret"))

            // Assistant：外观/上下文/正则/预设消息/本地工具/学习工具
            assertEquals("emoji:🧪", assistantDto.avatar)
            assertEquals("medium", assistantDto.reasoningLevel)
            assertEquals(50, assistantDto.contextMessageLimit)
            assertEquals(listOf("calendar", "time_info"), assistantDto.localTools)
            assertEquals("\\s+", assistantDto.regexes.single().findRegex)
            assertEquals("user", assistantDto.presetMessages.single().role)
            assertTrue(assistantDto.presetMessages.single().text.contains("remind me to test"))
            assertEquals(listOf("save_note"), assistantDto.enabledStudyTools)
            assertEquals("english", assistantDto.studySubject)
            assertNotNull(assistantDto.customHeadersRef)
            assertFalse(assistantText.contains("assistant-header-value"))
        } finally {
            cleanup()
        }
    }

    @Test
    fun assistantModelRefResolvesToProviderColonModel() {
        val (root, cleanup) = tempAgentRoot()
        try {
            val settings = sampleSettings()
            AgentConfigExporter.export(settings, root)

            val assistantFile = root.resolve(AgentConfigPaths.ASSISTANTS_DIR)
                .listFiles()!!.single()
            val dto = JsonInstant.decodeFromString<AssistantConfigFile>(assistantFile.readText()).assistant

            val provider = settings.providers.first()
            val model = provider.models.first()
            assertEquals("${provider.id}:${model.id}", dto.chatModelRef)
            assertEquals(listOf("locale-tui-localization", "publish-release"), dto.enabledSkills)
            assertEquals("测试助手", dto.name)
        } finally {
            cleanup()
        }
    }

    @Test
    fun repositoryViewsExportedConfig() {
        val (root, cleanup) = tempAgentRoot()
        try {
            AgentConfigExporter.export(sampleSettings(), root)
            val repository = AgentConfigRepository(root)

            val view = repository.view()
            assertEquals(3, view.providerCount)
            assertEquals(2, view.mcpServerCount)
            assertEquals(1, view.assistantCount)
            assertTrue(view.files.any { it.path == AgentConfigPaths.PROVIDERS_FILE })
            assertTrue(view.files.any { it.path == AgentConfigPaths.MANIFEST_FILE })
            assertEquals(AGENT_CONFIG_SCHEMA_VERSION, view.schemaVersion)
        } finally {
            cleanup()
        }
    }

    @Test
    fun repositoryPathWhitelistBlocksTraversal() {
        val (root, cleanup) = tempAgentRoot()
        try {
            AgentConfigExporter.export(sampleSettings(), root)
            val repository = AgentConfigRepository(root)

            // 白名单内可读
            assertNotNull(repository.readConfigFile(AgentConfigPaths.PROVIDERS_FILE))
            assertNotNull(repository.readConfigFile(AgentConfigPaths.MANIFEST_FILE))

            // 目录穿越/越权一律拒绝
            assertNull(repository.readConfigFile("../secret.txt"))
            assertNull(repository.readConfigFile("config/../../secret.txt"))
            assertNull(repository.readConfigFile(".."))
            assertNull(repository.readConfigFile("unknown/file.json"))

            // policies/ 前缀在白名单内：文件存在时可读
            val policy = root.resolve("policies").apply { mkdirs() }.resolve("global.md")
            policy.writeText("# policy")
            assertEquals("# policy", repository.readConfigFile("policies/global.md"))
        } finally {
            cleanup()
        }
    }
}
