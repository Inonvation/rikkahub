package me.rerere.rikkahub.data.config

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.tools.applyFileToSettings
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.writeAgentConfigFile
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

class AgentConfigWriteImporterTest {

    private fun tempAgentRoot(): Pair<File, () -> Unit> {
        val dir = Files.createTempDirectory("agent-write-test").toFile()
        return dir to { dir.deleteRecursively() }
    }

    private fun sampleSettings(): Settings {
        val providerId = Uuid.random()
        val assistantId = Uuid.random()
        val mcpId = Uuid.random()
        return Settings(
            providers = listOf(
                ProviderSetting.OpenAI(
                    id = providerId,
                    name = "My OpenAI",
                    apiKey = "sk-keep-me",
                    baseUrl = "https://old.example.com/v1",
                    models = listOf(
                        Model(modelId = "gpt-4o", displayName = "GPT-4o"),
                    ),
                )
            ),
            assistants = listOf(
                Assistant(id = assistantId, name = "旧助手", systemPrompt = "old prompt")
            ),
            mcpServers = listOf(
                McpServerConfig.SseTransportServer(
                    id = mcpId,
                    commonOptions = me.rerere.rikkahub.data.ai.mcp.McpCommonOptions(enable = true),
                    url = "https://sse.example.com",
                )
            ),
        )
    }

    @Test
    fun writeConfigFileIsAtomicWithBackupAndRevision() {
        val (root, cleanup) = tempAgentRoot()
        try {
            AgentConfigExporter.export(sampleSettings(), root)
            val repository = AgentConfigRepository(root)

            val newContent = File(root, AgentConfigPaths.PROVIDERS_FILE)
                .readText()
                .replace("My OpenAI", "Renamed")

            assertNull(repository.writeConfigFile(AgentConfigPaths.PROVIDERS_FILE, newContent))

            // 内容已更新
            assertTrue(File(root, AgentConfigPaths.PROVIDERS_FILE).readText().contains("Renamed"))
            // 快照已备份
            val backups = File(root, AgentConfigPaths.BACKUPS_DIR)
            assertTrue(backups.isDirectory && backups.listFiles()!!.isNotEmpty())
            // 修订已记录
            val revision = repository.latestRevision()
            assertNotNull(revision)
            assertEquals(AgentConfigPaths.PROVIDERS_FILE, revision!!.path)

            // 非法路径拒绝
            assertNotNull(repository.writeConfigFile("../evil.txt", "x"))
            assertNotNull(repository.writeConfigFile("unknown/file.json", "x"))
        } finally {
            cleanup()
        }
    }

    @Test
    fun applyProvidersMergesNonSensitiveAndKeepsSecrets() {
        val (root, cleanup) = tempAgentRoot()
        try {
            val settings = sampleSettings()
            AgentConfigExporter.export(settings, root)

            // 修改文件：改名字/地址/响应 API，密钥引用保持（apiKeyRef 为引用）
            val repository = AgentConfigRepository(root)
            val modified = File(root, AgentConfigPaths.PROVIDERS_FILE)
                .readText()
                .replace("\"name\": \"My OpenAI\"", "\"name\": \"From File\"")
                .replace("https://old.example.com/v1", "https://new.example.com/v1")
            assertNull(repository.writeConfigFile(AgentConfigPaths.PROVIDERS_FILE, modified))

            val applied = AgentConfigImporter.applyProviders(settings, root)
            val provider = applied.providers.single() as ProviderSetting.OpenAI
            assertEquals("From File", provider.name)
            assertEquals("https://new.example.com/v1", provider.baseUrl)
            // 密钥保留本地值（文件只有引用）
            assertEquals("sk-keep-me", provider.apiKey)
        } finally {
            cleanup()
        }
    }

    @Test
    fun applyAssistantsAndMcpMergeById() {
        val (root, cleanup) = tempAgentRoot()
        try {
            val settings = sampleSettings()
            AgentConfigExporter.export(settings, root)
            val repository = AgentConfigRepository(root)

            // 改助手名
            val assistantFile = File(root, AgentConfigPaths.ASSISTANTS_DIR)
                .listFiles()!!.single()
            val modifiedAssistant = assistantFile.readText().replace("旧助手", "新助手")
            assertNull(repository.writeConfigFile(
                "${AgentConfigPaths.ASSISTANTS_DIR}/${assistantFile.name}",
                modifiedAssistant,
            ))

            // 关 MCP
            val modifiedMcp = File(root, AgentConfigPaths.MCP_FILE)
                .readText()
                .replace("\"enable\": true", "\"enable\": false")
            assertNull(repository.writeConfigFile(AgentConfigPaths.MCP_FILE, modifiedMcp))

            val applied = AgentConfigImporter.applyAssistants(
                AgentConfigImporter.applyMcpServers(settings, root),
                root,
            )
            assertEquals("新助手", applied.assistants.single().name)
            assertEquals("old prompt", applied.assistants.single().systemPrompt)
            assertEquals(false, applied.mcpServers.single().commonOptions.enable)
        } finally {
            cleanup()
        }
    }

