package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import org.koin.core.component.KoinComponent

object RegexOutputTransformer : OutputMessageTransformer, KoinComponent {
    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val assistant = ctx.assistant
        if (assistant.regexes.isEmpty()) return messages // No regexes, return original messages
        // 流式每 chunk 都执行本方法：replaceRegexes 在正则无匹配时返回原字符串引用，
        // 仅在实际发生替换的消息上重建，其余保持引用，避免整列表 copy 导致下游重组
        return messages.map { message ->
            val scope = when (message.role) {
                MessageRole.ASSISTANT -> AssistantAffectScope.ASSISTANT
                else -> return@map message // Skip non-assistant messages
            }
            var changed = false
            val newParts = message.parts.map { part ->
                when (part) {
                    is UIMessagePart.Text -> {
                        val newText = part.text.replaceRegexes(assistant, scope, visual = false)
                        if (newText !== part.text) {
                            changed = true
                            part.copy(text = newText)
                        } else {
                            part
                        }
                    }

                    is UIMessagePart.Reasoning -> {
                        val newReasoning = part.reasoning.replaceRegexes(assistant, scope, visual = false)
                        if (newReasoning !== part.reasoning) {
                            changed = true
                            part.copy(reasoning = newReasoning)
                        } else {
                            part
                        }
                    }

                    else -> part
                }
            }
            if (changed) message.copy(parts = newParts) else message
        }
    }
}
