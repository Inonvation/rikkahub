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