    @Test
    fun corruptedFileIsSkippedByImporter() {
        val (root, cleanup) = tempAgentRoot()
        try {
            val settings = sampleSettings()
            AgentConfigExporter.export(settings, root)
            File(root, AgentConfigPaths.PROVIDERS_FILE).writeText("{ not valid json")

            val applied = AgentConfigImporter.applyProviders(settings, root)
            // 解析失败 → 原样返回，不破坏现有配置
            assertEquals("My OpenAI", applied.providers.single().name)
        } finally {
            cleanup()
        }
    }

    @Test
    fun applyAssistantsMergesFullFieldSet() {
        val (root, cleanup) = tempAgentRoot()
        try {
            val settings = sampleSettings()
            val assistant = settings.assistants.single()
            val provider = settings.providers.single()
            val model = provider.models.single()
            AgentConfigExporter.export(settings, root)

            // 覆盖助手文件：写入全部可反向映射的字段
            val assistantFile = File(root, AgentConfigPaths.ASSISTANTS_DIR)
                .listFiles()!!.single()
            val newContent = """
                {
                  "schemaVersion": 1,
                  "assistant": {
                    "id": "${assistant.id}",
                    "name": "新助手",
                    "chatModelRef": "${provider.id}:${model.id}",
                    "avatar": "emoji:🤖",
                    "useAssistantAvatar": true,
                    "tags": [],
                    "reasoningLevel": "high",
                    "background": "https://example.com/bg.png",
                    "backgroundOpacity": 0.5,
                    "useGradientBackground": true,
                    "regexes": [
                      {"id": "11111111-1111-1111-1111-111111111111", "name": "r1", "enabled": true, "findRegex": "a", "replaceString": "b", "affectingScope": ["user"], "visualOnly": false}
                    ],
                    "presetMessages": [
                      {"role": "system", "text": "hello"}
                    ],
                    "localTools": ["time_info", "clipboard"],
                    "quickMessageIds": [],
                    "defaultWorkspaceCwd": "/data",
                    "streamOutput": false,
                    "contextMessageLimit": 42,
                    "contextTokenLimit": 9999
                  }
                }
            """.trimIndent()
            assertNull(repositoryFor(root).writeConfigFile(
                "${AgentConfigPaths.ASSISTANTS_DIR}/${assistantFile.name}",
                newContent,
            ))

            val applied = AgentConfigImporter.applyAssistants(
                settings,
                root,
                onlyAssistantId = assistant.id.toString(),
            ).assistants.single()

            assertEquals("新助手", applied.name)
            assertEquals(model.id, applied.chatModelId)
            assertEquals(Avatar.Emoji("🤖"), applied.avatar)
            assertTrue(applied.useAssistantAvatar)
            assertEquals(ReasoningLevel.HIGH, applied.reasoningLevel)
            assertEquals("https://example.com/bg.png", applied.background)
            assertEquals(0.5f, applied.backgroundOpacity)
            assertTrue(applied.useGradientBackground)
            assertEquals(1, applied.regexes.size)
            assertEquals("a", applied.regexes.single().findRegex)
            assertEquals(setOf(me.rerere.rikkahub.data.model.AssistantAffectScope.USER), applied.regexes.single().affectingScope)
            assertEquals(1, applied.presetMessages.size)
            assertEquals(MessageRole.SYSTEM, applied.presetMessages.single().role)
            assertEquals("hello", applied.presetMessages.single().toText())
            assertTrue(applied.localTools.contains(LocalToolOption.TimeInfo))
            assertTrue(applied.localTools.contains(LocalToolOption.Clipboard))
            assertEquals("/data", applied.defaultWorkspaceCwd)
            assertFalse(applied.streamOutput)
            assertEquals(42, applied.contextMessageLimit)
            assertEquals(9999, applied.contextTokenLimit)
            // 密钥字段不在文件中 → 保持本地
            assertEquals("old prompt", applied.systemPrompt)
        } finally {
            cleanup()
        }
    }

