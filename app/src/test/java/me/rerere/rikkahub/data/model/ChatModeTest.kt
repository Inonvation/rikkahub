package me.rerere.rikkahub.data.model

import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatModeTest {

    @Test
    fun minimalModeIsDisplayedFirst() {
        assertEquals(
            listOf(ChatMode.MINIMAL, ChatMode.STANDARD, ChatMode.PTC, ChatMode.CREATIVE),
            ChatMode.entries.toList(),
        )
    }

    private fun resolveMode(
        assistant: Assistant,
        settings: Settings,
    ): ChatMode? = resolveModeRef(assistant, settings)
        ?.let { ModeRefs.parseBuiltin(it) }

    private fun settings(
        defaultMode: String? = null,
        customModes: List<CustomModeConfig> = emptyList(),
        builtinModeOverrides: Map<ChatMode, ChatModePolicy> = emptyMap(),
    ) = Settings(
        init = true,
        defaultMode = defaultMode,
        customModes = customModes,
        builtinModeOverrides = builtinModeOverrides,
    )

    private fun assistant(
        workspaceId: String? = null,
        defaultMode: String? = null,
    ) = Assistant(
        workspaceId = workspaceId?.let { Uuid.parse(it) },
        defaultMode = defaultMode,
    )

    private fun settingsWithSearch(
        builtInSearch: Boolean = false,
        enableWebSearch: Boolean = false,
    ): Settings {
        val modelId = Uuid.random()
        val model = Model(
            id = modelId,
            modelId = "test-search-model",
            displayName = "Test Search Model",
            tools = if (builtInSearch) setOf(BuiltInTools.Search) else emptySet(),
        )
        val assistant = Assistant(enableWebSearch = enableWebSearch)
        return settings().copy(
            chatModelId = modelId,
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
            assistants = listOf(assistant),
            assistantId = assistant.id,
        )
    }

    private fun conversation(mode: String?) =
        Conversation.ofId(id = Uuid.random(), assistantId = Uuid.random()).copy(mode = mode)

    @Test
    fun standardPolicyMatchesMatrix() {
        val p = ChatMode.STANDARD.policy()
        assertTrue(p.allowMcpUse)
        assertFalse(p.allowSkillUse)
        assertTrue(p.allowMemory)
        assertTrue(p.allowTodo)
        assertTrue(p.allowSubAgent)
        assertTrue(p.allowStudy)
        assertTrue(p.allowHistory)
        assertTrue(p.allowKnowledge)
        assertTrue(p.includePromptInjection)
        assertTrue(p.includeReminders)
        assertTrue(p.includeToolSystemPrompt)
        assertTrue(p.allowLocalTools)
        assertTrue(p.allowSearch)
        assertTrue(p.allowDocument)
        assertFalse(p.allowWorkspace)
        assertFalse(p.allowTrustedFolder)
        assertFalse(p.allowSkillAdmin)
        assertFalse(p.allowMcpAdmin)
        assertFalse(p.allowCreativeTools)
    }

    @Test
    fun ptcAddsWorkspaceAndTrustedFolder() {
        val p = ChatMode.PTC.policy()
        assertTrue(p.allowWorkspace)
        assertTrue(p.allowTrustedFolder)
        assertTrue(p.allowSkillUse)
        assertFalse(p.allowSkillAdmin)
        assertFalse(p.allowMcpAdmin)
    }

    @Test
    fun minimalKeepsOnlyLocalSearchAndDocument() {
        val p = ChatMode.MINIMAL.policy()
        assertTrue(p.allowLocalTools)
        assertTrue(p.allowSearch)
        assertTrue(p.allowDocument)
        assertFalse(p.allowMcpUse)
        assertFalse(p.allowSkillUse)
        assertFalse(p.allowWorkspace)
        assertFalse(p.allowTrustedFolder)
        assertFalse(p.allowMemory)
        assertFalse(p.allowTodo)
        assertFalse(p.allowSubAgent)
        assertFalse(p.allowStudy)
        assertFalse(p.allowHistory)
        assertFalse(p.allowKnowledge)
        assertFalse(p.includePromptInjection)
        assertFalse(p.includeReminders)
        assertFalse(p.includeToolSystemPrompt)
        assertFalse(p.allowCreativeTools)
    }

    @Test
    fun minimalKeepsBehaviorPromptWithoutToolPrompts() {
        val p = ChatMode.MINIMAL.policy()
        assertFalse(p.includeToolSystemPrompt)
        assertTrue(p.includeAgentBehaviorPrompt)
    }

    @Test
    fun builtInSearchMakesSearchCapabilityEffectiveWithoutWebSearch() {
        assertFalse(
            Capability.SEARCH in ChatMode.STANDARD.policy()
                .restrictedCapabilities(settingsWithSearch(builtInSearch = true))
        )
    }

    @Test
    fun localWebSearchMakesSearchCapabilityEffective() {
        assertFalse(
            Capability.SEARCH in ChatMode.STANDARD.policy()
                .restrictedCapabilities(settingsWithSearch(enableWebSearch = true))
        )
    }

    @Test
    fun searchCapabilityIsRestrictedWithoutAnySearchEnabled() {
        assertTrue(
            Capability.SEARCH in ChatMode.STANDARD.policy()
                .restrictedCapabilities(settingsWithSearch())
        )
    }

    @Test
    fun behaviorPromptIsIndependentFromToolSystemPrompt() {
        val toolPromptOnly = ChatModePolicy(capabilities = setOf(Capability.TOOL_SYSTEM_PROMPT))
        assertTrue(toolPromptOnly.includeToolSystemPrompt)
        assertFalse(toolPromptOnly.includeAgentBehaviorPrompt)

        val behaviorPromptOnly = ChatModePolicy(capabilities = setOf(Capability.AGENT_BEHAVIOR_PROMPT))
        assertFalse(behaviorPromptOnly.includeToolSystemPrompt)
        assertTrue(behaviorPromptOnly.includeAgentBehaviorPrompt)
    }

    @Test
    fun minimalKeepsMinimalProfileWhenCapabilitiesChange() {
        val override = ChatModePolicy(
            capabilities = setOf(
                Capability.LOCAL_TOOLS,
                Capability.SEARCH,
                Capability.DOCUMENT,
                Capability.MCP_USE,
            ),
            behaviorProfileOverride = AgentBehaviorProfile.MINIMAL,
        )
        val p = ChatMode.MINIMAL.effectivePolicy(
            settings(builtinModeOverrides = mapOf(ChatMode.MINIMAL to override))
        )

        assertEquals(AgentBehaviorProfile.MINIMAL, p.behaviorProfile)
        assertTrue(p.includeAgentBehaviorPrompt)
    }

    @Test
    fun managementModeOpensAdminAndCreativeTools() {
        val p = ChatMode.CREATIVE.policy()
        assertTrue(p.allowWorkspace)
        assertTrue(p.allowTrustedFolder)
        assertTrue(p.allowSkillUse)
        assertTrue(p.allowSkillAdmin)
        assertTrue(p.allowMcpAdmin)
        assertTrue(p.allowCreativeTools)
    }

    @Test
    fun behaviorProfileReflectsModeIntention() {
        assertEquals(AgentBehaviorProfile.STANDARD, ChatMode.STANDARD.policy().behaviorProfile)
        assertEquals(AgentBehaviorProfile.WORKSPACE, ChatMode.PTC.policy().behaviorProfile)
        assertEquals(AgentBehaviorProfile.MINIMAL, ChatMode.MINIMAL.policy().behaviorProfile)
        assertEquals(AgentBehaviorProfile.MANAGEMENT, ChatMode.CREATIVE.policy().behaviorProfile)
    }

    @Test
    fun capabilityListMatchesModes() {
        assertEquals(
            ChatModePolicy.DEFAULT_CAPABILITIES,
            ChatMode.STANDARD.policy().capabilities,
        )
        assertFalse(Capability.SKILL_USE in ChatMode.STANDARD.policy().capabilities)
        assertTrue(Capability.WORKSPACE in ChatMode.PTC.policy().capabilities)
        assertTrue(Capability.TRUSTED_FOLDER in ChatMode.PTC.policy().capabilities)
        assertEquals(
            setOf(Capability.LOCAL_TOOLS, Capability.SEARCH, Capability.DOCUMENT),
            ChatMode.MINIMAL.policy().capabilities,
        )
        val creative = ChatMode.CREATIVE.policy().capabilities
        assertTrue(Capability.SKILL_ADMIN in creative)
        assertTrue(Capability.MCP_ADMIN in creative)
        assertTrue(Capability.CREATIVE_TOOLS in creative)
        assertTrue(Capability.WORKSPACE in creative)
    }

    @Test
    fun managementModeIncludesNewAdminCapabilities() {
        val creative = ChatMode.CREATIVE.policy().capabilities
        assertTrue(Capability.PROVIDER_ADMIN in creative)
        assertTrue(Capability.ASSISTANT_ADMIN in creative)
        assertTrue(Capability.SETTINGS_ADMIN in creative)
        assertTrue(Capability.DATA_ADMIN in creative)
    }

    @Test
    fun anyAdminCapabilityDerivesManagementBehavior() {
        assertEquals(
            AgentBehaviorProfile.MANAGEMENT,
            ChatModePolicy(capabilities = setOf(Capability.PROVIDER_ADMIN)).behaviorProfile,
        )
    }

    @Test
    fun unrestrictedPolicyExcludesManagementOnlyCapabilities() {
        assertFalse(Capability.PROVIDER_ADMIN in ChatModePolicy.UNRESTRICTED_CAPABILITIES)
        assertFalse(Capability.ASSISTANT_ADMIN in ChatModePolicy.UNRESTRICTED_CAPABILITIES)
        assertFalse(Capability.SETTINGS_ADMIN in ChatModePolicy.UNRESTRICTED_CAPABILITIES)
        assertFalse(Capability.DATA_ADMIN in ChatModePolicy.UNRESTRICTED_CAPABILITIES)
    }

    @Test
    fun unrestrictedPolicyKeepsUsageCapabilitiesOnly() {
        val policy = ChatModePolicy.UNRESTRICTED
        assertEquals(ChatModePolicy.UNRESTRICTED_CAPABILITIES, policy.capabilities)
        // 使用面能力：标准基础 + 工作区/信任文件夹/skill 使用
        assertTrue(Capability.WORKSPACE in policy.capabilities)
        assertTrue(Capability.TRUSTED_FOLDER in policy.capabilities)
        assertTrue(Capability.SKILL_USE in policy.capabilities)
        assertTrue(Capability.MCP_USE in policy.capabilities)
        // 管理面能力（管理模式专属）不再出现在「跟随助手配置」中
        assertFalse(Capability.SKILL_ADMIN in policy.capabilities)
        assertFalse(Capability.MCP_ADMIN in policy.capabilities)
        assertFalse(Capability.CREATIVE_TOOLS in policy.capabilities)
        assertFalse(Capability.DEVICE_TOOLS in policy.capabilities)
        assertEquals(AgentBehaviorProfile.LEGACY, policy.behaviorProfile)
    }

    @Test
    fun emptyAssistantInFollowModeHasNoCapabilityDeclarations() {
        // 回归锚点：空配置助手 + 跟随助手配置，effective 能力集不得携带任何未启用能力的声明
        val effective = ChatModePolicy.UNRESTRICTED.withAvailability(
            assistant = Assistant(),
            settings = settings(),
            skillsInstalled = false,
            trustedFolderBound = false,
        )
        for (family in listOf(
            Capability.MCP_USE, Capability.MCP_ADMIN,
            Capability.SKILL_USE, Capability.SKILL_ADMIN,
            Capability.WORKSPACE, Capability.TRUSTED_FOLDER,
            Capability.SEARCH, Capability.MEMORY, Capability.HISTORY,
            Capability.KNOWLEDGE, Capability.STUDY, Capability.DEVICE_TOOLS,
            Capability.SUBAGENT,
        )) {
            assertFalse("$family should be restricted for an empty assistant", family in effective.capabilities)
        }
        // 基础能力保留
        assertTrue(Capability.LOCAL_TOOLS in effective.capabilities)
        assertTrue(Capability.DOCUMENT in effective.capabilities)
        assertTrue(Capability.TODO in effective.capabilities)
        assertTrue(Capability.TOOL_SYSTEM_PROMPT in effective.capabilities)
        assertTrue(Capability.AGENT_BEHAVIOR_PROMPT in effective.capabilities)
    }

    @Test
    fun boundTrustedFolderKeepsCapabilityForAssistant() {
        val boundAssistant = Assistant(trustedFolderProjectId = "proj-1")
        val effective = ChatMode.PTC.policy().withAvailability(
            assistant = boundAssistant,
            settings = settings(),
            skillsInstalled = false,
            trustedFolderBound = true,
        )
        assertTrue(Capability.TRUSTED_FOLDER in effective.capabilities)

        val unbound = ChatMode.PTC.policy().withAvailability(
            assistant = boundAssistant,
            settings = settings(),
            skillsInstalled = false,
            trustedFolderBound = false,
        )
        // 项目被删除（绑定悬空）时能力被扣除
        assertFalse(Capability.TRUSTED_FOLDER in unbound.capabilities)
    }

    @Test
    fun mcpUseDecoupledFromManagerSwitch() {
        // 「能用」与「能管理」分离：MCP_USE 只看助手是否绑定服务器，
        // 全局管理开关关闭只扣除 mcp_admin_*，不再静默禁用 mcp__* 使用
        val boundAssistant = Assistant(mcpServers = setOf(Uuid.random()))
        val effective = ChatMode.STANDARD.policy().withAvailability(
            assistant = boundAssistant,
            settings = settings().copy(enableMcpManager = false),
            skillsInstalled = false,
            trustedFolderBound = false,
        )
        assertTrue(Capability.MCP_USE in effective.capabilities)
        assertFalse(Capability.MCP_ADMIN in effective.capabilities)

        // 反向：开关开着但助手没绑定服务器，使用侧仍被扣除
        val emptyAssistant = ChatMode.STANDARD.policy().withAvailability(
            assistant = Assistant(),
            settings = settings(),
            skillsInstalled = false,
            trustedFolderBound = false,
        )
        assertFalse(Capability.MCP_USE in emptyAssistant.capabilities)
    }

    @Test
    fun knowledgeRestrictedWhenNoBoundBaseExists() {
        // 绑定 id 悬空（库已全部删除）时 KNOWLEDGE 被扣除，避免注入指向空库的环境块
        val assistantWithKb = Assistant(knowledgeBaseIds = setOf(Uuid.random()))
        val kept = ChatMode.STANDARD.policy().withAvailability(
            assistant = assistantWithKb,
            settings = settings(),
            skillsInstalled = false,
            trustedFolderBound = false,
            knowledgeReady = true,
        )
        assertTrue(Capability.KNOWLEDGE in kept.capabilities)

        val restricted = ChatMode.STANDARD.policy().withAvailability(
            assistant = assistantWithKb,
            settings = settings(),
            skillsInstalled = false,
            trustedFolderBound = false,
            knowledgeReady = false,
        )
        assertFalse(Capability.KNOWLEDGE in restricted.capabilities)
    }

    @Test
    fun resolveModePrefersAssistantThenGlobalOnly() {
        val base = settings()
        // 助手显式配置优先
        assertEquals(
            ChatMode.MINIMAL,
            resolveMode(assistant(workspaceId = "11111111-1111-1111-1111-111111111111", defaultMode = ChatMode.MINIMAL.name), base),
        )
        // 全局显式配置
        assertEquals(
            ChatMode.CREATIVE,
            resolveMode(assistant(), settings(defaultMode = ChatMode.CREATIVE.name)),
        )
        // 无显式配置 -> null，表示跟随助手配置
        assertNull(resolveMode(assistant(workspaceId = "11111111-1111-1111-1111-111111111111"), base))
        assertNull(resolveMode(assistant(), base))
    }

    @Test
    fun resolveConversationPolicyUsesConversationModeFirst() {
        val asst = assistant()
        val base = settings()
        // 会话内置模式
        assertEquals(
            ChatMode.MINIMAL.policy(),
            resolveConversationPolicy(conversation(ChatMode.MINIMAL.name), asst, base),
        )
        // 会话自定义模式
        val custom = CustomModeConfig(id = "custom-id-1", name = "自定义", policy = ChatModePolicy(capabilities = setOf(Capability.WORKSPACE)))
        assertEquals(
            custom.policy,
            resolveConversationPolicy(conversation(ModeRefs.custom(custom.id)), asst, settings(customModes = listOf(custom))),
        )
        // 未知自定义 id 回退 STANDARD
        assertEquals(
            ChatMode.STANDARD.policy(),
            resolveConversationPolicy(conversation(ModeRefs.custom("missing")), asst, base),
        )
        // 非法枚举名回退 STANDARD
        assertEquals(
            ChatMode.STANDARD.policy(),
            resolveConversationPolicy(conversation("NOT_A_MODE"), asst, base),
        )
        // 会话未快照 -> 跟随助手配置
        assertEquals(
            ChatModePolicy.UNRESTRICTED,
            resolveConversationPolicy(conversation(null), assistant(workspaceId = "11111111-1111-1111-1111-111111111111"), base),
        )
        // 防御：字面量 follow_assistant 也按跟随助手配置解析
        assertEquals(
            ChatModePolicy.UNRESTRICTED,
            resolveConversationPolicy(conversation(ModeRefs.FOLLOW_ASSISTANT), asst, base),
        )
    }

    @Test
    fun customDefaultModeResolvesFromAssistantAndGlobal() {
        val custom = CustomModeConfig(
            id = "custom-default",
            name = "自定义默认",
            policy = ChatModePolicy(capabilities = setOf(Capability.WORKSPACE)),
        )
        val settingsWithCustom = settings(
            customModes = listOf(custom),
            defaultMode = ModeRefs.custom(custom.id),
        )
        val customRef = ModeRefs.custom(custom.id)

        // 全局默认只用于新建会话快照，mode 为 null 的会话仍然跟随助手配置
        assertEquals(ChatModePolicy.UNRESTRICTED, resolveConversationPolicy(conversation(null), assistant(), settingsWithCustom))
        assertEquals(customRef, resolveModeRef(assistant(), settingsWithCustom))

        // 助手默认优先，且不受全局覆盖
        val assistantCustom = assistant(defaultMode = customRef)
        assertEquals(ChatModePolicy.UNRESTRICTED, resolveConversationPolicy(conversation(null), assistantCustom, settingsWithCustom))
        assertEquals(customRef, resolveModeRef(assistantCustom, settingsWithCustom))

        // 显式引用自定义模式时使用自定义策略
        assertEquals(custom.policy, resolveConversationPolicy(conversation(customRef), assistant(), settingsWithCustom))

        // 自定义模式被删除后，显式引用回退标准
        assertEquals(
            ChatMode.STANDARD.policy(),
            resolveConversationPolicy(conversation(customRef), assistantCustom, settings()),
        )
    }

    @Test
    fun builtinOverridesTakeEffectInConversationResolution() {
        val override = ChatModePolicy(capabilities = setOf(Capability.LOCAL_TOOLS, Capability.SEARCH))
        val settingsWithOverride = settings(
            defaultMode = ChatMode.STANDARD.name,
            builtinModeOverrides = mapOf(ChatMode.STANDARD to override),
        )

        assertEquals(
            ChatModePolicy.UNRESTRICTED,
            resolveConversationPolicy(conversation(null), assistant(), settingsWithOverride),
        )
        assertEquals(
            override,
            resolveConversationPolicy(
                conversation(ChatMode.STANDARD.name),
                assistant(),
                settingsWithOverride,
            ),
        )
    }
}
