package me.rerere.rikkahub.data.trustedfolders

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlin.uuid.Uuid

/**
 * 信任文件夹门面类。给 UI 和 AI 工具统一入口。
 *
 * - 项目管理：添加（SAF 树 URI）/删除/重命名/激活切换
 * - 审批判定：[approvalNeeded] 按操作类型读对应开关
 * - 文件操作：全部走当前激活项目（未激活则报错）
 */
class TrustedFolderRepository(
    private val context: Context,
    private val store: TrustedFolderStore,
) {
    private val access = SafeFolderAccess(context)

    /** 笔记名索引缓存：项目 treeUri -> (笔记名匹配键 -> 相对路径)。加速双链跳转，写操作后失效、带 TTL */
    private val noteIndexCache = HashMap<String, Map<String, String>>()
    private val noteIndexBuiltAt = HashMap<String, Long>()

    /** 图片名索引缓存：项目 treeUri -> (文件名匹配键 -> 相对路径)。供 Obsidian 附件图片按名定位 */
    private val imageIndexCache = HashMap<String, Map<String, String>>()
    private val imageIndexBuiltAt = HashMap<String, Long>()

    private companion object {
        const val NOTE_INDEX_TTL = 60_000L
    }

    /** 笔记名匹配键（与 SafeFolderAccess.toFileNameKey 保持一致）：去 md 后缀 + NFC 规范化 + 转小写 */
    private fun noteKey(name: String): String =
        name.removeSuffix(".md").removeSuffix(".markdown")
            .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFC) }
            .trim()
            .lowercase()

    /** 获取项目笔记索引：缓存有效直接返回，否则惰性重建（IO 线程，不持锁） */
    private suspend fun getNoteIndex(uri: String): Map<String, String> {
        val now = System.currentTimeMillis()
        synchronized(this) {
            val ts = noteIndexBuiltAt[uri]
            if (ts != null && now - ts < NOTE_INDEX_TTL) {
                return noteIndexCache[uri] ?: emptyMap()
            }
        }
        val index = access.indexMarkdownFiles(uri)
        synchronized(this) {
            noteIndexCache[uri] = index
            noteIndexBuiltAt[uri] = System.currentTimeMillis()
        }
        return index
    }

    /** 获取项目图片索引：缓存有效直接返回，否则惰性重建（IO 线程，不持锁） */
    private suspend fun getImageIndex(uri: String): Map<String, String> {
        val now = System.currentTimeMillis()
        synchronized(this) {
            val ts = imageIndexBuiltAt[uri]
            if (ts != null && now - ts < NOTE_INDEX_TTL) {
                return imageIndexCache[uri] ?: emptyMap()
            }
        }
        val index = access.indexImageFiles(uri)
        synchronized(this) {
            imageIndexCache[uri] = index
            imageIndexBuiltAt[uri] = System.currentTimeMillis()
        }
        return index
    }

    /** 文件结构变化后清空索引缓存，强制下次重建 */
    private fun invalidateNoteIndex() {
        synchronized(this) {
            noteIndexCache.clear()
            noteIndexBuiltAt.clear()
            imageIndexCache.clear()
            imageIndexBuiltAt.clear()
        }
    }

    /** 后台预热笔记 + 图片索引，加速后续双链跳转与附件图片加载。失败静默（回退到惰性构建） */
    suspend fun prewarmNoteIndex(projectId: String? = null) {
        runCatching {
            project(projectId) {
                getNoteIndex(it.treeUri)
                getImageIndex(it.treeUri)
            }
        }
    }

    /** 按图片名（Obsidian 附件 `![[name.jpg]]` 或 `![[name]]`）全局查找图片，返回相对路径；找不到返回 null */
    suspend fun resolveImagePath(name: String, projectId: String? = null): String? {
        if (name.isBlank()) return null
        val uri = project(projectId) { it.treeUri }
        return getImageIndex(uri)[noteKey(name)]
    }

    val settingsFlow: Flow<TrustedFolderSettings> = store.settingsFlow

    suspend fun currentSettings(): TrustedFolderSettings = store.current()

    /** 以当前设置为基准做变换后整体落盘（项目管理共用） */
    suspend fun updateSettings(transform: (TrustedFolderSettings) -> TrustedFolderSettings) {
        store.update(transform(store.current()))
    }

    /** 按项目 id 修改单个项目的任意字段（审批开关/配置目录设置，与其它项目隔离） */
    suspend fun updateProjectSettings(projectId: String, transform: (TrustedFolderProject) -> TrustedFolderProject) {
        store.update(
            store.current().let { s ->
                s.copy(projects = s.projects.map { if (it.id == projectId) transform(it) else it })
            }
        )
    }

    /** 读取单个项目的「显示配置目录」开关（文件浏览器按项目过滤 .obsidian 等目录时用） */
    suspend fun showConfigFoldersOf(projectId: String): Boolean =
        withProject(projectId) { it.showConfigFolders }

    // ---------- 最近访问 ----------

    /** 记录一条最近访问（跨会话持久化），供详情页「最近访问」展示 */
    suspend fun recordRecentFile(projectId: String, path: String) {
        store.recordRecentFile(RecentFile(projectId = projectId, path = path, openedAt = System.currentTimeMillis()))
    }

    /** 某项目的最近访问文件路径（时间倒序，最多 10 条） */
    fun recentFilesFlow(projectId: String): Flow<List<String>> =
        store.recentFilesFlow
            .map { list -> list.filter { it.projectId == projectId }.sortedByDescending { it.openedAt }.take(10).map { it.path } }

    // ---------- 项目管理 ----------

    suspend fun addProject(name: String, treeUri: String): TrustedFolderProject {
        val finalName = name.trim().ifBlank { "信任文件夹" }
        val settings = store.current()
        require(settings.projects.none { it.name.trim() == finalName }) {
            "已存在同名项目: $finalName"
        }
        val project = TrustedFolderProject(
            id = Uuid.random().toString(),
            name = finalName,
            treeUri = treeUri,
            createdAt = System.currentTimeMillis(),
        )
        store.update(settings.copy(projects = settings.projects + project))
        return project
    }

    suspend fun removeProject(id: String) {
        store.update(
            store.current().let { s ->
                s.copy(
                    projects = s.projects.filterNot { it.id == id },
                    // 删除的是激活项目时解除激活
                    activeProjectId = s.activeProjectId?.takeIf { it != id },
                )
            }
        )
    }

    suspend fun renameProject(id: String, name: String): Boolean {
        val finalName = name.trim()
        if (finalName.isBlank()) return false
        store.update(
            store.current().let { s ->
                require(s.projects.none { it.id != id && it.name.trim() == finalName }) {
                    "已存在同名项目: $finalName"
                }
                s.copy(projects = s.projects.map { if (it.id == id) it.copy(name = finalName) else it })
            }
        )
        return true
    }

    suspend fun setActiveProject(id: String?) {
        store.update(store.current().copy(activeProjectId = id))
    }

    // ---------- 审批判定 ----------

    /**
     * 按操作类型查【激活项目】的审批开关。同步返回以匹配 [me.rerere.ai.core.Tool.needsApproval] 的同步签名；
     * DataStore 读取为毫秒级磁盘读，频率低（每次工具调用一次），可接受。
     * 无激活项目时回退默认值（此时工具本就不会注入）。
     */
    fun approvalNeeded(op: TrustedOp): Boolean = runBlocking {
        val s = store.current()
        val active = s.activeProjectId?.let { id -> s.projects.find { it.id == id } }
        when (op) {
            TrustedOp.READ -> active?.approvalRead ?: false
            TrustedOp.CREATE -> active?.approvalCreate ?: true
            TrustedOp.EDIT -> active?.approvalEdit ?: true
            TrustedOp.DELETE -> active?.approvalDelete ?: true
        }
    }

    // ---------- 文件操作（仅激活项目） ----------

    private suspend fun requireActive(): TrustedFolderProject {
        val settings = store.current()
        return settings.activeProjectId
            ?.let { id -> settings.projects.find { it.id == id } }
            ?: throw IllegalStateException("请先在「信任文件夹」中激活一个项目")
    }

    suspend fun <T> withActiveProject(block: suspend (TrustedFolderProject) -> T): T =
        block(requireActive())

    /** 按项目 id 执行操作（文件浏览器/编辑器按项目浏览时用）。项目不存在则报错 */
    suspend fun <T> withProject(projectId: String, block: suspend (TrustedFolderProject) -> T): T {
        val s = store.current()
        val project = s.projects.find { it.id == projectId }
            ?: throw IllegalArgumentException("项目不存在或已删除")
        return block(project)
    }

    /**
     * 文件操作统一入口：传 [projectId] 用指定项目，否则用激活项目（AI 工具场景）。
     * 让详情页/编辑器能按项目浏览，AI 工具现有调用（不带 projectId）行为不变。
     */
    private suspend fun <T> project(projectId: String?, block: suspend (TrustedFolderProject) -> T): T =
        if (projectId != null) withProject(projectId, block) else withActiveProject(block)

    /** 是否位于配置目录（.obsidian 等点开头目录）内。同步，供工具审批判定使用 */
    fun isProtectedPath(relPath: String): Boolean =
        runCatching {
            SafeFolderAccess.validateRelPath(relPath).split('/').any { it.startsWith(".") }
        }.getOrDefault(false)

    /** 写操作前检查：配置目录默认禁止修改（除非本项目设置中开启允许） */
    private fun requireWritable(relPath: String, allowEditConfigFolders: Boolean) {
        if (isProtectedPath(relPath) && !allowEditConfigFolders) {
            throw IllegalArgumentException(
                "「$relPath」位于配置目录（.obsidian 等）内，已默认保护。如需 AI 修改，请在「项目设置」中开启「允许修改配置目录」。"
            )
        }
    }

    suspend fun list(relPath: String, projectId: String? = null): List<TrustedFolderEntry> =
        project(projectId) { access.list(it.treeUri, relPath) }

    suspend fun readText(relPath: String, projectId: String? = null): String =
        project(projectId) { access.readText(it.treeUri, relPath) }

    suspend fun readBytes(relPath: String, projectId: String? = null): ByteArray =
        project(projectId) { access.readBytes(it.treeUri, relPath) }

    suspend fun writeText(
        relPath: String,
        text: String,
        overwrite: Boolean,
        projectId: String? = null,
    ): TrustedFolderEntry =
        project(projectId) {
            invalidateNoteIndex()
            requireWritable(relPath, it.allowEditConfigFolders)
            access.writeText(it.treeUri, relPath, text, overwrite)
        }

    suspend fun createFolder(relPath: String, projectId: String? = null): TrustedFolderEntry =
        project(projectId) {
            invalidateNoteIndex()
            requireWritable(relPath, it.allowEditConfigFolders)
            access.createFolder(it.treeUri, relPath)
        }

    suspend fun rename(relPath: String, newName: String, projectId: String? = null): TrustedFolderEntry =
        project(projectId) {
            invalidateNoteIndex()
            requireWritable(relPath, it.allowEditConfigFolders)
            access.rename(it.treeUri, relPath, newName)
        }

    suspend fun move(relPath: String, targetDirRel: String, projectId: String? = null): TrustedFolderEntry =
        project(projectId) {
            invalidateNoteIndex()
            requireWritable(relPath, it.allowEditConfigFolders)
            requireWritable(targetDirRel, it.allowEditConfigFolders)
            access.move(it.treeUri, relPath, targetDirRel)
        }

    suspend fun delete(relPath: String, projectId: String? = null) =
        project(projectId) {
            invalidateNoteIndex()
            requireWritable(relPath, it.allowEditConfigFolders)
            access.delete(it.treeUri, relPath)
        }

    suspend fun search(
        relPath: String = "",
        query: String,
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        maxResults: Int = 50,
        projectId: String? = null,
    ): List<TrustedFolderSearchMatch> =
        project(projectId) { access.search(it.treeUri, query, relPath, regex, ignoreCase, maxResults) }

    /** 递归扫描全部 Markdown 笔记，返回 (相对路径, 内容) 列表。供断链/体检使用。 */
    suspend fun scanMarkdownFiles(projectId: String? = null): List<Pair<String, String>> =
        project(projectId) { access.scanMarkdownFiles(it.treeUri) }

    /**
     * 按笔记名（Obsidian 双链目标，不含扩展名/目录前缀）在激活项目里解析对应 .md 的相对路径。
     * 支持 `笔记名`、`子目录/笔记名`、带 .md 后缀等写法；找不到返回 null。
     */
    suspend fun resolveNotePath(dest: String, projectId: String? = null): String? {
        // 目标来自双链预处理，可能被 percent-encode（如空格 → %20）
        val decoded = runCatching { java.net.URLDecoder.decode(dest, "UTF-8") }.getOrDefault(dest)
        // 只取最后一段作为笔记名（Obsidian 双链目标通常为纯笔记名；`子目录/笔记` 中前缀仅作提示）
        val noteName = decoded.substringAfterLast('/').trim()
        if (noteName.isEmpty()) return null
        // 命中索引（含惰性构建），O(1) 定位，不逐文件遍历、不读内容
        return project(projectId) { getNoteIndex(it.treeUri)[noteKey(noteName)] }
    }

    /** 解析相对路径对应文件的 content:// URI（供图片预览直接用 Coil 加载）。非文件/不存在返回 null */
    suspend fun contentUri(relPath: String, projectId: String? = null): Uri? =
        runCatching { project(projectId) { access.resolveUri(it.treeUri, relPath) } }.getOrNull()

    /** 激活项目授权是否仍有效（用于 UI 提示） */
    suspend fun isActiveAuthorized(): Boolean =
        runCatching {
            withActiveProject { access.isAuthorized(it.treeUri) }
        }.getOrDefault(false)

    /** 查询某个项目的授权是否仍有效（同步，用于列表项状态展示） */
    fun isAuthorized(project: TrustedFolderProject): Boolean =
        runCatching { access.isAuthorized(project.treeUri) }.getOrDefault(false)
}