    @Test
    fun applyAssistantsKeepsLocalWhenFieldMissing() {
        val (root, cleanup) = tempAgentRoot()
        try {
            val assistantId = Uuid.random()
            // 本地非默认值，验证"文件缺字段 → 保留本地"不被 DTO 默认值重置
            val settings = Settings(
                providers = listOf(
                    ProviderSetting.OpenAI(name = "OpenAI", apiKey = "sk-local"),
                ),
                assistants = listOf(
                    Assistant(
                        id = assistantId,
                        name = "本地助手",
                        streamOutput = false,
                        contextMessageLimit = 100,
                        enableMemory = true,
                    )
                ),
            )
            AgentConfigExporter.export(settings, root)

            // 精简文件：只有 name（外部工具生成的缺字段文件场景）
            val assistantFile = File(root, AgentConfigPaths.ASSISTANTS_DIR)
                .listFiles()!!.single()
            val minimal = """
                {"schemaVersion":1,"assistant":{"id":"$assistantId","name":"仅改名"}}
            """.trimIndent()
            assertNull(repositoryFor(root).writeConfigFile(
                "${AgentConfigPaths.ASSISTANTS_DIR}/${assistantFile.name}",
                minimal,
            ))

            val applied = AgentConfigImporter.applyAssistants(settings, root).assistants.single()
            assertEquals("仅改名", applied.name)
            // 缺省字段全部保留本地值
            assertFalse(applied.streamOutput)
            assertEquals(100, applied.contextMessageLimit)
            assertTrue(applied.enableMemory)
        } finally {
            cleanup()
        }
    }

    @Test
    fun applyAssistantsOnlyAffectsGivenAssistant() {
        val (root, cleanup) = tempAgentRoot()
        try {
            val firstId = Uuid.random()
            val secondId = Uuid.random()
            val settings = Settings(
                assistants = listOf(
                    Assistant(id = firstId, name = "助手A"),
                    Assistant(id = secondId, name = "助手B"),
                ),
            )
            AgentConfigExporter.export(settings, root)

            // 只改 A 的文件
            val aFile = File(File(root, AgentConfigPaths.ASSISTANTS_DIR), "$firstId.json")
            val modified = aFile.readText().replace("助手A", "助手A-改")
            assertNull(repositoryFor(root).writeConfigFile(
                "${AgentConfigPaths.ASSISTANTS_DIR}/$firstId.json",
                modified,
            ))

            // 单文件应用：只影响 firstId，B 不受整目录合并影响
            val applied = AgentConfigImporter.applyAssistants(
                settings,
                root,
                onlyAssistantId = firstId.toString(),
            ).assistants
            assertEquals("助手A-改", applied.first { it.id == firstId }.name)
            assertEquals("助手B", applied.first { it.id == secondId }.name)
        } finally {
            cleanup()
        }
    }

    @Test
    fun applyMcpMergesName() {
        val (root, cleanup) = tempAgentRoot()
        try {
            val settings = sampleSettings()
            AgentConfigExporter.export(settings, root)
            val mcpFile = File(root, AgentConfigPaths.MCP_FILE)
            val modified = mcpFile.readText().replace("\"name\": \"\"", "\"name\": \"SSE Server\"")
            assertNull(repositoryFor(root).writeConfigFile(AgentConfigPaths.MCP_FILE, modified))

            val applied = AgentConfigImporter.applyMcpServers(settings, root)
            assertEquals("SSE Server", applied.mcpServers.single().commonOptions.name)
        } finally {
            cleanup()
        }
    }

    // ---- config_write 核心（writeAgentConfigFile / applyFileToSettings） ----

    @Test
    fun writeAgentConfigFileRejectsInvalidJson() {
        val (root, cleanup) = tempAgentRoot()
        try {
            val settings = sampleSettings()
            AgentConfigExporter.export(settings, root)
            val repo = repositoryFor(root)
            val before = File(root, AgentConfigPaths.PROVIDERS_FILE).readText()

            val error = writeAgentConfigFile(repo, AgentConfigPaths.PROVIDERS_FILE, "{ not valid json")
            assertNotNull(error)
            assertTrue(error!!.startsWith("invalid JSON"))
            // 文件未被破坏
            assertEquals(before, File(root, AgentConfigPaths.PROVIDERS_FILE).readText())
        } finally {
            cleanup()
        }
    }

