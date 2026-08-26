package me.rerere.ai.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Wire 侧工具 schema 归一化。
 *
 * 工具 schema（description + 参数描述）是每次请求的固定 token 成本。这里对发送给 provider 的
 * schema 做纯裁剪，**不删除任何参数/必填/枚举**，只截断冗余的长说明文本。详细用法仍由
 * `tool.systemPrompt`（system 消息，去重后仅注入一次）与 Agent Behavior 的工具分组承载。
 */

/** 工具级 description 的默认 wire 长度上限。 */
const val WIRE_DESCRIPTION_LIMIT: Int = 300

/** 参数内嵌 description 的默认 wire 长度上限。 */
const val WIRE_PARAM_DESCRIPTION_LIMIT: Int = 80

/** 截断一段说明，保留开头语义；超长时截到 [limit] 并追加省略号。 */
fun String.trimDescription(limit: Int): String {
    if (limit <= 0) return ""
    if (this.length <= limit) return this
    return take((limit - 1).coerceAtLeast(0)).trimEnd() + "…"
}

/**
 * 递归裁剪一个 JSON schema 里的所有 `description` 字符串字段。
 * 保留 `required`/`enum`/`type`/`properties`/`items` 等其余字段。
 */
fun JsonElement.trimDescriptionRecursively(limit: Int): JsonElement = when (this) {
    is JsonObject -> buildJsonObject {
        for ((key, value) in this@trimDescriptionRecursively) {
            val truncatedText =
                if (key == "description" && value is JsonPrimitive) value.contentOrNull else null
            put(
                key,
                if (truncatedText != null) {
                    JsonPrimitive(truncatedText.trimDescription(limit))
                } else {
                    value.trimDescriptionRecursively(limit)
                }
            )
        }
    }

    is JsonArray -> JsonArray(map { it.trimDescriptionRecursively(limit) })
    else -> this
}

/**
 * 把 [InputSchema] 裁到 wire 预算。参数描述统一截断到 [paramDescLimit]。
 * 返回 null 表示没有 schema（与入参一致），供 provider 直接 encode。
 */
fun InputSchema?.trimmed(
    paramDescLimit: Int = WIRE_PARAM_DESCRIPTION_LIMIT,
): InputSchema? = when (this) {
    null -> null
    is InputSchema.Obj -> InputSchema.Obj(
        properties = JsonObject(
            this.properties.mapValues { (_, propertySchema) ->
                propertySchema.trimDescriptionRecursively(paramDescLimit)
            }
        ),
        required = this.required,
    )
}
