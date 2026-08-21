package me.rerere.rikkahub.service

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.model.Capability
import me.rerere.rikkahub.data.model.ChatModePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class ModeToolFilterTest {

    private fun tool(name: String) = Tool(
        name = name,
        description = name,
        execute = { emptyList() },
    )

    @Test
    fun keepsUseSkillOnlyWhenEnabled() {
        val policy = ChatModePolicy(capabilities = setOf(Capability.SKILL_USE))
        val filtered = filterSkillToolsByMode(
            tools = listOf(tool("use_skill"), tool("skill_admin_list")),
            policy = policy,
        )

        assertEquals(listOf("use_skill"), filtered.map { it.name })
    }

    @Test
    fun keepsAdminOnlyWhenEnabled() {
        val policy = ChatModePolicy(capabilities = setOf(Capability.SKILL_ADMIN))
        val filtered = filterSkillToolsByMode(
            tools = listOf(tool("use_skill"), tool("skill_admin_list")),
            policy = policy,
        )

        assertEquals(listOf("skill_admin_list"), filtered.map { it.name })
    }

    @Test
    fun dropsAllSkillToolsWhenDisabled() {
        val policy = ChatModePolicy(capabilities = emptySet())
        val filtered = filterSkillToolsByMode(
            tools = listOf(tool("use_skill"), tool("skill_admin_list")),
            policy = policy,
        )

        assertEquals(emptyList<String>(), filtered.map { it.name })
    }
}
