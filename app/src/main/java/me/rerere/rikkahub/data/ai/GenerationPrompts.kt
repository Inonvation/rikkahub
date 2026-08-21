package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    val revision: String get() = PROMPT_REVISION
}

internal fun buildMemoryPrompt(memories: List<AssistantMemory>) =
    buildString {
        appendLine()
        append("**Memories**")
        appendLine()
        append("Memories from past conversations. Reference them when relevant.")
        appendLine()
        val json = buildJsonArray {
            memories.forEach { memory ->
                add(buildJsonObject {
                    put("id", memory.id)
                    put("content", memory.content)
                })
            }
        }
        append(JsonInstantPretty.encodeToString(json))
        appendLine()
    }
