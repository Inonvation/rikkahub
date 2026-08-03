package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.util.InstantSerializer
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 群组讨论配置。
 *
 * 挂在 [Conversation.discussion] 上，null 表示普通会话。
 * 群聊会话的 assistantId 固定为哨兵 [GROUP_DISCUSSION_ASSISTANT_ID]，避免混入普通助手列表。
 */
@Serializable
enum class DiscussionMode {
    /** 轮流发言：按成员顺序轮流，聊满 rounds 轮后终止 */
    @SerialName("round_robin")
    ROUND_ROBIN,

    /** AI 主持人调度：selector 模型观察讨论进度，动态决定下一位发言者 */
    @SerialName("selector")
    SELECTOR,

    /** 轮流发言 + 主持人收束：平时轮流，轮数结束后由主持人总结 */
    @SerialName("round_robin_then_summary")
    ROUND_ROBIN_THEN_SUMMARY,
}

/** 群组讨论会话使用的哨兵 assistantId（固定 Uuid，不在 settings.assistants 里） */
val GROUP_DISCUSSION_ASSISTANT_ID: Uuid =
    Uuid.parse("00000000-0000-0000-0000-0000000000dd")

@Serializable
data class DiscussionConfig(
    /** 成员列表，按 order 排序即发言顺序 */
    val members: List<DiscussionMember>,
    val mode: DiscussionMode,
    /** 每名成员发言轮数上限（RoundRobin 用） */
    val rounds: Int = 3,
    /** 全局硬上限，防死循环 */
    val maxTurns: Int = 60,
    /** 收束主持人自定义指令 */
    val summaryPrompt: String? = null,
    @Serializable(with = InstantSerializer::class)
    val createdAt: Instant = Instant.now(),
) {
    val enabledMembers: List<DiscussionMember>
        get() = members.filter { it.enabled }.sortedBy { it.order }

    val isDiscussion: Boolean get() = true
}

/** 成员发言风格（三档，注入 system prompt 控制篇幅与展开度） */
@Serializable
enum class MemberStyle {
    /** 精简：只给结论和关键点 */
    @SerialName("compact")
    COMPACT,

    /** 标准：简洁观点、不寒暄 */
    @SerialName("balanced")
    BALANCED,

    /** 详细：展开论证与分析 */
    @SerialName("detailed")
    DETAILED,
}

@Serializable
data class DiscussionMember(
    /** 引用 settings.assistants 里的 Assistant.id */
    val assistantId: Uuid,
    /** 创建时的名称快照（防改名/删除后无法显示） */
    val name: String,
    /** 创建时的头像快照 */
    val avatar: Avatar = Avatar.Dummy,
    /** 发言顺序（RoundRobin 用） */
    val order: Int = 0,
    /** 是否仍在讨论中 */
    val enabled: Boolean = true,
    /** 发言风格（默认标准） */
    val style: MemberStyle = MemberStyle.BALANCED,
    // per-member 覆盖（null = 用 Assistant 原配置）
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val systemPrompt: String? = null,
)
