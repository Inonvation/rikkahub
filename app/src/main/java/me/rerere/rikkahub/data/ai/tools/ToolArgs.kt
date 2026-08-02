package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart

/**
 * 容错解析工具参数里的数组字段。
 * AI 可能返回非数组（单个字符串/对象），此时包成单元素列表而不是静默丢数据。
 */
fun parseArrayField(params: JsonObject, key: String): String {
    val el = params[key] ?: return "[]"
    return when (el) {
        is JsonArray -> el.toString()
        is JsonPrimitive -> buildJsonArray { add(el) }.toString()
        is JsonObject -> buildJsonArray { add(el) }.toString()
        else -> "[]"
    }
}

/**
 * 构造工具错误返回（JSON：{"error": true, "message": "..."}）。
 * 学习工具（update/delete/search/stats）共用。
 */
internal fun errorResult(message: String): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("error", true)
            put("message", JsonPrimitive(message))
        }.toString()
    )
)
