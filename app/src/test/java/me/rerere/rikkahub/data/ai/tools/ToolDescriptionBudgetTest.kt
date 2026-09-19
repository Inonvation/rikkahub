package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.WIRE_DESCRIPTION_LIMIT
import me.rerere.ai.core.WIRE_PARAM_DESCRIPTION_LIMIT
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.SkillMetadata
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 工具声明 wire 预算护栏（与 PromptBudgetTest 互补：那个管 system 体积，这个管 schema 可送达性）。
 *
 * 背景（回归锚点）：wire 层把工具 description 截断到 [WIRE_DESCRIPTION_LIMIT] 字符、
 * 参数内嵌 description 截断到 [WIRE_PARAM_DESCRIPTION_LIMIT] 字符（见 WireTool.kt）。
 * 2026-09 前多个工具把"何时用/输出格式/护栏句"写在 300 字符之后，被静默砍掉从未送达模型
 * （搜索的 `[citation,domain](id)` 引用格式、子代理清单、记忆护栏句等）。
 *
 * 约定：description 只写触发条件（≤300 字符），使用策略/格式规范放 systemPrompt（不裁剪）。
 *
 * 覆盖边界：本测试只覆盖可在 JVM 构造的工具（不依赖 Android Context/DAO 的那部分）。
 * 全量扫描（含 workspace/calendar/study/管理等依赖 Context 的工具）用仓库脚本：
 * `python tools/check_tool_desc.py`——新增工具后先跑它，再回这里补样例。
 */
class ToolDescriptionBudgetTest {

    /** 可在 JVM 单测构造的代表性工具集：默认助手与搜索路径实际会注入的那些。 */
    private fun jvmBuildableTools(concise: Boolean = false): List<Tool> = buildList {
        addAll(
            createSearchTools(
                Settings(
                    searchServices = listOf(SearchServiceOptions.ExaOptions()),
                    searchServiceSelected = 0,
                ),
                concise = concise,
            )
        )
        addAll(
            buildMemoryTools(
                json = Json,
                onCreation = { content, category -> AssistantMemory(id = 0, content = content, category = category) },
                onUpdate = { id, content, category -> AssistantMemory(id = id, content = content, category = category) },
                onDelete = {},
            )
        )
        addAll(
            createSkillTools(
                enabledSkills = setOf("alpha"),
                listAllSkills = {
                    listOf(
                        SkillMetadata(
                            name = "alpha",
                            description = "Test skill for budget assertions",
                            skillDir = File("."),
                        )
                    )
                },
            )
        )
    }

    /** 递归收集 schema 里的全部 description 字符串（参数内嵌描述统一受 [WIRE_PARAM_DESCRIPTION_LIMIT] 约束）。 */
    private fun collectParamDescriptions(schema: InputSchema?): List<String> {
        val props = (schema as? InputSchema.Obj)?.properties ?: return emptyList()
        val found = mutableListOf<String>()
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (key == "description" && value is JsonPrimitive) {
                        value.contentOrNull?.let { found.add(it) }
                    } else {
                        walk(value)
                    }
                }

                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        props.values.forEach(::walk)
        return found
    }

    @Test
    fun `builtin tool descriptions fit the wire budget`() {
        val violations = jvmBuildableTools()
            .filter { it.description.length > WIRE_DESCRIPTION_LIMIT }
            .map { "${it.name}=${it.description.length}" }
        assertTrue(
            "工具 description 超出 wire 预算（$WIRE_DESCRIPTION_LIMIT 字符）：$violations。" +
                "说明该把使用策略移入 systemPrompt，而不是让运行时截断砍掉语义。",
            violations.isEmpty(),
        )
    }

    @Test
    fun `concise subagent search variants fit the wire budget`() {
        // 子代理装配走 concise 变体（desc/systemPrompt 都更短），单独断言防回归
        val violations = jvmBuildableTools(concise = true)
            .filter { it.description.length > WIRE_DESCRIPTION_LIMIT }
            .map { "${it.name}=${it.description.length}" }
        assertTrue("concise 变体超预算：$violations", violations.isEmpty())
    }

    @Test
    fun `builtin param descriptions fit the wire budget`() {
        val violations = jvmBuildableTools().flatMap { tool ->
            collectParamDescriptions(tool.parameters())
                .filter { it.length > WIRE_PARAM_DESCRIPTION_LIMIT }
                .map { "${tool.name}: ${it.take(60)}…(${it.length})" }
        }
        assertTrue(
            "参数 description 超出 wire 预算（$WIRE_PARAM_DESCRIPTION_LIMIT 字符）：$violations。" +
                "参数描述没有 systemPrompt 逃生通道，必须在声明处控制长度。",
            violations.isEmpty(),
        )
    }

    @Test
    fun `search citation contract is delivered via system prompt`() {
        // 回归锚点：`[citation,domain](id)` 的完整定义必须出现在 system 信道（description 会被截断），
        // 否则 UI 侧的 citation 角标渲染（MarkdownNew.kt）永远收不到对应格式的回答。
        val tool = jvmBuildableTools().single { it.name == "search_web" }
        val systemText = tool.systemPrompt(Model(modelId = "test-model"), emptyList())
        assertTrue("[citation,domain](id) 未送达 system 信道", systemText.contains("[citation,domain](id)"))
        assertTrue("images 规范未送达", systemText.contains("images[]"))
    }

    @Test
    fun `simple tool names are unique across the buildable set`() {
        val names = jvmBuildableTools().map { it.name }
        assertTrue("工具重名: ${names.groupBy { it }.filter { it.value.size > 1 }}", names.size == names.toSet().size)
    }
}
