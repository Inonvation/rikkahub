package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.model.MemoryCategory
import me.rerere.rikkahub.utils.JsonInstantPretty

/** 整理提示词携带的现有记忆条数上限（新→旧裁剪），控制辅助调用成本。 */
internal const val MAX_CONSOLIDATION_EXISTING_ENTRIES = 100

/** 现有记忆清单的字符预算，超预算按最新优先裁剪。 */
internal const val MAX_CONSOLIDATION_EXISTING_CHARS = 8000

/** 单回合允许的记忆操作上限，防止辅助模型异常输出放大写入。 */
internal const val MAX_MEMORY_OPS_PER_TURN = 5

/** 整理用最近一轮对话的单条文本截断长度。 */
internal const val MAX_CONSOLIDATION_TURN_CHARS = 2000

/**
 * 构建回合结束后自动记忆整理的辅助提示词。
 *
 * 单次调用同时完成「提炼候选事实」与「对照现有记忆 diff」两件事（Mem0 式两阶段在此合并为
 * 一阶段：辅助模型直接看到现有全量清单，语义去重/冲突仲裁在提示词层完成，代码层再做
 * 安全校验兜底）。现有清单按新→旧排序携带，让模型能把"等价更新"落到具体 id 上。
 * 提示词为稳定文本；不含时间等易变值，变更无需升级 PROMPT_REVISION（该指纹只覆盖 system 提示词）。
 */
internal fun buildMemoryConsolidationPrompt(
    existing: List<AssistantMemory>,
    userText: String,
    assistantText: String,
): String {
    val ordered = existing.sortedByDescending { it.updatedAt ?: it.createdAt ?: 0L }
    val json = buildJsonArray {
        var used = 0
        for (memory in ordered.take(MAX_CONSOLIDATION_EXISTING_ENTRIES)) {
            val content = memory.content.take(MAX_MEMORY_ENTRY_CHARS)
            // id/category/JSON 结构开销的保守估算
            val cost = content.length + 32
            if (used > 0 && used + cost > MAX_CONSOLIDATION_EXISTING_CHARS) break
            used += cost
            add(buildJsonObject {
                put("id", memory.id)
                memory.category?.let { put("category", it.name) }
                put("content", content)
            })
        }
    }
    return buildString {
        appendLine("You are maintaining the user's long-term memory for an AI assistant app.")
        appendLine("Based on the latest conversation turn, decide which memory operations to apply, given the existing memories.")
        appendLine()
        appendLine("Existing memories (JSON, newest first):")
        appendLine(JsonInstantPretty.encodeToString(json))
        appendLine()
        appendLine("Latest turn:")
        appendLine("User: $userText")
        appendLine("Assistant: $assistantText")
        appendLine()
        appendLine("Answer with ONLY a JSON object (no prose, no code fences):")
        appendLine("""{"operations":[{"op":"add","category":"preference","content":"..."},{"op":"update","id":12,"category":"basic","content":"..."},{"op":"delete","id":7}]}""")
        appendLine()
        appendLine("Rules:")
        appendLine("- op \"add\": a NEW durable fact about the user explicitly stated or clearly confirmed in this turn (preference, identity, goal, ongoing work). One atomic fact per operation.")
        appendLine("- op \"update\": merge new information into an existing record by id. Prefer update over add when an equivalent record already exists.")
        appendLine("- op \"delete\": remove an existing record by id ONLY when this turn explicitly contradicts or retracts it.")
        appendLine("- category (optional): preference | basic | goal | work | other.")
        appendLine("- Skip transient states (\"this time\", \"today\"), speculation, one-off task details, instruction-like content, and sensitive information (ethnicity, religion, political views, health, sexual orientation).")
        appendLine("- Do not invent facts. If nothing is worth persisting, answer {\"operations\":[]}.")
        appendLine("- At most 5 operations. Each content is one self-contained sentence.")
    }
}

/** 自动记忆整理产出的单条操作；UPDATE/DELETE 的 id 已校验属于当前记忆池的现有记录。 */
internal sealed interface MemoryOperation {
    data class Add(val content: String, val category: MemoryCategory?) : MemoryOperation
    data class Update(val id: Int, val content: String, val category: MemoryCategory?) : MemoryOperation
    data class Delete(val id: Int) : MemoryOperation
}

