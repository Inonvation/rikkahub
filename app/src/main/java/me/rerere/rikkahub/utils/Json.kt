package me.rerere.rikkahub.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

val JsonInstant by lazy {
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // 对带默认值的枚举/数值字段，输入值越界时自动取默认值（而非抛异常），
        // 作为 P0 容错的第二道防线，降低「类型不符 → 设置流崩溃」的概率。
        coerceInputValues = true
    }
}

val JsonInstantPretty by lazy {
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
}

val JsonElement.jsonPrimitiveOrNull: JsonPrimitive?
    get() = this as? JsonPrimitive
