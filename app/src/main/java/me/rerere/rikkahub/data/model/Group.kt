package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.util.InstantSerializer
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * 群组（AI 群组讨论的载体）。
 *
 * 一个群组拥有：群名 + 成员/模式/轮数等 [DiscussionConfig]（一处编辑，群下所有会话生效）。
 * 群下的每场对话是带 [Conversation.groupId] 的普通 Conversation 记录。
 */
@Serializable
data class Group(
    val id: Uuid,
    val name: String = "",
    val config: DiscussionConfig? = null,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
)
