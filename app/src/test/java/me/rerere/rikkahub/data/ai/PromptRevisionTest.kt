package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.ai.prompts.buildAgentBehaviorPrompt
import me.rerere.rikkahub.data.model.AgentBehaviorProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class PromptRevisionTest {

    @Test
    fun revisionMatchesCurrentPromptFingerprint() {
        val fingerprint = promptFingerprint(
            buildAgentBehaviorPrompt(emptyList(), AgentBehaviorProfile.LEGACY),
            buildAgentBehaviorPrompt(emptyList(), AgentBehaviorProfile.STANDARD),
            buildMemoryPrompt(emptyList()),
        )

        assertEquals("dc6727e77cd9183b", fingerprint)
    }
}
