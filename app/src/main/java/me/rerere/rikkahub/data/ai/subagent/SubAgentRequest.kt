package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/** 派发请求：由母代理的 spawn_subagent 工具参数解析而来 */
@Serializable
data class SubAgentRequest(
    val agentId: String,
    val task: String,
    /** 任务级模型覆盖（可选）。string 而非 Uuid：模型可能传任意字符串（"default"/"auto"/
     *  具体模型 id 字符串），严格按 Uuid 解析会把"模型随手传的合法字符串"当错误拒绝（如 GPT
     *  传 "default" → Uuid.parse 失败 → 整个请求 Invalid subagent request）。改为 String 后在
     *  resolveModel 里宽松解析：解析成 Uuid 则用之，否则当未指定（回落默认模型）。 */
    val modelId: String? = null,
    /** 上次执行的部分结果（详情页"重新执行"续跑用）。仅内存传递，不落库。 */
    val priorContext: String? = null,
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
