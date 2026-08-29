package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.prompts.buildAgentBehaviorPrompt
import me.rerere.rikkahub.data.model.AgentBehaviorProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统提示词体积与内容回归护栏（与 PromptRevisionTest 的指纹互补）：
 * - 指纹管「变了要知情」：稳定片段任何字节变化都会触发 PROMPT_REVISION 提醒；
 * - 本文件管「膨胀要拦截」：空配置基线的字符预算与能力叙述防泄漏——能力文本悄悄爬回
 *   空助手上下文时（能力隔离的核心收益被回退），编译与指纹都不会报警，只有这里会。
 */
class PromptBudgetTest {

    private fun tool(name: String): Tool = Tool(
        name = name,
        description = "desc",
        execute = { emptyList() },
    )

    /** 空助手 + 跟随模式的 system 基线：身份兜底行 + LEGACY 行为段（无工具、无模式引导）。 */
    private fun emptyAssistantBaseline(): String =
        "$BASE_IDENTITY_PROMPT\n\n" + buildAgentBehaviorPrompt(emptyList(), AgentBehaviorProfile.LEGACY)

    @Test
    fun `empty assistant follow-mode baseline stays lean`() {
        val baseline = emptyAssistantBaseline()
        assertTrue("空配置基线膨胀到 ${baseline.length} 字符（预算 1500）", baseline.length <= 1500)
    }

    @Test
    fun `empty assistant baseline carries no capability narratives`() {
        val lowered = emptyAssistantBaseline().lowercase()
        // 能力隔离回归锚点（原缺陷：空助手自称能配置管理 MCP/技能）：
        // 这些能力词只允许随对应工具/环境块注入出现，不得进入空配置基线
        listOf(
            "mcp", "skill", "workspace", "trusted folder", "knowledge",
            "management", "admin", "sub-agent",
        ).forEach { token ->
            assertFalse("基线泄漏能力叙述: '$token'", lowered.contains(token))
        }
    }

    @Test
    fun `behavior profiles stay within per-profile budgets`() {
        // 各行为档的字符预算（约为当前实际长度的 1.5 倍）：给模式段与通用段的膨胀设上限
        val budgets = mapOf(
            AgentBehaviorProfile.LEGACY to 1200,
            AgentBehaviorProfile.MINIMAL to 1500,
            AgentBehaviorProfile.STANDARD to 1700,
            AgentBehaviorProfile.WORKSPACE to 2000,
            AgentBehaviorProfile.MANAGEMENT to 2200,
        )
        budgets.forEach { (profile, budget) ->
            val prompt = buildAgentBehaviorPrompt(emptyList(), profile)
            assertTrue(
                "$profile 行为段膨胀到 ${prompt.length} 字符（预算 $budget）",
                prompt.length <= budget,
            )
        }
    }

    @Test
    fun `management section references only injected inspection tools`() {
        val tools = listOf(
            tool("admin_inventory"),
            tool("provider_list"),
            tool("assistant_list"),
            tool("todo_write"),
        )
        val prompt = buildAgentBehaviorPrompt(tools, AgentBehaviorProfile.MANAGEMENT)
        // 动态点名：只列本轮实际注入的感知工具（admin_inventory + *_list）
        assertTrue(
            "管理段未按注入集合生成感知名单",
            prompt.contains("call admin_inventory, provider_list, assistant_list before changing anything."),
        )
        // 未注入的管理工具不得出现在名单里
        assertFalse(prompt.contains("skill_admin_list"))
        assertFalse(prompt.contains("mcp_admin_list"))
        assertFalse(prompt.contains("search_admin_list"))
    }
}
