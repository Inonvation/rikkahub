package me.rerere.rikkahub.data.trustedfolders

import kotlinx.serialization.Serializable

/** AI 操作类型，决定对应审批开关（开关开 = 每次操作前需用户确认） */
enum class TrustedOp {
    /** 读取 / 列目录 */
    READ,

    /** 新建文件 / 新建文件夹 */
    CREATE,

    /** 编辑内容 / 重命名 / 移动 */
    EDIT,

    /** 删除 */
    DELETE,
}

/**
 * 一个信任文件夹项目：名称 + SAF 目录树 URI（"门禁卡"）+ 按项目独立设置。
 * 审批开关/配置目录保护均为本项目私有，互不影响。
 */
@Serializable
data class TrustedFolderProject(
    val id: String,
    val name: String,
    /** SAF 目录树 content:// URI，由系统文件选择器签发并持久授权 */
    val treeUri: String,
    val createdAt: Long,
    /** 本项目读取审批（开 = AI 读文件/列目录前需确认）。默认关：读不打扰 */
    val approvalRead: Boolean = false,
    /** 本项目新建审批。默认开 */
    val approvalCreate: Boolean = true,
    /** 本项目编辑审批（含重命名/移动）。默认开 */
    val approvalEdit: Boolean = true,
    /** 本项目删除审批。默认开 */
    val approvalDelete: Boolean = true,
    /** 本项目的文件列表中是否显示配置目录（.obsidian 等点开头目录）。默认隐藏 */
    val showConfigFolders: Boolean = false,
    /** 本项目是否允许 AI 修改配置目录。默认禁止（保护 vault 配置不被 AI 搞坏） */
    val allowEditConfigFolders: Boolean = false,
)

/** 目录条目（列目录返回），[path] 为相对项目根的路径 */
data class TrustedFolderEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val updatedAt: Long,
)

/** 内容搜索结果（[path] 相对项目根，[text] 为命中的那行） */
data class TrustedFolderSearchMatch(
    val path: String,
    val line: Int,
    val text: String,
)

/** 断链/笔记体检结果 */
data class TrustedFolderHealthReport(
    /** 链接指向不存在笔记的双链 */
    val brokenLinks: List<TrustedFolderBrokenLink> = emptyList(),
    /** 空笔记（内容为空或仅含 frontmatter） */
    val emptyNotes: List<String> = emptyList(),
    /** 扫描到的 Markdown 笔记总数 */
    val totalNotes: Int = 0,
)

/** 一条断链：来源笔记 + 链接文本 + 目标 */
data class TrustedFolderBrokenLink(
    val source: String,
    val link: String,
    val target: String,
)

/** 最近访问的文件记录（跨会话持久化），[path] 相对项目根 */
@Serializable
data class RecentFile(
    val projectId: String,
    val path: String,
    val openedAt: Long,
)
