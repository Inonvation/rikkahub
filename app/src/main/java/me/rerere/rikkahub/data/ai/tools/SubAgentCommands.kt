package me.rerere.rikkahub.data.ai.tools

import me.rerere.rikkahub.data.ai.subagent.SubAgentCatalog

/**
 * 手动强制调用子代理的指令注册表。
 *
 * 用户以 `/search 今天的新闻` 形式输入时，ChatService 拦截并直接派发对应子代理
 * （不走母代理的 spawn_subagent 工具，因此不受并发上限影响），子代理结果回填后再
 * 让母代理综合生成最终回复。
 *
 * 若 enableSubAgent 关闭，则提示用户先开启，不执行。
 *
 * 指令 → 子代理映射自动跟随 [SubAgentCatalog]：新增子代理只需在 Catalog 注册，
 * 这里会自动获得对应指令（prefix 固定为 "/" + agentId）。描述取自 Catalog 的 description。
 */
object SubAgentCommands {
    data class Command(
        val prefix: String,
        val agentId: String,
        val description: String,
    )

    /** 指令 → 子代理 派生表。prefix = "/" + agentId，保证与 Catalog 动态对齐。 */
    val all: List<Command> = SubAgentCatalog.all.map { def ->
        Command(
            prefix = "/${def.id}",
            agentId = def.id,
            description = def.description,
        )
    }

    /**
     * 解析用户输入。命中指令时返回 (Command, 任务文本)；否则返回 null。
     * 任务文本为指令后紧跟的内容（首空格起，去除首尾空白）。
     */
    fun parse(text: String): Pair<Command, String>? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return null
        val cmd = trimmed.substringBefore(' ')
        val command = all.find { it.prefix == cmd } ?: return null
        val task = trimmed.removePrefix(cmd).trim()
        return command to task
    }
}
