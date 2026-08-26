package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalString
import java.security.MessageDigest
import java.time.LocalDate

internal const val PROMPT_REVISION = "2026-08-21-v1"

internal fun currentDateLabel(): String = LocalDate.now().toLocalString(true)

/** 提示词内容指纹：任一稳定提示词片段变化时测试会失败，提醒同步升级 [PROMPT_REVISION]。 */
internal fun promptFingerprint(vararg parts: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { part -> digest.update(part.toByteArray(Charsets.UTF_8)) }
    return digest.digest().take(8).joinToString("") { "%02x".format(it) }
}

/** 最近一次主生成链路的系统提示词指标，供调试页展示。 */
object PromptMetrics {
    @Volatile
    var lastSystemPromptChars: Int = 0

    @Volatile
    var lastApproxTokens: Int = 0

    @Volatile
    var lastToolCount: Int = 0

    /** provider 收到 tools 数组（function schema）的近似字符成本。 */
    @Volatile
    var lastToolSchemaChars: Int = 0

    /** 各工具族数量，用于看清「你好」到底被哪些能力撑大。 */
    @Volatile
    var lastToolFamilies: Map<String, Int> = emptyMap()

    /**
     * system prompt + 工具 schema 的近似静态字符成本（不含历史消息），
     * 供调试页对照 contextTokenLimit 判断是否超预算。
     */
    @Volatile
    var lastStaticCostChars: Int = 0

    /** 静态成本占 contextTokenLimit 的比例（4 字符 ≈ 1 token 的粗略估算）。 */
    @Volatile
    var lastStaticCostRatio: Float = 0f

    /** 静态成本是否超过 [STATIC_COST_BUDGET_RATIO]，调试页置顶警示。 */
    @Volatile
    var lastStaticCostOverBudget: Boolean = false

    val revision: String get() = PROMPT_REVISION
}

/** 静态成本占上下文窗口的比例预算：超过即认为“能力注入过重”，调试页提示。 */
internal const val STATIC_COST_BUDGET_RATIO: Float = 0.30f

/**
 * 调试用工具族归类：直接委托给 [classifyToolFamily] 的统计口径（metricLabel）。
 * 与 AgentBehaviorPrompt 的工具分组共享同一份判定来源，避免前缀映射漂移。
 */
internal fun toolFamilyForMetrics(name: String): String = classifyToolFamily(name).metricLabel

/** 记忆 prompt 的防御性上限：即使调用侧传入超量记忆，也只渲染前 N 条，防止 system 膨胀。 */
internal const val MAX_MEMORY_PROMPT_ENTRIES = 40

/** 单条记忆注入 system 的内容截断长度（保留头部），避免一条超长记忆撑爆上下文。 */
internal const val MAX_MEMORY_ENTRY_CHARS = 512

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    buildString {
        appendLine()
        append("**Memories**")
        appendLine()
        append("Memories from past conversations. Reference them when relevant.")
        appendLine()
        val json = buildJsonArray {
            memories.take(MAX_MEMORY_PROMPT_ENTRIES).forEach { memory ->
                add(buildJsonObject {
                    put("id", memory.id)
                    put("content", memory.content.take(MAX_MEMORY_ENTRY_CHARS))
                })
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
    }

/**
 * 收集本轮每个工具要注入 system 的说明，并按内容去重。
 *
 * 同一份 systemPrompt（如 SkillTools 挂在多个技能工具上）只注入一次，避免重复粘贴
 * 相同段落放大 system 体积、稀释指令。去重以「内容完全相同」为准，不会误伤真正独立的工具说明。
 */
internal fun buildToolSystemPrompts(
    tools: List<Tool>,
    model: Model,
    messages: List<UIMessage>,
): List<String> =
    tools.mapNotNull { tool ->
        tool.systemPrompt(model, messages).takeIf { it.isNotBlank() }
    }.distinct()
