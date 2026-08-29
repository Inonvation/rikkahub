package me.rerere.rikkahub.ui.hooks

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/**
 * 会话级输入草稿缓存：内存为运行期权威，SharedPreferences 做磁盘兜底。
 *
 * 背景：切换会话/助手时，旧会话的 ChatVM 会被 `cleanupChatPages` 清出导航栈并销毁，
 * 输入框草稿（ChatInputState）随 VM 一并丢失。此 store 在 VM 销毁前保存草稿，
 * 重新进入该会话时由新 ChatVM 恢复。发送成功后输入框被清空并清槽，自然失效。
 *
 * 两个关键语义：
 * - **未发送的新会话共用 [NEW_CHAT_KEY] 一个草稿槽**：未落库的新会话 id 每次「新建对话」
 *   都会重新随机生成，按 id 存草稿会随旧 id 一起失联（“新建对话输入文字 → 切历史会话 →
 *   再新建切回，文字丢失”的根因）。对齐 Claude Code/Codex 的「草稿跟着新对话走」语义：
 *   所有未落库的新会话共享此槽，发送成功后由 ChatVM 清槽。已落库的会话（发送过消息、
 *   或因配置了模式而落库的空会话——它们都在历史列表可达）按各自 id 存取，互不干扰。
 * - **磁盘持久化**：进程被杀 / 应用重启后 onCleared 不会执行，仅靠内存会丢字；
 *   每次保存写穿磁盘，ChatVM 恢复时内存未命中回落磁盘快照（配合启动恢复
 *   lastConversationId，重启后回到未发送的新会话文字仍在）。
 *
 * 线程模型：save/load/remove 只在主线程调用（ChatVM init / onCleared / 防抖收集），
 * 磁盘写入走 SharedPreferences#apply（内存同步生效、异步落盘），无需额外同步。
 */
class ChatDraftStore(private val context: Context) {

    /** 一份输入草稿：文本 + 附件 parts（均为本地文件 URI 引用；发送前文件不会被按会话清理） */
    data class Draft(
        val text: String,
        val parts: List<UIMessagePart> = emptyList(),
    )

    @Serializable
    private data class DiskDraft(
        val text: String = "",
        val parts: List<UIMessagePart> = emptyList(),
        val savedAt: Long = 0,
    )

    private val drafts = mutableMapOf<Uuid, Draft>()

    // 磁盘快照：懒加载一次（进程重启后首次访问读盘），此后与内存写穿同步；
    // 只在内存未命中（重启后首次进入某会话）时被读取
    private val diskDrafts: MutableMap<String, DiskDraft> by lazy {
        val decoded = runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREFS_KEY, null)
                ?.let { JsonInstant.decodeFromString<Map<String, DiskDraft>>(it) }
        }.getOrNull()
        mutableMapOf<String, DiskDraft>().apply { decoded?.let { putAll(it) } }
    }

    /** 保存草稿；文本与附件均为空时视为无草稿并清槽，避免残留空 key */
    fun save(conversationId: Uuid, text: String, parts: List<UIMessagePart> = emptyList()) {
        if (text.isBlank() && parts.isEmpty()) {
            remove(conversationId)
            return
        }
        drafts[conversationId] = Draft(text, parts)
        diskDrafts[conversationId.toString()] = DiskDraft(
            text = text,
            parts = parts,
            savedAt = System.currentTimeMillis(),
        )
        persistDisk()
    }

    fun load(conversationId: Uuid): Draft? {
        drafts[conversationId]?.let { return it }
        // 进程重启后内存为空：回落磁盘快照并回填内存
        return diskDrafts[conversationId.toString()]?.let { disk ->
            Draft(disk.text, disk.parts).also { drafts[conversationId] = it }
        }
    }

    fun remove(conversationId: Uuid) {
        drafts.remove(conversationId)
        if (diskDrafts.remove(conversationId.toString()) != null) persistDisk()
    }

    private fun persistDisk() {
        // 磁盘条目按保存时间淘汰最旧，防止未发送新会话的临时 id 反复新建导致无限累积
        if (diskDrafts.size > MAX_DISK_DRAFTS) {
            diskDrafts.entries
                .sortedBy { it.value.savedAt }
                .take(diskDrafts.size - MAX_DISK_DRAFTS)
                .forEach { diskDrafts.remove(it.key) }
        }
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(PREFS_KEY, JsonInstant.encodeToString(diskDrafts))
            }
        }
    }

    companion object {
        /** 未发送新会话的共享草稿槽（nil UUID）：所有未落库的新会话读写同一个槽 */
        val NEW_CHAT_KEY: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000000")

        private const val PREFS_NAME = "rikkahub.preferences"
        private const val PREFS_KEY = "chat_input_drafts"
        private const val MAX_DISK_DRAFTS = 32
    }
}
