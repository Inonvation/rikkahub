package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.ai.prompts.buildAgentBehaviorPrompt
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
            sampleMemoryBlock,
        )

        assertEquals("4dcc11b19e939214", fingerprint)
    }
}
