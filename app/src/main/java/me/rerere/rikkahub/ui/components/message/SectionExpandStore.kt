package me.rerere.rikkahub.ui.components.message

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 当前 Chat 会话 id。由 ChatPage/ChatList 向上提升，供各 UI 区块按会话隔离展开折叠状态。
 * 无会话上下文（如预览导出）时为 null，组件走默认行为、不记录。
 */
val LocalConversationId = staticCompositionLocalOf<String?> { null }

/**
 * 会话内各 UI 区块（思维链卡片、思考链"显示 N 步"容器、todolist）展开折叠状态的进程级存储。
 *
 * key 由会话 id + 条目标识拼出（见各组件），实现"单会话、逐条独立"：
 * - 切换窗口/页面后仍保持用户手动展开/折叠的状态（Navigation 3 对非栈顶 entry 组合重建，
 *   remember/rememberSaveable 恢复不可靠，故存进程级单例，与 toolBubbleExpanded 同款方案）；
 * - 各 key 彼此独立、互不联动（不会出现"点开一条其他全展开"）；
 * - 仅记录用户手动操作，生成中自动预览/完成自动折叠不写入。
 * App 进程存活期间有效。
 */
internal val sectionExpanded = mutableStateMapOf<String, Boolean>()

/** 读取某区块记忆的展开折叠状态；无记录时返回 null，由组件走默认逻辑 */
fun getSectionExpanded(key: String): Boolean? = sectionExpanded[key]

/** 记录某区块用户手动设置的展开折叠状态 */
fun setSectionExpanded(key: String, expanded: Boolean) {
    sectionExpanded[key] = expanded
}

/**
 * 生命周期治理：仅保留 [keepConversationIds] 会话的记忆，删除其余会话的全部记录，返回删除条数。
 *
 * 所有 section key 统一为 `<语义前缀>:<conversationId>:<条目>` 两/三段式（chain:/reasoning:/process:/todo:），
 * 会话 id 固定位于第二段，故取第二段作为会话作用域匹配；无冒号的孤立 key（不应出现）
 * 整段视为作用域、不在保留集合即删除。**调用方必须把当前会话 id 放进保留集合**，
 * 否则正在显示的界面记忆会被误清；分批遍历移除发生在快照写中，不会撕裂可见状态。
 */
fun pruneSectionExpanded(keepConversationIds: Set<String>): Int {
    var removed = 0
    for (key in sectionExpanded.keys.toList()) {
        if (key.substringAfter(':').substringBefore(':') !in keepConversationIds) {
            sectionExpanded.remove(key)
            removed++
        }
    }
    return removed
}
