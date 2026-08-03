package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.ai.subagent.SubAgentCatalog

/**
 * 手动调用子代理的指令注册表。
 *
 * 用户以 `/search 今天的新闻` 形式输入时，ChatService 把指令改写成一句明确的普通用户消息
 * （"请使用子代理「信息搜索子代理」完成任务"），走母代理正常生成流程——母代理先思考、
 * 再 spawn_subagent 派发、子代理后台跑、完成时自动唤醒续答返回。
 *
 * 支持一条消息里多个指令（换行分隔，见 [parseAll]），母代理会并行派发多个子代理。
 *
 * 若 enableSubAgent 关闭，则提示用户先开启，不执行。
 *
 * 指令 → 子代理映射自动跟随 [SubAgentCatalog]：新增子代理只需在 Catalog 注册，
 * 这里会自动获得对应指令（全称 "/" + agentId，外加定义里配置的短别名 commandAlias，
 * 如 /plan /search /doc /code /data）。
 */
object SubAgentCommands {
    /** 解析结果：命中的指令 + 去掉指令前缀后的任务文本 */
    data class Command(
        val prefix: String,
        val agentId: String,
    )

    /** 指令 → 子代理 映射表。每个子代理提供短别名（/plan /search /doc /code /data）
     *  + 全 id 形式（/planner /web_researcher …），保证既好记又跟 [SubAgentCatalog] 精确对齐。 */
    private val agentIdByPrefix: Map<String, String> = buildMap {
        SubAgentCatalog.all.forEach { def ->
            put("/${def.id}", def.id)
            def.commandAlias?.let { alias -> put("/$alias", def.id) }
        }
    }

    /**
     * 解析用户输入。命中指令时返回 (Command, 任务文本)；否则返回 null。
     * 任务文本为指令后紧跟的内容（首空格起，去除首尾空白）。
     */
    fun parse(text: String): Pair<Command, String>? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return null
        val cmd = trimmed.substringBefore(' ')
        val agentId = agentIdByPrefix[cmd] ?: return null
        val task = trimmed.removePrefix(cmd).trim()
        return Command(prefix = cmd, agentId = agentId) to task
    }

    /** 一次解析的多个指令任务 */
    data class ParsedCommand(
        val command: Command,
        val task: String,
    )

    /**
     * 按行解析一段文本里的**多个**子代理指令（换行分隔）。
     *
     * 规则：
     * - 每行首部若是已知指令（/search /code 等）→ 开启一个新任务；
     * - 其余行（非指令行）→ 追加为前一个任务的补充说明（任务文本可跨行）；
     * - 没有任何指令 → 返回空列表（走普通消息）。
     *
     * 例：
     * ```
     * /search 查最新手机评测
     * 关注续航和拍照
     * /code 写个统计脚本
     * ```
     * 解析为两条任务：搜索（"查最新手机评测\n关注续航和拍照"）+ 代码（"写个统计脚本"）。
     */
    fun parseAll(text: String): List<ParsedCommand> {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return emptyList()

        val result = mutableListOf<ParsedCommand>()
        val pendingTask = StringBuilder()

        // 把积压的补充行合并到上一条任务（pendingTask 以 \n 开头，直接拼接即可）
        fun flush() {
            if (result.isNotEmpty()) {
                val last = result.last()
                result[result.lastIndex] = last.copy(task = (last.task + pendingTask).trim())
            }
            pendingTask.clear()
        }

        trimmed.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val parsed = if (line.startsWith("/")) parse(line) else null
            if (parsed != null) {
                flush()
                result.add(ParsedCommand(command = parsed.first, task = parsed.second))
            } else {
                // 非指令行（或行首 / 但不是已知指令）：追加为前一条任务的补充说明
                pendingTask.append('\n').append(line)
            }
        }
        flush()
        return result
    }

    /**
     * 把一条含子代理指令的用户文本改写为"请使用子代理"的指令。
     *
     * 用户输入的 `/search xxx` 在落库前被改写成这句，让母代理走正常生成流程。
     * 单个指令 → "请使用一个子代理「XX」（id）来完成任务"；多个指令（换行分隔）→
     * "请并行使用以下多个子代理"。不是指令消息 → 返回 null（保持原样）。
     */
    fun rewriteToInstruction(text: String): String? {
        val cmds = parseAll(text)
        if (cmds.isEmpty()) return null
        fun describe(c: ParsedCommand): String {
            val def = SubAgentCatalog.byId(c.command.agentId)
            val agentName = def?.name ?: c.command.agentId
            return "子代理「$agentName」（${c.command.agentId}）"
        }
        return if (cmds.size == 1) {
            val c = cmds.first()
            "请使用一个${describe(c)}来完成任务：\n${c.task}"
        } else {
            buildString {
                appendLine("请并行使用以下多个子代理来完成任务，每个子代理负责对应任务：")
                cmds.forEachIndexed { index, c ->
                    appendLine("${index + 1}. ${describe(c)}：${c.task}")
                }
            }.trim()
        }
    }
}
