package me.rerere.rikkahub.data.files

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.utils.JsonInstant

/**
 * 技能的安装来源与更新状态（GitHub 导入的技能才有）。
 *
 * 持久化为 skills 目录下的 `.sources.json` 注册表 —— 与 VS Code `extensions/extensions.json`
 * 同款做法：元数据独立于技能内容存放，不污染 SKILL.md，且随 skills 目录一起被备份/恢复。
 */
@Serializable
data class SkillSource(
    /** 本地技能目录名（= 导入时的 frontmatter name），注册表主键 */
    val skillName: String,
    val repoOwner: String,
    val repoName: String,
    /** 为空串表示跟随默认分支 */
    val branch: String,
    /** 仓库内技能目录路径，空串表示仓库根目录 */
    val path: String,
    /** 安装/最近一次更新时「影响该路径」的最新 commit SHA；空串表示安装时查询失败（下次检查自愈） */
    val commitSha: String,
    /** commits API 的 ETag，用于条件请求（304 时不消耗未认证 API 配额） */
    val etag: String? = null,
    /** 安装时的内容指纹，用于识别本地改动（指纹不一致 = 本地已修改） */
    val contentHash: String,
    /** 自动更新：检测到更新且本地未修改时，后台检查中直接应用 */
    val autoUpdate: Boolean = false,
    /** 本地文件相对安装内容有改动（重新计算而非缓存，检查/应用时刷新） */
    val localModified: Boolean = false,
    /** 最近一次检查发现远端有新 commit（UI 徽标依据，持久化以跨重启显示） */
    val updateAvailable: Boolean = false,
    /** 检测到的远端最新 commit SHA */
    val remoteSha: String? = null,
    /** 全字段给默认值：老版本注册表缺新增字段时仍能解码，避免整表静默重置 */
    val installedAt: Long = 0,
    val lastCheckedAt: Long = 0,
)

/**
 * `.sources.json` 的读写（JSON 数组，按 skillName 主键）。
 *
 * 纯 JVM 实现（不依赖 Android 类），便于单元测试；调用方负责并发互斥。
 */
class SkillSourceRegistry(private val file: File) {
    fun load(): Map<String, SkillSource> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            JsonInstant.decodeFromString<List<SkillSource>>(file.readText())
                .associateBy { it.skillName }
        }.getOrElse { emptyMap() }
    }

    fun save(registry: Map<String, SkillSource>) {
        file.parentFile?.mkdirs()
        // 原子写（tmp + rename）：进程被杀时不留半截 JSON，避免注册表整体损坏后所有来源丢失
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JsonInstant.encodeToString(registry.values.toList()))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            file.writeText(JsonInstant.encodeToString(registry.values.toList()))
        }
    }

    companion object {
        const val REGISTRY_FILE_NAME = ".sources.json"
    }
}

/**
 * 内容指纹：对有序 (路径, 字节) 序列做 SHA-256。
 *
 * 路径用 invariant 分隔符（`/`），保证 GitHub 导入记录的指纹与磁盘重算可比较。
 * 用途：识别「本地已修改」，避免自动更新静默覆盖用户编辑。
 */
object SkillContentHash {
    fun computeFilesHash(files: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for ((path, bytes) in files.entries.sortedBy { it.key }) {
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(bytes)
            digest.update(0)
        }
        return digest.digest().toHexString()
    }

    /** 计算技能目录的内容指纹；目录不存在返回 null。包含目录下全部文件（含用户新增）。 */
    fun computeDirHash(dir: File): String? {
        if (!dir.exists()) return null
        val files = mutableMapOf<String, ByteArray>()
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            files[file.toRelativeString(dir).replace(File.separatorChar, '/')] = file.readBytes()
        }
        return computeFilesHash(files)
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}
