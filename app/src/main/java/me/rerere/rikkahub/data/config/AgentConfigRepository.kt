package me.rerere.rikkahub.data.config

import android.content.res.AssetManager
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
import java.io.File

/**
 * agent/ 统一配置的只读门面。
 *
 * - [view]：目录结构 + manifest 元信息 + 计数（config_view 工具用）。
 * - [readConfigFile]：按白名单相对路径读取配置文件（config_read 工具用），
 *   越权/穿越/不存在一律返回 null。
 * - [schema]：读取内置 JSON Schema（assets/agent/schema/，config_schema 工具用）。
 * - [validate]：用 DTO 重新解码校验现有配置文件（config_validate 工具用）。
 *
 * 只读 MVP：本类只读不写；写路径（文件为源）由后续 P1 在同一个门面上扩展，
 * UI 与 AI 管理工具届时统一走这里，避免多套配置逻辑。
 */
class AgentConfigRepository(
    private val agentRoot: File,
    private val assets: AssetManager? = null,
) {
    val root: File get() = agentRoot

    fun view(): AgentConfigView {
        val manifest = readManifest()
        // 脏标记基准：manifest 文件最后写入时间（导出时最后写），晚于它的配置文件视为"导出后被手动改过"
        val manifestFile = File(agentRoot, AgentConfigPaths.MANIFEST_FILE)
        val manifestMtime = if (manifestFile.isFile) manifestFile.lastModified() else 0L
        val files = walkFilePaths(agentRoot)
            .map { absolutePath ->
                val rel = absolutePath
                    .removePrefix(agentRoot.path + File.separator)
                    .replace(File.separatorChar, '/')
                val file = File(agentRoot, rel)
                AgentConfigFileInfo(
                    path = rel,
                    bytes = file.length().toInt(),
                    status = manifest?.files?.get(rel) ?: "untracked",
                    category = classify(rel),
                    displayName = assistantDisplayName(rel),
                    dirty = manifestMtime > 0L && file.lastModified() > manifestMtime,
                )
            }
            .sortedBy { it.path }
        val providerCount = parseIfExists<ProviderConfigFile>(AgentConfigPaths.PROVIDERS_FILE)
            ?.providers?.size ?: 0
        val mcpServerCount = parseIfExists<McpConfigFile>(AgentConfigPaths.MCP_FILE)
            ?.servers?.size ?: 0
        val assistantCount = File(agentRoot, AgentConfigPaths.ASSISTANTS_DIR)
            .takeIf { it.isDirectory }
            ?.listFiles()
            ?.count { it.isFile && it.extension == "json" } ?: 0
        return AgentConfigView(
            schemaVersion = manifest?.schemaVersion ?: AGENT_CONFIG_SCHEMA_VERSION,
            source = manifest?.source,
            settingsDataVersion = manifest?.settingsDataVersion,
            exportedAt = manifest?.exportedAt,
            files = files,
            providerCount = providerCount,
            mcpServerCount = mcpServerCount,
            assistantCount = assistantCount,
        )
    }

    /** 白名单内读取；越权、穿越或文件不存在返回 null。 */
    fun readConfigFile(relativePath: String): String? {
        val file = AgentConfigPaths.resolveAllowed(agentRoot, relativePath) ?: return null
        if (!file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    /** 读取内置 JSON Schema（assets/agent/schema/<name>.schema.json）；未知名称或 assets 不可用返回 null。 */
    fun schema(schemaName: String): String? {
        val name = schemaName.lowercase()
            .let { if (it.endsWith(".schema.json")) it.removeSuffix(".schema.json") else it }
        if (name !in SCHEMA_NAMES) return null
        val assets = assets ?: return null
        return runCatching {
            assets.open("agent/schema/$name.schema.json")
                .bufferedReader()
                .use { it.readText() }
        }.getOrNull()
    }

    /** 用 DTO 重新解码校验现有配置文件，返回每文件结果。 */
    fun validate(): AgentConfigValidation {
        val results = linkedMapOf<String, String>()
        fun check(relativePath: String, parse: () -> Unit) {
            results[relativePath] = runCatching { parse(); "ok" }
                .getOrElse { "error: ${it.message}" }
        }
        check(AgentConfigPaths.MANIFEST_FILE) {
            JsonInstant.decodeFromString<AgentManifest>(
                File(agentRoot, AgentConfigPaths.MANIFEST_FILE).readText()
            )
        }
        check(AgentConfigPaths.PROVIDERS_FILE) {
            JsonInstant.decodeFromString<ProviderConfigFile>(
                File(agentRoot, AgentConfigPaths.PROVIDERS_FILE).readText()
            )
        }
        check(AgentConfigPaths.MCP_FILE) {
            JsonInstant.decodeFromString<McpConfigFile>(
                File(agentRoot, AgentConfigPaths.MCP_FILE).readText()
            )
        }
        File(agentRoot, AgentConfigPaths.ASSISTANTS_DIR)
            .takeIf { it.isDirectory }
            ?.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.forEach { f ->
                check("${AgentConfigPaths.ASSISTANTS_DIR}/${f.name}") {
                    JsonInstant.decodeFromString<AssistantConfigFile>(f.readText())
                }
            }
        return AgentConfigValidation(
            ok = results.values.all { it == "ok" },
            results = results,
        )
    }

    /**
     * 写配置文件（文件为源 P1 的写路径基础设施）：
     * 白名单校验 + 编辑前快照到 backups/ + 原子写（.tmp→rename）+ revisions.json 记录。
     * 返回 null 表示成功，否则返回错误信息。
     */
    fun writeConfigFile(relativePath: String, content: String): String? = synchronized(writeLock) {
        val file = AgentConfigPaths.resolveAllowed(agentRoot, relativePath)
            ?: return "path not allowed"
        if (!file.isFile) return "file not found"

        // 1) 编辑前快照，支持回退
        runCatching {
            File(agentRoot, AgentConfigPaths.BACKUPS_DIR).apply { mkdirs() }
                .resolve("${file.name.replace('.', '_')}_${System.currentTimeMillis()}.bak")
                .writeText(file.readText())
        }.onFailure { return "backup failed: ${it.message}" }

        // 2) 原子写
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(content, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(content, Charsets.UTF_8)
            }
        }.onFailure { return "write failed: ${it.message}" }

        // 3) 修订记录
        recordRevision(relativePath, content.length)
        null
    }

    /** 最近一次编辑修订（revisions.json 首条）；无则 null。 */
    fun latestRevision(): AgentConfigRevision? {
        val revFile = File(agentRoot, AgentConfigPaths.REVISIONS_FILE)
        if (!revFile.isFile) return null
        return runCatching {
            JsonInstant.decodeFromString<AgentConfigRevisions>(revFile.readText())
                .entries.firstOrNull()
        }.getOrNull()
    }

    /** 全部编辑修订（新→旧）；无则空列表。 */
    fun revisions(): List<AgentConfigRevision> {
        val revFile = File(agentRoot, AgentConfigPaths.REVISIONS_FILE)
        if (!revFile.isFile) return emptyList()
        return runCatching {
            JsonInstant.decodeFromString<AgentConfigRevisions>(revFile.readText()).entries
        }.getOrDefault(emptyList())
    }

    /** 某配置文件在 backups/ 下的全部编辑快照（新→旧）。 */
    fun listBackups(relativePath: String): List<AgentBackupInfo> {
        val file = AgentConfigPaths.resolveAllowed(agentRoot, relativePath) ?: return emptyList()
        if (!file.isFile) return emptyList()
        // 备份命名：<文件名去点>_<epoch>.bak（见 writeConfigFile）
        val prefix = file.name.replace('.', '_') + "_"
        return File(agentRoot, AgentConfigPaths.BACKUPS_DIR)
            .takeIf { it.isDirectory }
            ?.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".bak") }
            ?.sortedByDescending { it.lastModified() }
            ?.map {
                AgentBackupInfo(
                    name = it.name,
                    at = it.lastModified(),
                    size = it.length().toInt(),
                )
            }
            .orEmpty()
    }

    /** 读取指定备份快照内容（防穿越：仅限 backups/ 目录内的 .bak 文件）。 */
    fun readBackup(backupName: String): String? {
        if (backupName.isBlank() || !backupName.endsWith(".bak")) return null
        val backupDir = File(agentRoot, AgentConfigPaths.BACKUPS_DIR)
        val file = backupDir.resolve(backupName).canonicalFile
        if (file.parentFile?.canonicalFile != backupDir.canonicalFile) return null
        if (!file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    /** 脏标记：文件是否在最近一次导出（manifest 最后写入）后被手动修改。 */
    fun isFileDirty(relativePath: String): Boolean {
        val file = AgentConfigPaths.resolveAllowed(agentRoot, relativePath) ?: return false
        if (!file.isFile) return false
        val manifestFile = File(agentRoot, AgentConfigPaths.MANIFEST_FILE)
        if (!manifestFile.isFile) return false
        // 导出时 manifest 最后写：任何配置文件的 mtime 严格晚于 manifest 即视为导出后被改过
        return file.lastModified() > manifestFile.lastModified()
    }

    private fun recordRevision(relativePath: String, size: Int) {
        runCatching {
            val revFile = File(agentRoot, AgentConfigPaths.REVISIONS_FILE)
            val current = runCatching {
                JsonInstant.decodeFromString<AgentConfigRevisions>(revFile.readText())
            }.getOrDefault(AgentConfigRevisions())
            val entries = (
                listOf(
                    AgentConfigRevision(
                        at = System.currentTimeMillis(),
                        path = relativePath,
                        size = size,
                    )
                ) + current.entries
                ).take(MAX_REVISIONS)
            revFile.parentFile?.mkdirs()
            revFile.writeText(JsonInstantPretty.encodeToString(AgentConfigRevisions(entries)))
        }
    }

    private fun readManifest(): AgentManifest? =
        parseIfExists<AgentManifest>(AgentConfigPaths.MANIFEST_FILE)

    /** 按路径归类配置文件。 */
    private fun classify(relativePath: String): AgentConfigFileCategory = when {
        relativePath == AgentConfigPaths.MANIFEST_FILE -> AgentConfigFileCategory.MANIFEST
        relativePath == AgentConfigPaths.PROVIDERS_FILE -> AgentConfigFileCategory.PROVIDERS
        relativePath == AgentConfigPaths.MCP_FILE -> AgentConfigFileCategory.MCP
        relativePath.startsWith("${AgentConfigPaths.ASSISTANTS_DIR}/") ->
            AgentConfigFileCategory.ASSISTANT
        relativePath.startsWith("policies/") -> AgentConfigFileCategory.POLICY
        relativePath.startsWith("state/") -> AgentConfigFileCategory.STATE
        else -> AgentConfigFileCategory.OTHER
    }

    /** 助手配置文件返回助手显示名（解析失败/非助手文件返回 null）。 */
    private fun assistantDisplayName(relativePath: String): String? {
        if (!relativePath.startsWith("${AgentConfigPaths.ASSISTANTS_DIR}/")) return null
        return parseIfExists<AssistantConfigFile>(relativePath)
            ?.assistant
            ?.name
            ?.takeIf { it.isNotBlank() }
    }

    private inline fun <reified T> parseIfExists(relativePath: String): T? {
        val file = File(agentRoot, relativePath)
        if (!file.isFile) return null
        return runCatching { JsonInstant.decodeFromString<T>(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    /** 递归收集 agent/ 下的文件绝对路径（排除 .tmp 中间产物与 backups/ 编辑快照）。 */
    private fun walkFilePaths(root: File): List<String> {
        if (!root.isDirectory) return emptyList()
        return buildList {
            root.walkTopDown().forEach { f ->
                if (f.isFile &&
                    !f.name.endsWith(".tmp") &&
                    !f.absolutePath.startsWith(File(root, AgentConfigPaths.BACKUPS_DIR).absolutePath + File.separator)
                ) {
                    add(f.absolutePath)
                }
            }
        }
    }

    companion object {
        /** 内置 JSON Schema 白名单（assets/agent/schema/ 下的文件名）。 */
        private val SCHEMA_NAMES = setOf("manifest", "providers", "mcp", "assistant")

        /** revisions.json 最多保留的修订条数。 */
        private const val MAX_REVISIONS = 100

        /** 写路径互斥锁（编辑/导入/导出共用，防 .tmp 冲突）。 */
        private val writeLock = Any()
    }
}

/** config_validate 结果：每文件 decode 校验状态。 */
data class AgentConfigValidation(
    val ok: Boolean,
    val results: Map<String, String>,
)
