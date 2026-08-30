package me.rerere.rikkahub.ui.components.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * ask_user 工具参数中单个问题的解析结果（UI 渲染模型），无 Compose 依赖，可 JVM 单测。
 */
data class AskUserQuestion(
    val id: String,
    val question: String,
    val rationale: String = "",
    val options: List<String> = emptyList(),
    val selectionType: String = "text",
    val placeholder: String = "",
    val required: Boolean = true,
)

/**
 * 解析 ask_user 工具调用参数中的 questions 列表。
 *
 * 容错策略（模型输出不可信）：
 * - 结构性畸形（questions 非数组、参数非 JSON 对象）返回空列表，调用方据此显示空态；
 * - 单个条目畸形（非对象、字段类型漂移）只丢弃/降级该条目，不拖垮其余问题；
 * - selection_type 大小写/别名漂移归一到四种标准类型，未知值降级为 text；
 * - id 缺失或为空时按序兜底为 q1/q2/...，避免多问题共享 "" 键互相覆盖答案。
 */
internal fun parseAskUserQuestions(arguments: JsonElement): List<AskUserQuestion> {
    val questionsArray = runCatching { arguments.jsonObject["questions"] }.getOrNull() as? JsonArray
        ?: return emptyList()
    return questionsArray.mapIndexedNotNull { index, element ->
        val obj = element as? JsonObject ?: return@mapIndexedNotNull null
        AskUserQuestion(
            id = obj.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: "q${index + 1}",
            question = obj.stringOrNull("question") ?: "",
            rationale = obj.stringOrNull("rationale") ?: "",
            options = (obj["options"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?: emptyList(),
            selectionType = normalizeSelectionType(obj.stringOrNull("selection_type")),
            placeholder = obj.stringOrNull("placeholder") ?: "",
            required = obj.stringOrNull("required")?.let { it != "false" } ?: true,
        )
    }
}

/**
 * selection_type 容错归一：schema 虽声明了 enum，模型仍会输出大小写或别名变体
 * （"Multi"/"multiple"/"multi_select" 等），静默降级为 text 会丢掉多选/确认语义，这里统一映射。
 */
private fun normalizeSelectionType(raw: String?): String = when (raw?.trim()?.lowercase()) {
    "single", "single_choice", "singlechoice", "choice", "radio" -> "single"
    "multi", "multiple", "multi_select", "multiselect", "multi_choice", "checkbox", "checkboxes" -> "multi"
    "confirmation", "confirm", "yes_no", "yesno", "boolean", "bool" -> "confirmation"
    // text / null / 未知值一律按文本题兜底
    else -> "text"
}

/** 按字符串字段安全取值：非字符串原始值返回 null，对象/数组不会抛异常（JsonNull 得 null） */
private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull
