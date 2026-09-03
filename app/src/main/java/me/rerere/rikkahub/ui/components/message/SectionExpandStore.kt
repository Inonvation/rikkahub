package me.rerere.rikkahub.ui.components.message

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 当前 Chat 会话 id。由 ChatPage/ChatList 向上提升，供各 UI 区块按会话隔离展开折叠状态。
 * 无会话上下文（如预览导出）时为 null，组件走默认行为、不记录。
 */
val LocalConversationId = staticCompositionLocalOf<String?> { null }

/**
 * 会话内各 UI 区块（思维链卡片、思考链"显示 N 步"容器、todolist、思考卡）展开折叠状态的
 * 进程级存储。
 *
 * key 由会话 id + 条目标识拼出（见各组件），实现"单会话、逐条独立"：
 * - 切换窗口/页面后仍保持用户手动展开/折叠的状态（Navigation 3 对非栈顶 entry 组合重建，
 *   remember/rememberSaveable 恢复不可靠，故存进程级单例，与 toolBubbleExpanded 同款方案）；
 * - 各 key 彼此独立、互不联动（不会出现"点开一条其他全展开"）；
 * - 写入时机：用户手动 toggle（onExpandedChange / 点卡），外加"loading 由 true 翻转为
 *   false 的完成定稿"——思考卡把系统自动折叠/保留的最终形态落库（`reasoning:` 前缀，
 *   见 ChatMessageReasoningStep effect）；过程区整体折叠（`process:` 前缀）完成定稿
 *   时守卫放行折叠即写 false、过程区仍可见则"临时固化"展开写 true（防切走切回/重建
 *   塌缩，见 ChatMessage effect），临时固化随消息离开组合（滚出回收 / 切走销毁）由
 *   DisposableEffect 落 false 到期——自动折叠只对"已离开用户视野的消息"生效，可见区
 *   形态恒等于用户所见。统一保证消息回收重建后形态确定、无可见塌缩。
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

/**
 * 进程级"最近访问会话"记录，跨 ChatPage 导航实例共享。
 *
 * 不要放进某个 Composable 的 `remember`：切换会话走导航栈（旧 ChatPage 被
 * `cleanupChatPages` 清栈销毁），实例级队列在每次切换后只剩当前会话一个元素，
 * pruneSectionExpanded 会趁机把其它会话的折叠记忆全部清掉——"切走再切回，
 * 思考/过程区折叠态重置"的根因。进程级单例与 sectionExpanded 同生命周期，
 * 才能跨导航实例累积出真正的"最近 N 个会话"。
 */
internal val recentConversationIds = ArrayDeque<String>()

/**
 * 把 [conversationId] 记为最近访问并执行展开折叠记忆的生命周期治理：
 * 进程级最近记录只保留最近 [keepRecentCount] 个会话（含本次访问），其余会话的
 * section 记忆（process:/chain:/reasoning:/todo:）与工具气泡会话维度记录
 * （tool: 前缀，见 pruneToolBubbleExpanded）被回收；顺带对齐
 * toolBubbleExpanded 的容量上限。返回本次回收的 section 记录数。
 * 滚动位置（ChatScrollStore）不在此文件管辖，调用方（ChatPage）按
 * [recentConversationIds] 自行对齐回收（见 ChatScrollStore.prune）。
 */
fun trackRecentConversation(conversationId: String, keepRecentCount: Int): Int {
    recentConversationIds.remove(conversationId)
    recentConversationIds.addFirst(conversationId)
    while (recentConversationIds.size > keepRecentCount) {
        recentConversationIds.removeLast()
    }
    val removed = pruneSectionExpanded(recentConversationIds.toSet())
    pruneToolBubbleExpanded(recentConversationIds.toSet())
    trimToolBubbleExpanded()
    return removed
}

/**
 * 当前"最近 N 会话"保留集合的快照（trackRecentConversation 维护）。供 ChatPage
 * 在治理入口对齐 ChatScrollStore 等无法直接进本文件的生命周期（见 ChatPage 治理 effect）。
 */
fun recentConversationIds(): Set<String> = recentConversationIds.toSet()
