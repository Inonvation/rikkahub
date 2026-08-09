package me.rerere.rikkahub.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.utils.JsonInstant

// ---------------------------------------------------------------------------
// 常量与路径
// ---------------------------------------------------------------------------

const val CONVERSATION_INDEX_FORMAT = "rikkahub-conversations-index"
const val CONVERSATION_ITEM_FORMAT = "rikkahub-conversation"
const val CONVERSATION_SYNC_VERSION = 1

/** 会话同步的云端根（相对 syncRoot）。与现有 db/、upload/ 平级。 */
const val CONVERSATION_SYNC_DIR = "conversations"
const val CONVERSATION_INDEX_PATH = "$CONVERSATION_SYNC_DIR/index.json"
const val CONVERSATION_ITEMS_DIR = "$CONVERSATION_SYNC_DIR/items"

fun conversationItemPath(id: String): String = "$CONVERSATION_ITEMS_DIR/$id.json"

// ---------------------------------------------------------------------------
// 云端模型
// ---------------------------------------------------------------------------

/** 会话级墓碑（删除会话的传播记录）。 */
@Serializable
data class ConversationTombstone(
    val id: String,
    val deletedAt: Long,
)

/** 消息级墓碑（删除消息的传播记录，C2 合并用）。 */
@Serializable
data class MessageTombstone(
    val id: String,
    val deletedAt: Long,
)

/** index.json 中的单个会话条目（快速跳过未变化会话）。 */
@Serializable
data class ConversationIndexEntry(
    val id: String,
    val syncUpdatedAt: Long,
    val title: String,
    val createAt: Long,
    val isPinned: Boolean,
    val messageCount: Int,
    val path: String,
)

/** 全局索引：极小，用于「哪些会话需要同步」的快速判断。index 是提交点。 */
@Serializable
data class ConversationIndex(
    val format: String = CONVERSATION_INDEX_FORMAT,
    val version: Int = CONVERSATION_SYNC_VERSION,
    val updatedAt: Long = 0L,
    val conversations: List<ConversationIndexEntry> = emptyList(),
    val deleted: List<ConversationTombstone> = emptyList(),
) {
    fun entryById(id: String): ConversationIndexEntry? = conversations.firstOrNull { it.id == id }

    fun upsert(entry: ConversationIndexEntry, now: Long): ConversationIndex = copy(
        updatedAt = now,
        conversations = (conversations.filterNot { it.id == entry.id } + entry)
            .sortedByDescending { it.syncUpdatedAt },
    )

    /** 按 tombstone 过滤后剔除已删除会话。 */
    fun activeEntries(): List<ConversationIndexEntry> {
        val deletedIds = deleted.map { it.id }.toSet()
        return conversations.filterNot { it.id in deletedIds }
    }
}

/** 会话文件中的一个节点（对应本地 message_node 行）。 */
@Serializable
data class ConversationNode(
    val id: String,
    val selectIndex: Int,
    val messages: List<UIMessage>,
)

/** 单会话同步文件（items/<id>.json），可独立增量上传。 */
@Serializable
data class ConversationSyncItem(
    val format: String = CONVERSATION_ITEM_FORMAT,
    val version: Int = CONVERSATION_SYNC_VERSION,
    val id: String,
    val syncUpdatedAt: Long,
    val title: String,
    val createAt: Long,
    val isPinned: Boolean,
    val assistantId: String,
    val customSystemPrompt: String?,
    val modeInjectionIds: Set<String>,
    val lorebookIds: Set<String>,
    val workspaceCwd: String?,
    val folderId: String?,
    val groupId: String?,
    val nodes: List<ConversationNode>,
    /** 本会话内已删除消息（墓碑，C2 合并应用）。 */
    val deletedMessageIds: List<MessageTombstone> = emptyList(),
)

// ---------------------------------------------------------------------------
// 序列化 / 校验
// ---------------------------------------------------------------------------

/** 编码 index 为上传字节。 */
internal fun encodeConversationIndex(index: ConversationIndex): ByteArray =
    JsonInstant.encodeToString(index).toByteArray()

/** 解析 index；format/version 不匹配（未知格式/未来版本）返回 null，调用方跳过，绝不覆盖云端。 */
internal fun decodeConversationIndex(bytes: ByteArray): ConversationIndex? = runCatching {
    val index = JsonInstant.decodeFromString<ConversationIndex>(bytes.decodeToString())
    index.takeIf { it.format == CONVERSATION_INDEX_FORMAT && it.version == CONVERSATION_SYNC_VERSION }
}.getOrNull()

/** 编码会话条目为上传字节。 */
internal fun encodeConversationItem(item: ConversationSyncItem): ByteArray =
    JsonInstant.encodeToString(item).toByteArray()

