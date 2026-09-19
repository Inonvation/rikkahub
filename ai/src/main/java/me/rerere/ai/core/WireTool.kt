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

/**
 * 参数内嵌 description 的默认 wire 长度上限。
 *
 * 参数描述没有 systemPrompt 逃生通道（schema 绑定，无法上移），且 enum/required 之外的
 * 语义（默认值、互斥关系、取值约定）只能写在描述里。实测内置工具参数描述集中在 84–207 字符，
 * 80 的上限会把「省略时读默认 SKILL.md」「30 字符纯文本标题」这类可执行约束静默砍掉。
 * 取 160 并在 ToolDescriptionBudgetTest 中固化：超限即测试失败，要求写的时候就控制长度。
 */
const val WIRE_PARAM_DESCRIPTION_LIMIT: Int = 160

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
 * 为 `type: array` 但缺少 `items` 的属性补上一个空 schema（任意元素）。
 *
 * Gemini 服务端校验强制要求 array 必须有 `items`，缺失直接报 `items: missing field`
 * （OpenAI/Anthropic/Claude 容忍缺失，所以症状只在 Gemini 模型出现）。本地工具声明应显式
 * 写全 `items`，这里是针对 MCP server 透传 schema（McpSessionRegistry 原样转发，无法改远端）
 * 与漏写声明的兜底：空 schema 不臆断元素类型，只让请求通过校验。
 */
internal fun JsonElement.fillMissingArrayItems(): JsonElement = when (this) {
    is JsonObject -> buildJsonObject {
        for ((key, value) in this@fillMissingArrayItems) {
            if (key == "type" && value is JsonPrimitive && value.contentOrNull == "array" &&
                this@fillMissingArrayItems["items"] == null
            ) {
                put("items", JsonObject(emptyMap()))
            }
            put(key, value.fillMissingArrayItems())
        }
    }

    is JsonArray -> JsonArray(map { it.fillMissingArrayItems() })
    else -> this
}

/**
 * 把 [InputSchema] 裁到 wire 预算。参数描述统一截断到 [paramDescLimit]，
 * 并兜底补齐 array 缺 `items` 的问题（见 [fillMissingArrayItems]）。
 * 返回 null 表示没有 schema（与入参一致），供 provider 直接 encode。
 */
fun InputSchema?.trimmed(
    paramDescLimit: Int = WIRE_PARAM_DESCRIPTION_LIMIT,
): InputSchema? = when (this) {
    null -> null
    is InputSchema.Obj -> InputSchema.Obj(
        properties = JsonObject(
            this.properties.mapValues { (_, propertySchema) ->
                propertySchema.trimDescriptionRecursively(paramDescLimit).fillMissingArrayItems()
            }
        ),
        required = this.required,
    )
}
