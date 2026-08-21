package me.rerere.rikkahub.data.model

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
        trustedFolderActive: Boolean,
    ): ChatMode? = resolveModeRef(assistant, settings, trustedFolderActive)
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
    fun unrestrictedPolicyMatchesPreModeCapabilities() {
        val policy = ChatModePolicy.UNRESTRICTED
        assertEquals(ChatModePolicy.UNRESTRICTED_CAPABILITIES, policy.capabilities)
        assertFalse(Capability.CREATIVE_TOOLS in policy.capabilities)
        assertTrue(Capability.SKILL_ADMIN in policy.capabilities)
        assertTrue(Capability.MCP_ADMIN in policy.capabilities)
        assertEquals(AgentBehaviorProfile.LEGACY, policy.behaviorProfile)
    }

    @Test
    fun resolveModePrefersAssistantThenGlobalOnly() {
        val base = settings()
        // 助手显式配置优先
        assertEquals(
            ChatMode.MINIMAL,
            resolveMode(assistant(workspaceId = "11111111-1111-1111-1111-111111111111", defaultMode = ChatMode.MINIMAL.name), base, false),
        )
        // 全局显式配置
        assertEquals(
            ChatMode.CREATIVE,
            resolveMode(assistant(), settings(defaultMode = ChatMode.CREATIVE.name), false),
        )
        // 无显式配置 -> null，表示跟随助手配置
        assertNull(resolveMode(assistant(workspaceId = "11111111-1111-1111-1111-111111111111"), base, false))
        assertNull(resolveMode(assistant(), base, true))
    }

    @Test
    fun resolveConversationPolicyUsesConversationModeFirst() {
        val asst = assistant()
        val base = settings()
        // 会话内置模式
        assertEquals(
            ChatMode.MINIMAL.policy(),
            resolveConversationPolicy(conversation(ChatMode.MINIMAL.name), asst, base, false),
        )
        // 会话自定义模式
        val custom = CustomModeConfig(id = "custom-id-1", name = "自定义", policy = ChatModePolicy(capabilities = setOf(Capability.WORKSPACE)))
        assertEquals(
            custom.policy,
            resolveConversationPolicy(conversation(ModeRefs.custom(custom.id)), asst, settings(customModes = listOf(custom)), false),
        )
        // 未知自定义 id 回退 STANDARD
        assertEquals(
            ChatMode.STANDARD.policy(),
            resolveConversationPolicy(conversation(ModeRefs.custom("missing")), asst, base, false),
        )
        // 非法枚举名回退 STANDARD
        assertEquals(
            ChatMode.STANDARD.policy(),
            resolveConversationPolicy(conversation("NOT_A_MODE"), asst, base, false),
        )
        // 会话未快照 -> 跟随助手配置
        assertEquals(
            ChatModePolicy.UNRESTRICTED,
            resolveConversationPolicy(conversation(null), assistant(workspaceId = "11111111-1111-1111-1111-111111111111"), base, false),
        )
        // 防御：字面量 follow_assistant 也按跟随助手配置解析
        assertEquals(
            ChatModePolicy.UNRESTRICTED,
            resolveConversationPolicy(conversation(ModeRefs.FOLLOW_ASSISTANT), asst, base, false),
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
        assertEquals(ChatModePolicy.UNRESTRICTED, resolveConversationPolicy(conversation(null), assistant(), settingsWithCustom, false))
        assertEquals(customRef, resolveModeRef(assistant(), settingsWithCustom, false))

        // 助手默认优先，且不受全局覆盖
        val assistantCustom = assistant(defaultMode = customRef)
        assertEquals(ChatModePolicy.UNRESTRICTED, resolveConversationPolicy(conversation(null), assistantCustom, settingsWithCustom, false))
        assertEquals(customRef, resolveModeRef(assistantCustom, settingsWithCustom, false))

        // 显式引用自定义模式时使用自定义策略
        assertEquals(custom.policy, resolveConversationPolicy(conversation(customRef), assistant(), settingsWithCustom, false))

        // 自定义模式被删除后，显式引用回退标准
        assertEquals(
            ChatMode.STANDARD.policy(),
            resolveConversationPolicy(conversation(customRef), assistantCustom, settings(), false),
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
            resolveConversationPolicy(conversation(null), assistant(), settingsWithOverride, false),
        )
        assertEquals(
            override,
            resolveConversationPolicy(
                conversation(ChatMode.STANDARD.name),
                assistant(),
                settingsWithOverride,
                false,
            ),
        )
    }
}
