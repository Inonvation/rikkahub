package me.rerere.rikkahub.ui.components.ai.completion

import androidx.compose.ui.text.TextRange
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.rikkahub.data.ai.subagent.SubAgentCatalog
import kotlin.math.max

/**
 * 斜杠命令补全: 输入 "/" 时列出 /init 与全部子代理命令(/plan /search /doc /code /data)。
 * 选中后替换当前命令词。与 workspace 路径补全(@) 触发条件互斥。
 * 子代理有短别名时补全只显示别名; 全 id 形式(/planner 等) 保留给系统提示词派发, 不进输入补全。
 */
class CommandCompletionProvider : ChatCompletionProvider {
    override val id: String = "slash_commands"

    override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
        if (context.hasSelection) return null
        val text = context.text
        val cursor = context.cursor

        // 取光标前一个"词"的起点: 行首或最近空格后; 以 "/" 开头才触发
        val lineStart = text.lastIndexOf('\n', cursor - 1).let { if (it < 0) 0 else it + 1 }
        val wordStart = text.lastIndexOf(' ', cursor - 1).let { max(lineStart, it + 1) }
        if (wordStart >= cursor) return null
        val word = text.substring(wordStart, cursor)
        if (!word.startsWith("/")) return null
        // 已完整输入某个命令名时不再弹补全(用户准备加参数或回车), 避免选中后又重复提示
        if (COMMANDS.any { it.command == word }) return null
        val query = word.removePrefix("/").lowercase()

        val items = COMMANDS
            .asSequence()
            .filter { it.command.lowercase().contains(query) }
            .map { def ->
                ChatCompletionItem(
                    label = def.command,
                    insertText = def.command,
                    detail = def.detail,
                    icon = HugeIcons.ComputerTerminal01,
                    sortScore = if (def.command.lowercase() == "/$query") 100 else 0,
                )
            }
            .sortedWith(compareByDescending<ChatCompletionItem> { it.sortScore }.thenBy { it.label })
            .take(MAX_COMPLETION_ITEMS)
            .toList()

        if (items.isEmpty()) return null
        return ChatCompletionList(
            providerId = id,
            replacementRange = TextRange(wordStart, cursor),
            items = items,
        )
    }

    private data class CommandDef(val command: String, val detail: String)

    private companion object {
        const val MAX_COMPLETION_ITEMS = 10

        /** 命令列表: /init + 子代理命令(仅短别名, 无别名才用全 id 形式)。有别名时全 id 不进补全 */
        val COMMANDS: List<CommandDef> = buildList {
            add(CommandDef("/init", "初始化当前工作区（生成 .agent 配置与结构索引）"))
            SubAgentCatalog.all.forEach { def ->
                if (def.commandAlias != null) {
                    add(CommandDef("/${def.commandAlias}", "${def.name} — ${def.description.orEmpty()}"))
                } else {
                    add(CommandDef("/${def.id}", def.name))
                }
            }
        }
    }
}
