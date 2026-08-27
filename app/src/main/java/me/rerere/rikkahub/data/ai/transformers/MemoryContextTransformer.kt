package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.buildMemoryContextBlock

/**
 * 情景记忆尾部注入转换器。
 *
 * 记忆随每轮检索变化，拼进 system 会打穿其后工具说明/行为提示词/全部历史消息的
 * 前缀缓存；改为追加到最后一条 USER 消息内部 —— 该消息本来就是本轮新增，缓存代价最小，
 * 且不改变 provider 看到的角色交替（规避 Anthropic 类严格轮替限制）。
 *
 * 必须注册在管线最末尾：晚于 TemplateTransformer，避免记忆文本被消息模板二次渲染。
 * 末尾不是 USER 消息时（如续答唤醒流）本轮跳过注入。
 */
object MemoryContextTransformer : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val block = buildMemoryContextBlock(ctx.retrievedMemories)
        return appendMemoryContext(messages, block)
    }
}

/** 将已渲染好的记忆块追加到最后一条消息（仅当其为 USER 消息）；否则原样返回。 */
internal fun appendMemoryContext(
    messages: List<UIMessage>,
    block: String,
): List<UIMessage> {
    if (block.isEmpty()) return messages
    val last = messages.lastOrNull() ?: return messages
    // 只注入于绝对末尾的 USER 消息：既保证记忆总在上下文尾部，
    // 也让续答唤醒流（尾消息为 ASSISTANT）自然跳过，避免改写历史消息破坏缓存前缀
    if (last.role != MessageRole.USER) return messages
    return messages.dropLast(1) + last.appendText("\n\n$block")
}
