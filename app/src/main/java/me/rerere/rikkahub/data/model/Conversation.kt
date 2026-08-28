package me.rerere.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import java.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class Conversation(
    // 内存态版本号，置于构造器首位：让 MutableStateFlow 的 equals 先比较 version O(1) 短路，
    // 避免流式每 chunk 在主线程深比较 messageNodes→parts→全量文本字符串（长对话掉帧源）。
    // @Transient 不进 JSON/DB，重启归 0；仅内存中随每次 updateConversation 递增。
    @Transient
    val version: Long = 0L,
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    /** 会话增量同步的版本号（单调时钟）。由仓库保存时维护，内存态仅供映射与比对外部传入值。 */
    val syncUpdatedAt: Long = 0L,
    val customSystemPrompt: String? = null,
    /** 会话能力模式快照：内置模式存枚举名，自定义模式存 `custom:<id>`；null = 生成期按助手/全局解析 */
    val mode: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    // Absolute path inside the workspace rootfs
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    // 群组讨论配置，null = 普通会话。群聊会话的 assistantId 为 GROUP_DISCUSSION_ASSISTANT_ID
    val discussion: DiscussionConfig? = null,
    // 所属 AI 群组（多会话）。null = 非群组会话。
    // 群组会话的配置在 Group.config，discussion 字段已废弃（迁移后恒为 null）
    val groupId: Uuid? = null,
    /** 压缩后的有效上下文快照；UI 仍显示完整 messageNodes，AI 请求优先使用 effectiveMessages() */
    val compressedHistory: CompressedHistory? = null,
    @Transient
    val newConversation: Boolean = false
) {
    /** 是否为群组讨论会话（当前仅按 groupId 判定） */
    val isGroupDiscussion: Boolean get() = groupId != null
    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .collectAllParts()
            .mapNotNull { it.fileUri() }

    /**
     *  当前选中的 message
     *  用 mapNotNull 跳过 selectIndex 越界的异常节点（数据损坏时避免整屏崩溃，
     *  与 MessageNode.currentMessage 的防御性兜底一致）。
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.mapNotNull { node ->
                if (node.selectIndex in node.messages.indices) {
                    node.messages[node.selectIndex]
                } else {
                    null
                }
            }
        }

    /** AI 请求使用的消息：有压缩快照时用“摘要 + 保留消息 + 压缩后新增消息”，否则用完整历史。 */
    fun effectiveMessages(): List<UIMessage> {
        val compressed = compressedHistory ?: return currentMessages
        val result = compressed.messages.toMutableList()
        val lastOriginalMessageId = compressed.lastOriginalMessageId
        if (lastOriginalMessageId != null) {
            val index = currentMessages.indexOfFirst { it.id == lastOriginalMessageId }
            if (index >= 0) {
                result += currentMessages.drop(index + 1)
            }
        }
        return result
    }

    fun getMessageNodeByMessage(message: UIMessage): MessageNode? {
        // 用消息 id 定位而非 equals：UIMessage 是 data class，syncUpdatedAt 参与相等性，
        // 而内存态消息（保存前）syncUpdatedAt=0、DB 里的有值，跨实例 equals 会失配导致重生成失效。
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == message.id } }
    }

    fun getMessageNodeByMessageId(messageId: Uuid): MessageNode? {
        return messageNodes.firstOrNull { node -> node.messages.any { it.id == messageId } }
    }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        val newNodes = this.messageNodes.toMutableList()

        messages.forEachIndexed { index, message ->
            if (index > newNodes.lastIndex) {
                // 新消息：直接追加新 node（原逻辑等价于 toMessageNode().copy(...)）
                newNodes.add(message.toMessageNode())
                return@forEachIndexed
            }
            val node = newNodes[index]
            val existingIndex = node.messages.indexOfFirst { it.id == message.id }
            // 流式输出每 chunk 都带全量消息：目标消息与现有引用相同且选中未变时复用原 node，
            // 否则每次 chunk 都重建整列表，LazyColumn 中所有可见 item 的 node 引用变化而全部重组，
            // 长对话后期可见消息多时即掉帧主因。内容实际变化的最后一条消息会走下方正常更新路径。
            if (existingIndex >= 0 && node.messages[existingIndex] === message && node.selectIndex == existingIndex) {
                return@forEachIndexed
            }

            val newMessages = node.messages.toMutableList()
            var newMessageIndex = node.selectIndex
            if (existingIndex >= 0) {
                newMessages[existingIndex] = message
            } else {
                newMessages.add(message)
                newMessageIndex = newMessages.lastIndex
            }

            val newNode = node.copy(
                messages = newMessages,
                selectIndex = newMessageIndex
            )
            newNodes[index] = newNode
        }

        return this.copy(
            messageNodes = newNodes
        )
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

/** 压缩后的上下文快照：摘要消息 + 保留的最近消息，lastOriginalMessageId 标记压缩时的最后一条原消息。 */
@Serializable
data class CompressedHistory(
    val messages: List<UIMessage> = emptyList(),
    val lastOriginalMessageId: Uuid? = null,
    val summaryText: String = "",
)

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException("MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex")
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/**
 * 递归展开所有 parts，包括工具调用结果中的嵌套 parts。
 */
private fun List<UIMessagePart>.collectAllParts(): List<UIMessagePart> =
    this + filterIsInstance<UIMessagePart.Tool>().flatMap { it.output.collectAllParts() }

/**
 * 剔除开头的预设消息段（assistant.presetMessages 注入的开场展示）。
 *
 * 判定与 ChatList 的 presetMessageCount 同款：按消息 id 逐条对齐前缀，任一失配即停止
 * （内容相同但被用户编辑的消息 id 已变，不会被误判）。预设消息只作开场展示，
 * 不应进入发给 provider 的请求与上下文占用统计。
 */
fun List<UIMessage>.dropPresetMessages(presetMessages: List<UIMessage>): List<UIMessage> {
    if (presetMessages.isEmpty()) return this
    val presetCount = presetMessages.indices.takeWhile { index ->
        this.getOrNull(index)?.id == presetMessages[index].id
    }.size
    return drop(presetCount)
}

/**
 * 提取 part 中引用的本地文件 URI，新增文件类型时只需在此处添加。
 */
private fun UIMessagePart.fileUri(): Uri? = when (this) {
    is UIMessagePart.Image -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Document -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Video -> url.takeIf { it.startsWith("file://") }?.toUri()
    is UIMessagePart.Audio -> url.takeIf { it.startsWith("file://") }?.toUri()
    else -> null
}
