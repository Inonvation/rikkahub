package me.rerere.rikkahub.ui.components.message

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 当前 Chat 会话 id。由 ChatPage 向上提升，供思考链等组件按会话记忆用户意图。
 * 无会话上下文（如预览导出）时为 null，组件走默认行为。
 */
val LocalConversationId = staticCompositionLocalOf<String?> { null }

/**
 * 每个会话"思考链展开/收起"的用户意图（进程级，按 conversationId 隔离）。
 *
 * 只记录用户的明确操作，不记录折叠/展开的绝对布尔：
 * 这样不会和"生成中强制展开预览、完成后自动折叠"的默认行为打架——
 * 用户没动过 = Default，完全走原有逻辑；动过了就按用户偏好来。
 */
enum class ChainOfThoughtIntent {
    /** 用户明确展开过：链内展开且不自动折叠（除非用户手动收起） */
    ExpandAll,

    /** 用户明确收起过：链内全部收起 */
    CollapseAll,

    /** 未表达意图：走原有默认逻辑（生成中预览/完成后自动折叠） */
    Default,
}

/**
 * 进程级存储。导航返回等场景下 Chat 重新组合，remember/rememberSaveable 都不可靠
 * （Navigation 3 对非栈顶 entry 的组合槽位重建），故沿用 toolBubbleExpanded 同款方案：
 * 存进程级单例，App 进程存活期间任何导航路径都能保持用户意图。
 */
internal val chainOfThoughtIntent = mutableStateMapOf<String, ChainOfThoughtIntent>()

internal fun setChainOfThoughtIntent(conversationId: String, intent: ChainOfThoughtIntent) {
    chainOfThoughtIntent[conversationId] = intent
}

internal fun getChainOfThoughtIntent(conversationId: String?): ChainOfThoughtIntent =
    conversationId?.let { chainOfThoughtIntent[it] } ?: ChainOfThoughtIntent.Default
