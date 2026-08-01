package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.knowledge.KnowledgeManager

/**
 * 知识库系统提示注入转换器
 *
 * 当助手绑定了知识库时，在系统提示中注入知识库列表（含名称和描述），
 * 让 AI 一开始就知道用户的文档里有什么，主动在合适的时机调用 kb_search。
 */
class KnowledgeBaseReminderTransformer(
    private val knowledgeManager: KnowledgeManager,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val kbIds = ctx.assistant.knowledgeBaseIds
        if (kbIds.isEmpty()) return messages

        val prompt = buildKnowledgeBasePrompt(kbIds.map { it.toString() })

        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendText("\n\n$prompt")
            }
        } else {
            listOf(UIMessage.system(prompt)) + messages
        }
    }

    private suspend fun buildKnowledgeBasePrompt(kbIds: List<String>): String {
        val bases = kbIds.mapNotNull { knowledgeManager.baseRepository.getById(it) }

        if (bases.isEmpty()) {
            return """
                <knowledge_base>
                The user has knowledge bases available. Use `kb_list` to discover them, then `kb_search` to search them.
                </knowledge_base>
            """.trimIndent()
        }

        val kbList = bases.joinToString("\n") { base ->
            val desc = if (base.description.isNotBlank()) " — ${base.description}" else ""
            "  - ${base.name}$desc"
        }

        return """
            <knowledge_base>
            You have access to the following knowledge bases containing the user's uploaded documents:

            $kbList

            Rules:
            - For questions related to these knowledge bases, call `kb_search` first before answering.
            - If a question's topic matches a knowledge base's description, search it.
            - Search modes: "scan" for counting/listing, "keyword" for exact terms, "semantic" for concepts, "hybrid" for general questions.
            - Answer from the retrieved chunks. Call `kb_search` once per question.
            - If no relevant information is found, say "I couldn't find this in your knowledge base" and stop.
            - Search the knowledge base, not the workspace or filesystem, for these documents.
            </knowledge_base>
        """.trimIndent()
    }
}