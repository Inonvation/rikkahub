package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.ai.prompts.buildAgentBehaviorPrompt
import me.rerere.rikkahub.data.ai.tools.MEMORY_TOOL_SYSTEM_PROMPT
import me.rerere.rikkahub.data.model.AgentBehaviorProfile
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class PromptRevisionTest {

    @Test
    fun revisionMatchesCurrentPromptFingerprint() {
        val sampleMemoryBlock = buildMemoryContextBlock(
            listOf(
                AssistantMemory(
                    id = 1,
                    content = "sample memory",
                    category = MemoryCategory.PREFERENCE,
                    createdAt = 1000L,
                    updatedAt = 1000L,
                )
            )
        )
        val fingerprint = promptFingerprint(
            buildAgentBehaviorPrompt(emptyList(), AgentBehaviorProfile.LEGACY),
            buildAgentBehaviorPrompt(emptyList(), AgentBehaviorProfile.STANDARD),
            buildAgentBehaviorPrompt(emptyList(), AgentBehaviorProfile.WORKSPACE),
            buildAgentBehaviorPrompt(emptyList(), AgentBehaviorProfile.MANAGEMENT),
            buildAgentBehaviorPrompt(emptyList(), AgentBehaviorProfile.MINIMAL),
            BASE_IDENTITY_PROMPT,
            MEMORY_TOOL_SYSTEM_PROMPT,
            MEMORY_CONTEXT_POLICY_LINES,
            sampleMemoryBlock,
        )

        assertEquals("PROMPT_REVISION 未随稳定提示词片段同步升级", "e50a0e7b0d86f759", fingerprint)
    }
}
