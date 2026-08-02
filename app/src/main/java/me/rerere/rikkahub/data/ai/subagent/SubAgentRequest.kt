package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.uuid.Uuid

/** 派发请求：由母代理的 spawn_subagent 工具参数解析而来 */
@Serializable
data class SubAgentRequest(
    val agentId: String,
    val task: String,
    /** 任务级模型覆盖（可选），优先级最高 */
    val modelId: Uuid? = null,
) {
    companion object {
        // ignoreUnknownKeys: 运行时 args 里可能混入隐藏字段（如 __toolCallId），需容忍未知键
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJsonElement(element: JsonElement): SubAgentRequest? =
            runCatching {
                json.decodeFromJsonElement<SubAgentRequest>(element)
            }.getOrNull()
    }
}
