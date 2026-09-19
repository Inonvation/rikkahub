package me.rerere.rikkahub.data.ai

import me.rerere.rikkahub.data.ai.prompts.buildAgentBehaviorPrompt
import me.rerere.rikkahub.data.ai.tools.ASSISTANT_ADMIN_SYSTEM_PROMPT
import me.rerere.rikkahub.data.ai.tools.MEMORY_TOOL_SYSTEM_PROMPT
import me.rerere.rikkahub.data.ai.tools.PROVIDER_ADMIN_SYSTEM_PROMPT
import me.rerere.rikkahub.data.ai.tools.SCRAPE_TOOL_SYSTEM_PROMPT
import me.rerere.rikkahub.data.ai.tools.SUBAGENT_SPAWN_SYSTEM_PROMPT
import me.rerere.rikkahub.data.ai.tools.modeToolsSystemPrompt
import me.rerere.rikkahub.data.ai.tools.searchToolSystemPrompt
import me.rerere.rikkahub.data.model.AgentBehaviorProfile
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 稳定提示词片段指纹：任一内容变化都会失败，提醒同步升级 [PROMPT_REVISION]。
 *
 * 覆盖面（2026-09 扩展）：行为层各档、身份兜底、记忆工具指针与读取策略、记忆块渲染，
 * 以及本轮从 description 上移到 system 信道的工具规范（搜索/抓取/子代理/模式/提供商/助手管理）
 * ——这些片段进 system、逐字节稳定、参与前缀缓存，改动影响所有用户。
 *
 * 不含：含运行期插值的片段（如日历/屏幕时间的时区行）——指纹须与机器环境无关。
 */
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
            searchToolSystemPrompt(concise = false),
            searchToolSystemPrompt(concise = true),
            SCRAPE_TOOL_SYSTEM_PROMPT,
            SUBAGENT_SPAWN_SYSTEM_PROMPT,
            modeToolsSystemPrompt(),
            PROVIDER_ADMIN_SYSTEM_PROMPT,
            ASSISTANT_ADMIN_SYSTEM_PROMPT,
        )

        assertEquals("PROMPT_REVISION 未随稳定提示词片段同步升级", "58513989da0e7f52", fingerprint)
    }
}
