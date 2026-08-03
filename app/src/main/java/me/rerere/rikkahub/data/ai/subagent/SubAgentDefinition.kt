package me.rerere.rikkahub.data.ai.subagent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * 子代理注册表定义（代码常量，放 SubAgentCatalog.kt）。
 *
 * 每个子代理 = 职责 system prompt + 工具能力白名单 + 模型/超时/并行偏好。
 * 定义本身可序列化；工具装配逻辑见 [SubAgentToolAssembler]，两者分离，
 * 保证定义能存进 DataStore（未来用户自定义时）而装配逻辑不随之序列化。
 */
@Serializable
data class SubAgentDefinition(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val capabilities: Set<SubAgentCapability> = emptySet(),
    /** 短指令别名（如 "plan"→/plan），用户手动派发子代理用。null 时仅支持 /<id> 全称指令。 */
    val commandAlias: String? = null,
    val defaultModelId: Uuid? = null,
    val maxSteps: Int = 16,
    val timeoutSeconds: Long = 300,
    val allowParallel: Boolean = false,
)

/** 子代理可用的工具能力白名单，由 [SubAgentToolAssembler] 解析成具体 Tool 列表 */
@Serializable
enum class SubAgentCapability {
    /** 纯 LLM，无工具（规划） */
    @SerialName("none")
    NONE,

    /** 联网搜索（复用多选搜索服务商，见 SearchTools） */
    @SerialName("search")
    SEARCH,

    /** 抓取页面正文 */
    @SerialName("scrape")
    SCRAPE,

    /** 调用已配置的 MCP 服务器工具 */
    @SerialName("mcp")
    MCP,

    /** 知识库检索 */
    @SerialName("knowledge_base")
    KNOWLEDGE_BASE,

    /** 文档解析（PDF/Word/PPT/EPUB） */
    @SerialName("document")
    DOCUMENT,

    /** 沙盒 shell / 文件操作（workspace 模块） */
    @SerialName("workspace")
    WORKSPACE,
}
