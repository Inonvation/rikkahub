package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

/** 注入 system prompt 的 AGENTS 内容上限, 防 AI 把文件写大撑爆 context */
internal const val MAX_AGENTS_INJECT_CHARS = 4096

/** cwd 级 AGENTS.md 读取字节上限, 防异常大文件整体载入内存; 注入时再按 [MAX_AGENTS_INJECT_CHARS] 截断 */
private const val MAX_CWD_AGENTS_READ_BYTES = 64L * 1024

/** Rootfs 内 workspace files 区挂载点, 会话 cwd 的根 */
private const val ROOTFS_WORKSPACE_DIR = "/workspace"

/**
 * Workspace 系统提示注入转换器
 *
 * 当助手绑定了一个 shell 已就绪的 workspace 时, 在系统提示词中追加一段引导,
 * 让模型了解 workspace 环境与 workspace_* 工具的使用方式。
 * AGENTS 分两层: /workspace/.agent/AGENTS.md 为全局层(自动生成+自愈),
 * 会话 cwd 下的 AGENTS.md 为 cwd 级项目指令层, 存在且非空才注入。
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

        // 每次新会话自动刷新 AGENTS 自动生成区(节流控制探测脚本频率)。内容无变化时不写文件,
        // 注入文本与上轮逐字节一致, 保持 LLM prompt 缓存前缀稳定不失效。
        runCatching { workspaceRepository.refreshAgentsFileIfStale(workspaceId) }
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

        // cwd 级项目指令: 无此文件时为 null, 注入文本与原先逐字节一致
        val cwdAgents = readCwdAgentsInstructions(workspaceId, ctx.workspaceCwd)

        val prompt = buildWorkspacePrompt(workspace, ctx.workspaceCwd, envContent, memoryContent, cwdAgents)

        // 追加到第一条 system 消息; 若不存在则插入一条
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex]
                    .appendText("\n\n$prompt")
                    .copy(isSynthetic = true)
            }
        } else {
            listOf(UIMessage.system(prompt).copy(isSynthetic = true)) + messages
        }
    }

    /** 读取会话 cwd 下的 AGENTS.md; 路径非法/不存在/空白/异常一律静默返回 null(取消除外) */
    private suspend fun readCwdAgentsInstructions(
        workspaceId: String,
        cwd: String?,
    ): Pair<String, String>? {
        val path = resolveCwdAgentsPath(cwd) ?: return null
        val content = try {
            workspaceRepository.readRootfsTextRange(workspaceId, path, 0, MAX_CWD_AGENTS_READ_BYTES).text
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }?.takeIf { it.isNotBlank() } ?: return null
        return path to content
    }
}

/**
 * 由会话 cwd 解析 cwd 级 AGENTS.md 的 Rootfs 绝对路径。
 * 空白 cwd 视为 /workspace; 相对路径按 /workspace 解析; 规范化后逃出 /workspace,
 * 或恰好是全局层 /workspace/.agent/AGENTS.md(避免同文件重复注入)时返回 null。
 */
internal fun resolveCwdAgentsPath(cwd: String?): String? {
    val raw = cwd?.takeIf { it.isNotBlank() } ?: ROOTFS_WORKSPACE_DIR
    val normalized = if (raw.startsWith("/")) raw else "$ROOTFS_WORKSPACE_DIR/$raw"
    val segments = ArrayDeque<String>()
    for (segment in normalized.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.removeLastOrNull() == null) return null
            else -> segments.addLast(segment)
        }
    }
    if (segments.firstOrNull() != "workspace") return null
    val path = "/" + (segments + "AGENTS.md").joinToString("/")
    return path.takeUnless { it == "/workspace/.agent/AGENTS.md" }
}

/**
 * 环境块总预算：三段 AGENTS/MEMORY 文本合计上限，超出时按
 * cwd 指令 > 环境探测 > 记忆索引 的优先级裁剪（越靠近当前工作目录越重要）。
 * 各段仍各自受 [MAX_AGENTS_INJECT_CHARS] 约束；本预算防止三段同时打满（≈12KB）。
 */
internal const val MAX_ENV_TOTAL_CHARS = 6 * 1024

/**
 * 按优先级把 [MAX_ENV_TOTAL_CHARS] 的总额度分给三段文本（cwd 指令最先占额）。
 * 返回 (cwd, env, memory) 三段各自可分到的字符数，纯函数便于单测。
 */
internal fun allocateEnvBudget(
    cwdChars: Int,
    envChars: Int,
    memoryChars: Int,
    total: Int = MAX_ENV_TOTAL_CHARS,
): Triple<Int, Int, Int> {
    var budget = total
    fun grant(requested: Int): Int {
        val clipped = requested.coerceAtMost(MAX_AGENTS_INJECT_CHARS).coerceAtMost(budget.coerceAtLeast(0))
        budget -= clipped
        return clipped
    }
    val cwd = grant(cwdChars)
    val env = grant(envChars)
    val memory = grant(memoryChars)
    return Triple(cwd, env, memory)
}

private fun buildWorkspacePrompt(
    workspace: WorkspaceEntity,
    cwd: String? = null,
    envContent: String? = null,
    memoryContent: String? = null,
    cwdAgents: Pair<String, String>? = null,
): String = buildString {
    appendLine("<workspace>")
    appendLine("Linux workspace \"${workspace.name}\" (PRoot sandbox on Android; not Windows — use Unix commands). Env & installed tools auto-refreshed below.")
    appendLine("- cwd: ${cwd ?: "/workspace"} · workspace_* tools resolve relative paths against it (writes outside it require approval).")
    appendLine("- /workspace/.agent/: AGENTS.md (auto env), MEMORY.md (index), notes/, INDEX.md (layout). Skills: /skills/<skill>/SKILL.md; /upload is read-only.")
    appendLine("- Reply with workspace images using ![alt](/workspace/<relative-path>) and file links using [name](/workspace/<relative-path>); use the exact paths workspace tools report (write results carry a ready-to-copy \"markdown\" field), never guess paths.")
    append("</workspace>")
    // 三段文本按优先级共享总预算：cwd 指令最先占额，余量依次给环境探测与记忆索引
    val (cwdBudget, envBudget, memoryBudget) = allocateEnvBudget(
        cwdChars = cwdAgents?.second?.length ?: 0,
        envChars = envContent?.length ?: 0,
        memoryChars = memoryContent?.length ?: 0,
    )
    if (cwdAgents != null && cwdBudget > 0) {
        val body = cwdAgents.second.take(cwdBudget)
        if (body.isNotBlank()) {
            appendLine()
            appendLine("<workspace_cwd_instructions>")
            appendLine("AGENTS.md at ${cwdAgents.first} (instructions for the current working directory):")
            appendLine(body)
            append("</workspace_cwd_instructions>")
        }
    }
    if (!envContent.isNullOrBlank() && envBudget > 0) {
        val body = envContent.take(envBudget)
        if (body.isNotBlank()) {
            appendLine()
            appendLine("<workspace_environment>")
            appendLine(body)
            append("</workspace_environment>")
        }
    }
    if (!memoryContent.isNullOrBlank() && memoryBudget > 0) {
        val body = memoryContent.take(memoryBudget)
        if (body.isNotBlank()) {
            appendLine()
            appendLine("<workspace_memory>")
            appendLine(body)
            append("</workspace_memory>")
        }
    }
}
