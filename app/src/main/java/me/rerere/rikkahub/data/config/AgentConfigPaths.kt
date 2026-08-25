package me.rerere.rikkahub.data.config

import java.io.File

/**
 * agent/ 目录布局常量 + AI 可读路径白名单。
 *
 * 白名单与 canonical 校验照抄 [me.rerere.rikkahub.data.files.SkillPaths]
 * 的防御模式：AI 只能读 agent/ 下声明的文件，防目录穿越。
 */
object AgentConfigPaths {
    /** filesDir 下的根目录名 */
    const val ROOT_DIR = "agent"

    const val MANIFEST_FILE = "manifest.json"
    const val CONFIG_DIR = "config"
    const val PROVIDERS_FILE = "config/providers.json"
    const val MCP_FILE = "config/mcp.json"
    const val ASSISTANTS_DIR = "config/assistants"

    /** 编辑前快照目录（backups/） */
    const val BACKUPS_DIR = "backups"

    /** 文件编辑修订记录（state/revisions.json） */
    const val REVISIONS_FILE = "state/revisions.json"

    /** 允许读取的精确文件（相对 agent/）。 */
    private val ALLOWED_FILE_PATHS = setOf(MANIFEST_FILE)

    /** 允许读取的目录前缀（相对 agent/，带尾部 /）。 */
    private val ALLOWED_DIR_PREFIXES = listOf(
        "$CONFIG_DIR/",
        "policies/",
        "state/",
    )

    /**
     * 解析白名单内的相对路径；不合法（越权/穿越/空）返回 null。
     */
    fun resolveAllowed(agentRoot: File, relativePath: String): File? {
        if (relativePath.isBlank()) return null
        val allowed = relativePath in ALLOWED_FILE_PATHS ||
            ALLOWED_DIR_PREFIXES.any { relativePath.startsWith(it) }
        if (!allowed) return null

        val canonicalRoot = agentRoot.canonicalFile
        val canonicalTarget = canonicalRoot.resolve(relativePath).canonicalFile
        val rootPath = canonicalRoot.path
        val targetPath = canonicalTarget.path
        if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) return null
        return canonicalTarget
    }
}
