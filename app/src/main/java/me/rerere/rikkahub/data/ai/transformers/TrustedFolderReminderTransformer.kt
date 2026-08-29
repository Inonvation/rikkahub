package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.trustedfolders.TrustedFolderRepository

/**
 * 信任文件夹系统提示注入转换器
 *
 * 当【当前助手绑定了信任文件夹项目】时，在系统提示中追加一段引导，把【绑定的项目名】暴露给模型，
 * 让用户能在指令里直接说项目名（如「把XX笔记改了」「在YY里新建」）触发 trusted_folder_* 工具。
 *
 * 只提当前绑定项目：AI 工具本就只操作绑定项目，列出所有项目会诱导模型去碰非绑定的、造成误操作。
 * 未绑定（或项目已删除）时直接返回原消息（此时也没有 trusted_folder 工具）。
 */
class TrustedFolderReminderTransformer(
    private val repository: TrustedFolderRepository,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val settings = repository.currentSettings()
        val bound = ctx.assistant.trustedFolderProjectId
            ?.let { id -> settings.projects.find { it.id == id } }
            ?: return messages
        val prompt = buildTrustedFolderPrompt(bound.name)

        // 追加到第一条 system 消息；若不存在则插入一条（与 WorkspaceReminderTransformer 一致）
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex].appendText("\n\n$prompt")
            }
        } else {
            listOf(UIMessage.system(prompt)) + messages
        }
    }
}

private fun buildTrustedFolderPrompt(boundProjectName: String): String = buildString {
    appendLine("<trusted_folder>")
    appendLine("Real folders on device (SAF, e.g. Obsidian vaults). Bound project: \"$boundProjectName\".")
    appendLine("Paths are relative to the bound project root; use trusted_folder_* tools.")
    appendLine("When the user names the bound project, operate on that trusted folder.")
    appendLine("trusted_folder_* = real files; workspace_* = Linux sandbox. Keep them separate.")
    append("</trusted_folder>")
}