    @Test
    fun writeAgentConfigFileWritesValidJsonWithBackup() {
        val (root, cleanup) = tempAgentRoot()
        try {
            val settings = sampleSettings()
            AgentConfigExporter.export(settings, root)
            val repo = repositoryFor(root)

            val valid = File(root, AgentConfigPaths.PROVIDERS_FILE)
                .readText()
                .replace("My OpenAI", "AI Rewritten")
            assertNull(writeAgentConfigFile(repo, AgentConfigPaths.PROVIDERS_FILE, valid))
            assertTrue(File(root, AgentConfigPaths.PROVIDERS_FILE).readText().contains("AI Rewritten"))
            // 快照 + 修订已生成
            assertTrue(repo.listBackups(AgentConfigPaths.PROVIDERS_FILE).isNotEmpty())
            assertTrue(repo.revisions().any { it.path == AgentConfigPaths.PROVIDERS_FILE })
        } finally {
            cleanup()
        }
    }

    @Test
    fun applyFileToSettingsRoutesByPath() {
        val (root, cleanup) = tempAgentRoot()
        try {
            val settings = sampleSettings()
            AgentConfigExporter.export(settings, root)
            val repo = repositoryFor(root)

            // providers 路径 → 合并
            val providersContent = File(root, AgentConfigPaths.PROVIDERS_FILE)
                .readText()
                .replace("My OpenAI", "Routed")
            assertNull(writeAgentConfigFile(repo, AgentConfigPaths.PROVIDERS_FILE, providersContent))
            val merged = applyFileToSettings(settings, repo, AgentConfigPaths.PROVIDERS_FILE)
            assertEquals("Routed", merged.providers.single().name)
            // 密钥保留本地
            assertEquals("sk-keep-me", (merged.providers.single() as ProviderSetting.OpenAI).apiKey)

            // 不可应用路径（policies/）→ 原样返回同一对象
            File(root, "policies").mkdirs()
            File(root, "policies/global.md").writeText("# test")
            assertSame(settings, applyFileToSettings(settings, repo, "policies/global.md"))
        } finally {
            cleanup()
        }
    }

    // ---- 备份回退 / 修订 / 脏标记 ----

    @Test
    fun backupsRoundTripAndRejectTraversal() {
        val (root, cleanup) = tempAgentRoot()
        try {
            val settings = sampleSettings()
            AgentConfigExporter.export(settings, root)
            val repo = repositoryFor(root)
            val original = File(root, AgentConfigPaths.PROVIDERS_FILE).readText()

            // 修改两次，产生两个快照
            val v1 = original.replace("My OpenAI", "Step One")
            val v2 = original.replace("My OpenAI", "Step Two")
            assertNull(repo.writeConfigFile(AgentConfigPaths.PROVIDERS_FILE, v1))
            Thread.sleep(10)
            assertNull(repo.writeConfigFile(AgentConfigPaths.PROVIDERS_FILE, v2))

            val backups = repo.listBackups(AgentConfigPaths.PROVIDERS_FILE)
            assertEquals(2, backups.size)
            // 新→旧：第一条是最近的（v1 内容的快照）
            val latest = backups.first()
            assertEquals(v1, repo.readBackup(latest.name))
            // 最早的快照是原始内容
            assertEquals(original, repo.readBackup(backups.last().name))
            // 修订记录：2 条，新→旧
            assertEquals(2, repo.revisions().size)
            // 防穿越
            assertNull(repo.readBackup("../evil.bak"))
            assertNull(repo.readBackup("providers_json_123.bak"))
        } finally {
            cleanup()
        }
    }

    @Test
    fun dirtyFlagTracksManualEditAndReExport() {
        val (root, cleanup) = tempAgentRoot()
        try {
            val settings = sampleSettings()
            AgentConfigExporter.export(settings, root)
            val repo = repositoryFor(root)
            val file = File(root, AgentConfigPaths.PROVIDERS_FILE)

            // 导出后立即：不脏
            assertFalse(repo.isFileDirty(AgentConfigPaths.PROVIDERS_FILE))
            assertFalse(repo.view().files.first { it.path == AgentConfigPaths.PROVIDERS_FILE }.dirty)

            // 手动修改（不经过 writeConfigFile 的语义：直接写文件模拟外部编辑）
            Thread.sleep(10)
            file.writeText(file.readText().replace("My OpenAI", "External Edit"))
            assertTrue(repo.isFileDirty(AgentConfigPaths.PROVIDERS_FILE))
            assertTrue(repo.view().files.first { it.path == AgentConfigPaths.PROVIDERS_FILE }.dirty)

            // 重新导出后：脏标记清除
            AgentConfigExporter.export(settings, root)
            assertFalse(repo.isFileDirty(AgentConfigPaths.PROVIDERS_FILE))
        } finally {
            cleanup()
        }
    }

    private fun repositoryFor(root: File) = AgentConfigRepository(root)
}