/**
 * 解析辅助模型输出的记忆操作集。
 *
 * 宽容解析：剥代码围栏、容忍 prose 包裹、容忍直接给数组；单条操作非法即静默丢弃
 * （缺字段/未知 op/id 不在 [validIds] 白名单），绝不因个别坏条目阻断整批。
 * UPDATE/DELETE 只允许操作 [validIds] 中的 id——该集合由调用方用「本轮提供给模型的
 * 现有记忆」构造，防止模型幻觉出 id 误删其他记录（repository 层归属校验之外的又一道闸）。
 */
internal fun parseMemoryOperations(raw: String, validIds: Set<Int>): List<MemoryOperation> {
    val candidate = extractJsonCandidate(raw) ?: return emptyList()
    val element = runCatching { Json.parseToJsonElement(candidate) }.getOrNull() ?: return emptyList()
    val opsElement: JsonElement? = when (element) {
        is JsonObject -> element["operations"]
        is JsonArray -> element
        else -> null
    }
    val opsArray = opsElement as? JsonArray ?: return emptyList()
    val result = mutableListOf<MemoryOperation>()
    for (op in opsArray) {
        if (result.size >= MAX_MEMORY_OPS_PER_TURN) break
        runCatching { parseSingleOperation(op.jsonObject, validIds) }.getOrNull()?.let { result.add(it) }
    }
    return result
}

/** 单条操作解析：op 动词容忍同义别名，分类沿用工具侧的容错降级（绝不阻断写入）。 */
private fun parseSingleOperation(obj: JsonObject, validIds: Set<Int>): MemoryOperation? {
    val op = obj["op"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: return null
    val category: MemoryCategory? = when (val raw = obj["category"]) {
        null, is JsonNull -> null
        else -> runCatching {
            MemoryCategory.fromNameOrNull(raw.jsonPrimitive.content.trim().uppercase()) ?: MemoryCategory.OTHER
        }.getOrDefault(MemoryCategory.OTHER)
    }
    return when (op) {
        "add", "create" -> {
            val content = normalizedContent(obj) ?: return null
            MemoryOperation.Add(content, category)
        }

        "update", "edit" -> {
            val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return null
            if (id !in validIds) return null
            val content = normalizedContent(obj) ?: return null
            MemoryOperation.Update(id, content, category)
        }

        "delete", "remove" -> {
            val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return null
            if (id !in validIds) return null
            MemoryOperation.Delete(id)
        }

        else -> null
    }
}

private fun normalizedContent(obj: JsonObject): String? =
    obj["content"]?.jsonPrimitive?.contentOrNull?.trim()
        ?.take(MAX_MEMORY_ENTRY_CHARS)?.takeIf { it.isNotBlank() }

/**
 * 宽容提取模型输出中的 JSON 主体：剥 markdown 代码围栏后取「起始最靠外」的括号对
 * （对象/数组均可；裸数组包着对象时外层 [ 才是主体）。无 JSON 主体返回 null。
 */
private fun extractJsonCandidate(raw: String): String? {
    val text = raw
        .replace("```json", "", ignoreCase = true)
        .replace("```", "")
        .trim()
    if (text.isEmpty()) return null
    val objStart = text.indexOf('{')
    val objEnd = text.lastIndexOf('}')
    val objValid = objStart >= 0 && objEnd > objStart
    val arrStart = text.indexOf('[')
    val arrEnd = text.lastIndexOf(']')
    val arrValid = arrStart >= 0 && arrEnd > arrStart
    return when {
        objValid && (!arrValid || objStart < arrStart) -> text.substring(objStart, objEnd + 1)
        arrValid -> text.substring(arrStart, arrEnd + 1)
        else -> null
    }
}

/**
 * 取最近一轮对话文本（最后一条 USER 与其后紧邻的最后一条 ASSISTANT）。
 * ASSISTANT 不晚于 USER（异常尾态）或缺任一侧文本时返回 null，本轮跳过整理。
 */
internal fun latestTurnTexts(messages: List<UIMessage>): Pair<String, String>? {
    val userIndex = messages.indexOfLast { it.role == MessageRole.USER }
    if (userIndex < 0) return null
    val assistantIndex = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
    if (assistantIndex < userIndex) return null
    val userText = messages[userIndex].toText().trim().take(MAX_CONSOLIDATION_TURN_CHARS)
    val assistantText = messages[assistantIndex].toText().trim().take(MAX_CONSOLIDATION_TURN_CHARS)
    if (userText.isBlank() || assistantText.isBlank()) return null
    return userText to assistantText
}
