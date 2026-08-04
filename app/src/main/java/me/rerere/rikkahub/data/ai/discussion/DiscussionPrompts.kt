package me.rerere.rikkahub.data.ai.discussion

import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.DISCUSSION_MODERATOR_ID
import me.rerere.rikkahub.data.model.DiscussionConfig
import me.rerere.rikkahub.data.model.DiscussionMember
import me.rerere.rikkahub.data.model.DiscussionMode
import me.rerere.rikkahub.data.model.MemberStyle
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 群组讨论提示词拼接。
 *
 * token 控制常量：
 * - MAX_TRANSCRIPT_MESSAGES / MAX_TRANSCRIPT_CHARS：每个成员看到的共享 transcript 窗口
 * - MEMBER_MAX_STEPS：单成员单轮的最大工具循环步数（讨论里不追求长 agent 循环）
 * - 单轮输出上限由 subAgentRunLoop 内部的 8000 字符/消息兜底
 */
object DiscussionPrompts {
    const val MAX_TRANSCRIPT_MESSAGES = 24
    const val MAX_TRANSCRIPT_CHARS = 12000
    const val MEMBER_MAX_STEPS = 8

    /**
     * 成员 system prompt：该成员的 persona + 讨论规则。
     * member.systemPrompt 覆盖 Assistant.systemPrompt 时用覆盖值。
     */
    fun memberSystemPrompt(
        member: DiscussionMember,
        assistantSystemPrompt: String,
        config: DiscussionConfig,
        groupName: String? = null,
    ): String {
        val persona = member.systemPrompt ?: assistantSystemPrompt
        val modeText = when (config.mode) {
            DiscussionMode.ROUND_ROBIN -> "轮流发言：按顺序轮到你时发言。"
            DiscussionMode.SELECTOR -> "由主持人决定发言顺序，轮到你时发言。"
            DiscussionMode.ROUND_ROBIN_THEN_SUMMARY -> "轮流发言：按顺序轮到你时发言。全部发言结束后会有一名主持人做总结。"
        }
        val memberNames = config.enabledMembers.joinToString("、") { it.name }
        val styleText = when (member.style) {
            MemberStyle.COMPACT -> "发言尽量精简：只给结论和关键点，不展开论证、不复述、不寒暄。"
            MemberStyle.BALANCED -> "发言保持简洁，直接给观点，不要复述别人的话，不要寒暄。"
            MemberStyle.DETAILED -> "发言可以展开：给出论据与分析过程，最后落到结论，别跑题。"
        }
        return buildString {
            appendLine(persona.trim().ifBlank { "You are a helpful AI assistant." })
            appendLine()
            appendLine("## 群组讨论规则")
            appendLine("- 你的名字是「${member.name}」，正在和其他成员一起讨论。")
            if (!groupName.isNullOrBlank()) {
                appendLine("- 你身处一个群组讨论「$groupName」中，当前共有 ${config.enabledMembers.size} 位成员参与：$memberNames。")
            }
            appendLine("- $modeText")
            appendLine("- 先看清前面谁说了什么，再回应。可以引用 @某位成员 的名字来表达针对谁的发言。")
            appendLine("- $styleText")
            appendLine("- 你有权调用工具（搜索/知识库等）来支撑观点，但调用工具的最终目的仍是给出你的发言。")
            appendLine("- 本轮发言是你个人的独立意见，不要替别人总结。")
        }
    }

    /** 开题用户消息（讨论主题），始终保留在 transcript 最前 */
    fun openingTopicMessage(topic: String): UIMessage = UIMessage.user(topic)

    /**
     * 组装发给单个成员的完整消息序列：system + 开题 + 最近 N 条成员发言。
     * 只保留文本，丢工具 parts，控制 token。
     */
    fun buildMemberMessages(
        member: DiscussionMember,
        assistantSystemPrompt: String,
        config: DiscussionConfig,
        conversation: Conversation,
        groupName: String? = null,
    ): List<UIMessage> {
        val system = UIMessage.system(
            memberSystemPrompt(member, assistantSystemPrompt, config, groupName = groupName)
        )

        val topicIndex = conversation.messageNodes.indexOfFirst { it.role == me.rerere.ai.core.MessageRole.USER }
        val turns = conversation.messageNodes.filterIndexed { index, node ->
            // 开题消息保留在最前；其余取成员发言（带 speakerId 的 ASSISTANT，排除主持人总结）
            // 以及开题之后的用户插话（让成员能看到用户中途提出的补充/质疑）。
            // 主持人总结（DISCUSSION_MODERATOR_ID）不进成员上下文——避免成员复述/参考总结而非继续讨论。
            index == topicIndex ||
                (node.role == me.rerere.ai.core.MessageRole.ASSISTANT &&
                    node.currentMessage.speakerId != null &&
                    node.currentMessage.speakerId != DISCUSSION_MODERATOR_ID) ||
                (node.role == me.rerere.ai.core.MessageRole.USER && index > topicIndex && topicIndex >= 0)
        }

        val result = mutableListOf<UIMessage>()
        if (topicIndex >= 0) {
            result.add(conversation.messageNodes[topicIndex].currentMessage)
        }

        // 最近的发言（跳过开题），倒序取 MAX_TRANSCRIPT_MESSAGES 条再反转
        val recent = turns.filter { it != conversation.messageNodes.getOrNull(topicIndex) }
            .takeLast(MAX_TRANSCRIPT_MESSAGES)
        val transcript = buildString {
            recent.forEachIndexed { i, node ->
                val msg = node.currentMessage
                val name = when {
                    node.role == me.rerere.ai.core.MessageRole.USER -> "用户"
                    else -> msg.speakerName ?: "成员${i + 1}"
                }
                val text = msg.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n") { it.text }
                    .take(MAX_TRANSCRIPT_CHARS / (recent.size.coerceAtLeast(1)))
                if (text.isNotBlank()) appendLine("「$name」: $text")
            }
        }
        if (transcript.isNotBlank()) {
            result.add(
                UIMessage.user(
                    buildString {
                        appendLine("以下是到目前为止的讨论记录（按发言顺序）：")
                        append(transcript)
                    }
                )
            )
        }
        return listOf(system) + result
    }

    /** SELECTOR 模式：主持人 prompt，要求返回 JSON */
    fun selectorSystemPrompt(config: DiscussionConfig): String {
        val memberList = config.enabledMembers.joinToString("、") { "${it.name}(${it.assistantId})" }
        return """
            You are the discussion moderator. Your job is to decide who speaks next in a multi-agent discussion.
            Members: $memberList

            Read the discussion history and decide the next speaker, or end the discussion.
            Reply with ONLY a JSON object, no other text:
            {"speaker": "<member name or id>", "action": "next" | "end"}

            Rules:
            - If the discussion has reached a natural conclusion, return "end".
            - Otherwise pick the member whose response would add the most value next.
            - "speaker" must be exactly one of the member names (or ids) listed above.
        """.trimIndent()
    }

    /** 收束模式：主持人总结 prompt */
    fun summarySystemPrompt(config: DiscussionConfig): String {
        return """
            You are the discussion synthesizer. Members have just finished discussing a topic.
            ${config.summaryPrompt?.let { "Extra instruction from the user: $it" } ?: ""}
            Produce a concise, well-structured summary of the discussion: key points each member made,
            points of agreement and disagreement, and a final conclusion. Use markdown.
        """.trimIndent()
    }
}
