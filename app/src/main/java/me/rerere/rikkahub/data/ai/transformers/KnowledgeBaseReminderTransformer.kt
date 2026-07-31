package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * 知识库系统提示注入转换器
 *
 * 当助手绑定了知识库时, 在系统提示词中追加一段引导,
 * 让模型知道: 知识库是用户文档的权威来源, 优先用 kb_search,
 * 不要为了核实而去 workspace 翻原始文件。
 */
class KnowledgeBaseReminderTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val kbIds = ctx.assistant.knowledgeBaseIds
        if (kbIds.isEmpty()) return messages

        val prompt = buildKnowledgeBasePrompt()

        // 追加到第一条 system 消息; 若不存在则插入一条
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendText("\n\n$prompt")
            }
        } else {
            listOf(UIMessage.system(prompt)) + messages
        }
    }

    private fun buildKnowledgeBasePrompt(): String = """
        <knowledge_base>
        The user has uploaded documents into knowledge bases. Use the `kb_search` tool whenever a question could be answered from those documents.
        Rules:
        - Call `kb_search` first for questions about the user's documents, notes, or uploaded files.
        - Answer from the retrieved chunks. Do NOT call `kb_search` more than once for the same question.
        - If no relevant information is found, say "I couldn't find this in your knowledge base" and stop.
        </knowledge_base>
    """.trimIndent()
}
