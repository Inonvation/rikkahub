package me.rerere.rikkahub.utils

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.uuid.Uuid

private const val TAG = "ResilientCodec"

/**
 * 安全解码：把 [raw] 解码为 [T]。
 *
 * - null / 空白：直接返回 [default]（与“键不存在”语义一致）
 * - 语法错误 / 字段类型不符 / 结构校验失败：回退到 [default]，只记录一句**非敏感**告警
 *   （类型名 + 原因），绝不打印原始内容，避免 apiKey / token / OAuth 密钥写入日志。
 *
 * 这是 P0 兜底的核心：保证 `SettingsStore.settingsFlowRaw` 的 `.map` 永不因单个坏字段抛异常，
 * 从而避免「一个坏 provider/MCP/skillOrder 打死整条 settingsFlow → App 崩溃 → SafeMode 又读同一
 * 坏流 → 无限崩溃环」。
 */
inline fun <reified T> decodeOrDefault(raw: String?, default: T): T {
    if (raw == null || raw.isBlank()) return default
    return try {
        JsonInstant.decodeFromString<T>(raw)
    } catch (e: Exception) {
        // 记录一句非敏感告警；包 runCatching 保证日志失败本身也不会让加载崩溃
        // （JVM 单测下 android.util.Log 未 mock 时同理）。
        runCatching {
            Log.w("ResilientCodec", "decode ${T::class.simpleName} failed, fallback to default: ${e.message}")
        }
        default
    }
}

/**
 * 把 [raw] 作为数组逐条解码为 [T]，并**逐条回收**：某一条解码失败（坏 provider/MCP/assistant）
 * 只丢弃该条，其余正常条目继续使用 —— 避免“一个坏项丢掉整表”。
 *
 * - raw 为 null/空白 / 顶层不是数组 / 顶层解析失败：整表回退到 [default]
 * - 数组内某条解码失败：该条被隔离（丢弃），不写回、不落盘；只记非敏感告警
 *
 * 返回的列表已尽可能保留有效条目，供 `SettingsStore` 下游继续合并默认值。
 */
inline fun <reified T> decodeListOrDefault(raw: String?, default: List<T>): List<T> {
    if (raw == null || raw.isBlank()) return default
    val element = try {
        JsonInstant.parseToJsonElement(raw)
    } catch (e: Exception) {
        runCatching { Log.w("ResilientCodec", "decode ${T::class.simpleName} array failed, fallback to default: ${e.message}") }
        return default
    }
    val array = element as? JsonArray ?: return default
    return array.mapNotNull { item ->
        try {
            JsonInstant.decodeFromJsonElement<T>(item)
        } catch (e: Exception) {
            runCatching {
                Log.w("ResilientCodec", "decode ${T::class.simpleName} item failed, item quarantined: ${e.message}")
            }
            null
        }
    }
}

/**
 * 把字符串解析为 [Uuid]；非法输入返回 null。
 *
 * 避免 [Uuid.parse] 对损坏的模型/助手 id 字符串抛异常，进而打崩整个设置加载。
 */
fun parseUuidOrNull(raw: String?): Uuid? {
    if (raw.isNullOrBlank()) return null
    return try {
        Uuid.parse(raw)
    } catch (e: Exception) {
        runCatching { Log.w(TAG, "parse Uuid failed, fallback to null: ${e.message}") }
        null
    }
}
