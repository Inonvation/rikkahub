package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray

/**
 * 容错解析工具参数里的数组字段。
 * AI 可能返回非数组（单个字符串/对象），此时包成单元素列表而不是静默丢数据。
 */
fun parseArrayField(params: JsonObject, key: String): String {
    val el = params[key] ?: return "[]"
    return when (el) {
        is JsonArray -> el.toString()
        is JsonPrimitive -> buildJsonArray { add(el) }.toString()
        else -> "[]"
    }
}
