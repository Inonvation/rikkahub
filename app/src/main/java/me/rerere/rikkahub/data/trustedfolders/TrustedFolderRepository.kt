package me.rerere.rikkahub.data.trustedfolders

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * 信任文件夹门面类。给 UI 和 AI 工具统一入口。
 *
 * - 项目管理：添加（SAF 树 URI）/删除/重命名（文件夹库管理；激活=助手级绑定，见 Assistant.trustedFolderProjectId）
 * - 审批判定：[approvalNeeded] 按操作类型读对应项目的开关
 * - 文件操作：全部显式指定项目（AI 工具操作当前助手绑定的项目，UI 按所在页面项目操作）
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
        /** 笔记/图片索引有效期：重建成本高（SAF 遍历整棵树），10 分钟内复用；写操作会主动失效 */
        const val NOTE_INDEX_TTL = 600_000L
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
    suspend fun prewarmNoteIndex(projectId: String) {
        runCatching {
            withProject(projectId) {
                getNoteIndex(it.treeUri)
                getImageIndex(it.treeUri)
            }
        }
    }

    /** 按图片名（Obsidian 附件 `![[name.jpg]]` 或 `![[name]]`）全局查找图片，返回相对路径；找不到返回 null */
    suspend fun resolveImagePath(name: String, projectId: String): String? {
        if (name.isBlank()) return null
        val uri = withProject(projectId) { it.treeUri }
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

    /** 最近访问展示上限（横向 chips，避免过多） */
    private val RECENT_SHOW_LIMIT = 6

    /** 记录一条最近访问（跨会话持久化），供详情页「最近访问」展示 */
    suspend fun recordRecentFile(projectId: String, path: String) {
        store.recordRecentFile(RecentFile(projectId = projectId, path = path, openedAt = System.currentTimeMillis()))
    }

    /** 从最近访问移除一个文件（用户手动叉掉） */
    suspend fun removeRecentFile(projectId: String, path: String) {
        store.removeRecentFile(projectId, path)
    }

    /** 某项目的最近访问文件路径（时间倒序，最多 [RECENT_SHOW_LIMIT] 条，自动过滤已被删除的文件） */
    fun recentFilesFlow(projectId: String): Flow<List<String>> =
        store.recentFilesFlow
            .map { list -> list.filter { it.projectId == projectId }.sortedByDescending { it.openedAt } }
            .mapLatest { list ->
                // 先过滤已删除文件（contentUri 为 null = 文件不存在），再截取上限，
                // 避免最新几条都被删时列表空（IO 线程检查，避免卡 UI）
                withContext(Dispatchers.IO) {
                    list.filter { runCatching { contentUri(it.path, projectId) }.getOrNull() != null }
                        .take(RECENT_SHOW_LIMIT)
                        .map { it.path }
                }
            }

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
                s.copy(projects = s.projects.filterNot { it.id == id })
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

    // ---------- 审批判定 ----------
    /**
     * 按操作类型查指定项目的审批开关。同步返回以匹配 [me.rerere.ai.core.Tool.needsApproval] 的同步签名；
     * DataStore 读取为毫秒级磁盘读，频率低（每次工具调用一次），可接受。
     * 项目不存在时回退默认值（此时工具本就不会注入）。
     */
    fun approvalNeeded(op: TrustedOp, projectId: String): Boolean = runBlocking {
        val s = store.current()
        val project = s.projects.find { it.id == projectId }
        when (op) {
            TrustedOp.READ -> project?.approvalRead ?: false
            TrustedOp.CREATE -> project?.approvalCreate ?: true
            TrustedOp.EDIT -> project?.approvalEdit ?: true
            TrustedOp.DELETE -> project?.approvalDelete ?: true
        }
    }

    // ---------- 文件操作（显式指定项目） ----------

    /** 按项目 id 执行操作。项目不存在则报错 */
    suspend fun <T> withProject(projectId: String, block: suspend (TrustedFolderProject) -> T): T {
        val s = store.current()
        val project = s.projects.find { it.id == projectId }
            ?: throw IllegalArgumentException("项目不存在或已删除")
        return block(project)
    }

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

    suspend fun list(relPath: String, projectId: String): List<TrustedFolderEntry> =
        withProject(projectId) { access.list(it.treeUri, relPath) }

    suspend fun readText(relPath: String, projectId: String): String =
        withProject(projectId) { access.readText(it.treeUri, relPath) }

    suspend fun readBytes(relPath: String, projectId: String): ByteArray =
        withProject(projectId) { access.readBytes(it.treeUri, relPath) }

    suspend fun writeText(
        relPath: String,
        text: String,
        overwrite: Boolean,
        projectId: String,
    ): TrustedFolderEntry =
        withProject(projectId) {
            invalidateNoteIndex()
            requireWritable(relPath, it.allowEditConfigFolders)
            access.writeText(it.treeUri, relPath, text, overwrite)
        }

    suspend fun createFolder(relPath: String, projectId: String): TrustedFolderEntry =
        withProject(projectId) {
            invalidateNoteIndex()
            requireWritable(relPath, it.allowEditConfigFolders)
            access.createFolder(it.treeUri, relPath)
        }

    suspend fun rename(relPath: String, newName: String, projectId: String): TrustedFolderEntry =
        withProject(projectId) {
            invalidateNoteIndex()
            requireWritable(relPath, it.allowEditConfigFolders)
            access.rename(it.treeUri, relPath, newName)
        }

    suspend fun move(relPath: String, targetDirRel: String, projectId: String): TrustedFolderEntry =
        withProject(projectId) {
            invalidateNoteIndex()
            requireWritable(relPath, it.allowEditConfigFolders)
            requireWritable(targetDirRel, it.allowEditConfigFolders)
            access.move(it.treeUri, relPath, targetDirRel)
        }

    suspend fun delete(relPath: String, projectId: String) =
        withProject(projectId) {
            invalidateNoteIndex()
            requireWritable(relPath, it.allowEditConfigFolders)
            access.delete(it.treeUri, relPath)
        }

    suspend fun search(
        relPath: String,
        query: String,
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        maxResults: Int = 50,
        projectId: String,
    ): List<TrustedFolderSearchMatch> =
        withProject(projectId) { access.search(it.treeUri, query, relPath, regex, ignoreCase, maxResults) }

    /** 递归扫描全部 Markdown 笔记，返回 (相对路径, 内容) 列表。供断链/体检使用。 */
    suspend fun scanMarkdownFiles(projectId: String): List<Pair<String, String>> =
        withProject(projectId) { access.scanMarkdownFiles(it.treeUri) }

    /**
     * 按笔记名（Obsidian 双链目标，不含扩展名/目录前缀）在指定项目里解析对应 .md 的相对路径。
     * 支持 `笔记名`、`子目录/笔记名`、带 .md 后缀等写法；找不到返回 null。
     */
    suspend fun resolveNotePath(dest: String, projectId: String): String? {
        // 目标来自双链预处理，可能被 percent-encode（如空格 → %20）
        val decoded = runCatching { java.net.URLDecoder.decode(dest, "UTF-8") }.getOrDefault(dest)
        // 只取最后一段作为笔记名（Obsidian 双链目标通常为纯笔记名；`子目录/笔记` 中前缀仅作提示）
        val noteName = decoded.substringAfterLast('/').trim()
        if (noteName.isEmpty()) return null
        // 命中索引（含惰性构建），O(1) 定位，不逐文件遍历、不读内容
        return withProject(projectId) { getNoteIndex(it.treeUri)[noteKey(noteName)] }
    }

    /** 解析相对路径对应文件的 content:// URI（供图片预览直接用 Coil 加载）。非文件/不存在返回 null */
    suspend fun contentUri(relPath: String, projectId: String): Uri? =
        runCatching { withProject(projectId) { access.resolveUri(it.treeUri, relPath) } }.getOrNull()

    /** 查询某个项目的授权是否仍有效（同步，用于列表项状态展示） */
    fun isAuthorized(project: TrustedFolderProject): Boolean =
        runCatching { access.isAuthorized(project.treeUri) }.getOrDefault(false)
}
