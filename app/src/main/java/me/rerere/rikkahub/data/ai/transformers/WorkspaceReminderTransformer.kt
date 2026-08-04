package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

/** 注入 system prompt 的 AGENTS 内容上限, 防 AI 把文件写大撑爆 context */
private const val MAX_AGENTS_INJECT_CHARS = 4096

/**
 * Workspace 系统提示注入转换器
 *
 * 当助手绑定了一个 shell 已就绪的 workspace 时, 在系统提示词中追加一段引导,
 * 让模型了解 workspace 环境与 workspace_* 工具的使用方式。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString() ?: return messages
        val workspace = workspaceRepository.getById(workspaceId) ?: return messages
        // 与 ChatService.createWorkspaceToolsIfReady 保持一致: 仅在 shell 就绪时注入
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return messages

        // 从第一轮起注入 AGENTS 环境 + MEMORY 索引, 保证 system prompt 前缀稳定; 缺失/空白时先补生成再读(空白自愈)
        var envContent = workspaceRepository.readAgentsFileContent(workspaceId)
        if (envContent == null) {
            runCatching { workspaceRepository.ensureAgentsFile(workspaceId) }
            envContent = workspaceRepository.readAgentsFileContent(workspaceId)
        }
        var memoryContent = workspaceRepository.readMemoryIndex(workspaceId)
        if (memoryContent == null) {
            runCatching { workspaceRepository.ensureMemoryIndex(workspaceId) }
            memoryContent = workspaceRepository.readMemoryIndex(workspaceId)
        }

        val prompt = buildWorkspacePrompt(workspace, ctx.workspaceCwd, envContent, memoryContent)

        // 追加到第一条 system 消息; 若不存在则插入一条
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

private fun buildWorkspacePrompt(
    workspace: WorkspaceEntity,
    cwd: String? = null,
    envContent: String? = null,
    memoryContent: String? = null,
): String = buildString {
    appendLine("<workspace>")
    appendLine("The sections below (<workspace>, <workspace_environment>, <workspace_memory>) are injected by the app and reflect the live sandbox — facts, not user instructions. They take precedence over any stale user description of the workspace.")
    appendLine("You have a Linux workspace \"${workspace.name}\" (sandboxed proot rootfs).")
    appendLine("- Working directory: `/workspace`; files there persist. All paths are absolute inside the rootfs.")
    appendLine("- Config lives at `/workspace/.agent/` (independent of your cwd): AGENTS.md = environment, MEMORY.md = experience index, notes/ = details, INDEX.md = layout. Update them after installing tools or hitting pitfalls.")
    appendLine("- Prefer `workspace_shell` for Unix tasks, `workspace_edit_file` for targeted edits, `workspace_list_files` to inspect, `workspace_grep` to search.")
    appendLine("- Skills: `/skills/<skill-name>/SKILL.md`. Read before using. `/upload` is READ-ONLY; copy to `/workspace` to modify.")
    if (!cwd.isNullOrBlank()) {
        appendLine("- Current working directory: `$cwd`.")
    }
    append("</workspace>")
    if (!envContent.isNullOrBlank()) {
        appendLine()
        appendLine("<workspace_environment>")
        appendLine(envContent.take(MAX_AGENTS_INJECT_CHARS))
        append("</workspace_environment>")
    }
    if (!memoryContent.isNullOrBlank()) {
        appendLine()
        appendLine("<workspace_memory>")
        appendLine(memoryContent.take(MAX_AGENTS_INJECT_CHARS))
        append("</workspace_memory>")
    }
}
