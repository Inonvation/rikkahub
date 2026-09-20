package me.rerere.rikkahub.data.files

/**
 * 技能更新变更集计算：纯 JVM 纯函数，输入均为「相对路径 -> git blob SHA」清单。
 *
 * 三方清单来源：
 * - manifest：注册表 [SkillSource.fileHashes]（安装/上次更新时的远端清单）；
 * - remote：git trees API 本次列出的远端清单（无需下载文件内容）；
 * - disk：本地技能目录逐文件重算（[SkillContentHash.computeDirBlobShas]）。
 */
sealed interface FileChange {
    val path: String

    data class Added(override val path: String) : FileChange

    data class Modified(override val path: String) : FileChange

    data class Removed(override val path: String) : FileChange
}

object SkillUpdateDiff {

    /**
     * 本地磁盘相对安装清单的状态。
     *
     * 语义（相对旧版整目录指纹的改进）：
     * - [modifiedTracked]：清单内文件被用户改动/删除 —— 视为「本地已修改」，阻塞自动更新；
     * - [locallyAdded]：用户新增的文件（不在清单内）—— 只是「本地扩展」，不算修改，
     *   不阻塞自动更新，且增量应用时会原样保留。
     */
    data class LocalState(
        val modifiedTracked: List<String>,
        val locallyAdded: List<String>,
    ) {
        val hasModifiedTracked: Boolean get() = modifiedTracked.isNotEmpty()
    }

    /**
     * 远端相对安装版本的变更集。manifest 为空（旧记录/首次登记失败）时
     * 全部远端文件记为 Added —— 自然退化为全量下载，无需单独的兼容路径。
     */
    fun computeChangeSet(
        manifest: Map<String, String>,
        remote: Map<String, String>,
    ): List<FileChange> {
        val changes = mutableListOf<FileChange>()
        for ((path, sha) in remote) {
            val installedSha = manifest[path]
            when {
                installedSha == null -> changes.add(FileChange.Added(path))
                installedSha != sha -> changes.add(FileChange.Modified(path))
            }
        }
        for (path in manifest.keys) {
            if (path !in remote) changes.add(FileChange.Removed(path))
        }
        return changes.sortedBy { it.path }
    }

    /** 清单 vs 本地磁盘：找出被改动的 tracked 文件与用户新增的文件。 */
    fun computeLocalState(
        manifest: Map<String, String>,
        disk: Map<String, String>,
    ): LocalState {
        val modifiedTracked = manifest.keys.filter { path -> disk[path] != manifest[path] }.sorted()
        val locallyAdded = disk.keys.filter { it !in manifest }.sorted()
        return LocalState(modifiedTracked = modifiedTracked, locallyAdded = locallyAdded)
    }
}
