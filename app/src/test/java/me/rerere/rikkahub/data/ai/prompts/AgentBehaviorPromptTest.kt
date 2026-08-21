package me.rerere.rikkahub.data.ai.prompts

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.model.AgentBehaviorProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentBehaviorPromptTest {

    @Test
    fun standardProfileInjectsBalancedGuidanceOnly() {
        val prompt = buildAgentBehaviorPrompt(tools = emptyList(), profile = AgentBehaviorProfile.STANDARD)
        assertTrue(prompt.contains("## Mode: Balanced"))
        assertFalse(prompt.contains("## Mode: Workspace"))
        assertFalse(prompt.contains("## Mode: Management"))
        assertFalse(prompt.contains("## Mode: Minimal"))
    }

    @Test
    fun workspaceProfileInjectsWorkspaceGuidance() {
        val prompt = buildAgentBehaviorPrompt(tools = emptyList(), profile = AgentBehaviorProfile.WORKSPACE)
        assertTrue(prompt.contains("## Mode: Workspace"))
        assertTrue(prompt.contains("execute continuously"))
        assertFalse(prompt.contains("## Mode: Management"))
    }

    @Test
    fun managementProfileInjectsApprovalAndRollbackGuidance() {
        val prompt = buildAgentBehaviorPrompt(tools = emptyList(), profile = AgentBehaviorProfile.MANAGEMENT)
        assertTrue(prompt.contains("## Mode: Management"))
        assertTrue(prompt.contains("wait for approval"))
        assertTrue(prompt.contains("rollback or verification plan"))
        assertTrue(prompt.contains("keep workspace behavior"))
        assertFalse(prompt.contains("## Mode: Workspace"))
    }

    @Test
    fun minimalProfileInjectsNoUnrequestedToolGuidance() {
        val prompt = buildAgentBehaviorPrompt(tools = emptyList(), profile = AgentBehaviorProfile.MINIMAL)
        assertTrue(prompt.contains("## Mode: Minimal"))
        assertTrue(prompt.contains("do not call tools unless the user explicitly asks"))
        assertFalse(prompt.contains("## Mode: Balanced"))
    }

    @Test
    fun minimalProfileSkipsToolGroups() {
        val prompt = buildAgentBehaviorPrompt(
            tools = listOf(Tool(name = "get_time", description = "get time", execute = { emptyList() })),
            profile = AgentBehaviorProfile.MINIMAL,
        )
        assertFalse(prompt.contains("## Tool Groups"))
        assertFalse(prompt.contains("Available tools are grouped below"))
    }

    @Test
    fun legacyProfileOmitsModeSectionsAndKeepsToolGroups() {
        val prompt = buildAgentBehaviorPrompt(
            tools = listOf(Tool(name = "get_time", description = "get time", execute = { emptyList() })),
            profile = AgentBehaviorProfile.LEGACY,
        )
        assertFalse(prompt.contains("## Mode:"))
        assertTrue(prompt.contains("## Plan & Act"))
        assertTrue(prompt.contains("## Tool Groups"))
    }
}