/** 解析会话条目；format/version 不匹配返回 null。 */
internal fun decodeConversationItem(bytes: ByteArray): ConversationSyncItem? = runCatching {
    val item = JsonInstant.decodeFromString<ConversationSyncItem>(bytes.decodeToString())
    item.takeIf { it.format == CONVERSATION_ITEM_FORMAT && it.version == CONVERSATION_SYNC_VERSION }
}.getOrNull()

// ---------------------------------------------------------------------------
// 导出构建
// ---------------------------------------------------------------------------

/**
 * 由本地会话构建可上传的同步条目。
 * 消息的本地附件引用（file:// 绝对路径）会被改写为云端相对引用 upload/<name>；
 * 不在 upload 通道内的本地文件引用会被移除（避免把设备路径带上云端）；
 * 工具入参 / metadata / workspaceCwd 中的设备路径一并脱敏。
 *
 * @param filesRoot 本机 files 目录绝对路径（用于识别/脱敏设备路径；为空则跳过脱敏，测试用）
 */
internal fun buildConversationItem(
    conversation: Conversation,
    deletedMessageIds: List<MessageTombstone> = emptyList(),
    filesRoot: String = "",
): ConversationSyncItem {
    return ConversationSyncItem(
        id = conversation.id.toString(),
        syncUpdatedAt = conversation.syncUpdatedAt,
        title = conversation.title,
        createAt = conversation.createAt.toEpochMilli(),
        isPinned = conversation.isPinned,
        assistantId = conversation.assistantId.toString(),
        customSystemPrompt = conversation.customSystemPrompt,
        modeInjectionIds = conversation.modeInjectionIds.map { it.toString() }.toSet(),
        lorebookIds = conversation.lorebookIds.map { it.toString() }.toSet(),
        // workspaceCwd 是本机工作区绝对路径，跨设备无意义且含设备路径，不导出
        workspaceCwd = null,
        folderId = conversation.folderId?.toString(),
        groupId = conversation.groupId?.toString(),
        nodes = conversation.messageNodes.map { node ->
            ConversationNode(
                id = node.id.toString(),
                selectIndex = node.selectIndex,
                messages = rewriteUrlsForExport(node.messages, filesRoot),
            )
        },
        deletedMessageIds = deletedMessageIds,
    )
}

// ---------------------------------------------------------------------------
// 附件引用映射（导出方向：本地路径 -> 云端相对引用）
// ---------------------------------------------------------------------------

/**
 * 把本地 file:// 附件引用改写为云端相对引用 upload/<name>。
 * @return 改写后的引用；null 表示该引用不在同步通道内（非 upload 目录的本地文件），导出时应移除该 part。
 */
internal fun toRemoteUrl(url: String): String? {
    return when {
        url.startsWith("file://") -> {
            val path = url.removePrefix("file://").replace('\\', '/')
            val marker = "/upload/"
            val idx = path.lastIndexOf(marker)
            if (idx >= 0) {
                val name = path.substring(idx + marker.length).substringBefore('?').substringBefore('#')
                // 拒绝含 ../ 或路径分隔的名称，避免越出 upload 目录的引用
                if (name.contains("..") || name.contains("/")) null else "upload/$name"
            } else {
                null
            }
        }

        url.startsWith("http://") || url.startsWith("https://") ||
            url.startsWith("data:") || url.startsWith("content:") -> url

        else -> url
    }
}

/** 导出方向：递归改写一条消息的附件引用与设备路径；不可同步的引用移除对应 part。 */
internal fun mapPartsForExport(parts: List<UIMessagePart>, filesRoot: String): List<UIMessagePart> {
    return parts.mapNotNull { part ->
        when (part) {
            is UIMessagePart.Image -> toRemoteUrl(part.url)?.let {
                part.copy(url = it, metadata = redactMeta(part.metadata, filesRoot))
            }

            is UIMessagePart.Video -> toRemoteUrl(part.url)?.let {
                part.copy(url = it, metadata = redactMeta(part.metadata, filesRoot))
            }

            is UIMessagePart.Audio -> toRemoteUrl(part.url)?.let {
                part.copy(url = it, metadata = redactMeta(part.metadata, filesRoot))
            }

            is UIMessagePart.Document -> toRemoteUrl(part.url)?.let {
                part.copy(url = it, metadata = redactMeta(part.metadata, filesRoot))
            }

            is UIMessagePart.Tool -> part.copy(
                // 工具入参是 JSON 字符串，可能含本地文件绝对路径，直接脱敏
                input = redactDevicePaths(part.input, filesRoot),
                output = mapPartsForExport(part.output, filesRoot),
                metadata = redactMeta(part.metadata, filesRoot),
            )

            is UIMessagePart.ServerTool -> part.copy(
                input = part.input?.let { redactJsonElement(it, filesRoot) },
                output = part.output?.let { redactJsonElement(it, filesRoot) },
                metadata = redactMeta(part.metadata, filesRoot),
            )

            is UIMessagePart.Search -> part // deprecated data object，无 copy，metadata 原样保留

            is UIMessagePart.Text -> part.copy(metadata = redactMeta(part.metadata, filesRoot))
            is UIMessagePart.Reasoning -> part.copy(metadata = redactMeta(part.metadata, filesRoot))
            is UIMessagePart.ToolCall -> part.copy(metadata = redactMeta(part.metadata, filesRoot))
            is UIMessagePart.ToolResult -> part.copy(metadata = redactMeta(part.metadata, filesRoot))
        }
    }
}

/** 导出方向：改写消息列表的附件引用并脱敏设备路径。 */
internal fun rewriteUrlsForExport(messages: List<UIMessage>, filesRoot: String): List<UIMessage> =
    messages.map { message -> message.copy(parts = mapPartsForExport(message.parts, filesRoot)) }

// ---------------------------------------------------------------------------
// 附件引用映射（下载方向：云端相对引用 -> 本地 file:// 路径）
// ---------------------------------------------------------------------------

/**
 * 下载方向：把云端相对引用 `upload/<name>` 改写为本地 `file://<uploadDirPath>/<name>`，
 * 使合并进本地 DB 后 UI 能按本地路径读取附件。附件内容本身由 includeChatFiles 通道同步。
 * 其它引用（http/data 等）原样保留。
 */
internal fun mapRemoteUrlsToLocal(parts: List<UIMessagePart>, uploadDirPath: String): List<UIMessagePart> {
    return parts.map { part ->
        when (part) {
            is UIMessagePart.Image -> part.copy(url = mapRemoteUrl(part.url, uploadDirPath))
            is UIMessagePart.Video -> part.copy(url = mapRemoteUrl(part.url, uploadDirPath))
            is UIMessagePart.Audio -> part.copy(url = mapRemoteUrl(part.url, uploadDirPath))
            is UIMessagePart.Document -> part.copy(url = mapRemoteUrl(part.url, uploadDirPath))
            is UIMessagePart.Tool -> part.copy(output = mapRemoteUrlsToLocal(part.output, uploadDirPath))
            else -> part
        }
    }
}

/** `upload/<name>` -> `file://<uploadDirPath>/<name>`；其它原样返回。含 `../` 的引用拒绝映射（防路径遍历）。 */
internal fun mapRemoteUrl(url: String, uploadDirPath: String): String {
    return if (url.startsWith("upload/")) {
        val name = url.removePrefix("upload/")
        if (name.contains("..")) url else "file://${uploadDirPath.trimEnd('/')}/$name"
    } else {
        url
    }
}

// ---------------------------------------------------------------------------
// 设备路径脱敏（导出方向）
// ---------------------------------------------------------------------------

/**
 * 把文本中出现的设备绝对路径改写/移除：
 * - upload 目录内的路径（含 file:// 前缀）→ `upload/<name>`（保留可用引用）
 * - files 目录下其它路径（workspaces 等）→ 移除（避免泄露设备目录结构/包名）
 */
internal fun redactDevicePaths(text: String, filesRoot: String): String {
    if (filesRoot.isBlank()) return text
    val uploadDir = "$filesRoot/upload"
    var t = text
    // 1) file:// + upload 绝对路径 → upload/<name>
    t = t.replace("file://$uploadDir/", "upload/")
    // 2) upload 绝对路径 → upload/<name>
    t = t.replace("$uploadDir/", "upload/")
    // 3) file:// + files 下其它路径 → 移除
    t = t.replace(Regex(Regex.escape("file://$filesRoot") + "/[^\\s\\\"',)\\]}]+"), "")
    // 4) files 下其它绝对路径 → 移除
    t = t.replace(Regex(Regex.escape(filesRoot) + "/[^\\s\\\"',)\\]}]+"), "")
    return t
}

/** 递归脱敏 JSON 结构里的字符串值。 */
private fun redactJsonElement(element: JsonElement, filesRoot: String): JsonElement {
    if (filesRoot.isBlank()) return element
    return when (element) {
        is JsonPrimitive -> if (element.isString) JsonPrimitive(redactDevicePaths(element.content, filesRoot)) else element
        is JsonObject -> JsonObject(element.mapValues { (_, v) -> redactJsonElement(v, filesRoot) })
        is JsonArray -> JsonArray(element.map { redactJsonElement(it, filesRoot) })
        JsonNull -> element
    }
}

private fun redactMeta(meta: JsonObject?, filesRoot: String): JsonObject? =
    if (meta == null || filesRoot.isBlank()) meta else redactJsonElement(meta, filesRoot).jsonObject
