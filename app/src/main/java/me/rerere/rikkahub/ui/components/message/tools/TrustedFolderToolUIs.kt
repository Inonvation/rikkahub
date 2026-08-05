package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert01
import me.rerere.hugeicons.stroke.ArrowDataTransferHorizontal
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.FileAdd
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.PencilEdit01
import me.rerere.hugeicons.stroke.Search01

/**
 * 信任文件夹工具的气泡渲染器。
 *
 * 核心目的：AI 调用工具时，气泡标题直接表达**操作类型 + 目标文件**（如「编辑文件：notes/日记.md」、
 * 「删除文件：xxx.md」），让用户在批准/拒绝前一眼看清 AI 要做什么。
 * 这些标题在 pending（待审批）状态即可读——入参在工具执行前已就位。
 */
object TrustedFolderListToolUI : ToolUIRenderer {
    override val toolName: String = "trusted_folder_list"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Folder01

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path")
        return if (path.isNullOrBlank()) "列目录：根目录" else "列目录：$path"
    }
}

object TrustedFolderReadToolUI : ToolUIRenderer {
    override val toolName: String = "trusted_folder_read"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileView

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path") ?: "?"
        return "读取文件：$path"
    }
}

object TrustedFolderSearchToolUI : ToolUIRenderer {
    override val toolName: String = "trusted_folder_search"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Search01

    @Composable
    override fun title(context: ToolUIContext): String {
        val query = (context.arguments.getStringContent("query") ?: "?").let { q ->
            if (q.length > 24) q.take(24) + "…" else q
        }
        return "搜索：$query"
    }
}

object TrustedFolderWriteToolUI : ToolUIRenderer {
    override val toolName: String = "trusted_folder_write"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FileAdd

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path") ?: "?"
        return "写入文件：$path"
    }
}

object TrustedFolderCreateFolderToolUI : ToolUIRenderer {
    override val toolName: String = "trusted_folder_create_folder"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.FolderAdd

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path") ?: "?"
        return "新建文件夹：$path"
    }
}

object TrustedFolderEditToolUI : ToolUIRenderer {
    override val toolName: String = "trusted_folder_edit"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Edit01

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path") ?: "?"
        return "编辑文件：$path"
    }
}

object TrustedFolderRenameToolUI : ToolUIRenderer {
    override val toolName: String = "trusted_folder_rename"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.PencilEdit01

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path") ?: "?"
        val newName = context.arguments.getStringContent("new_name") ?: "?"
        return "重命名：$path → $newName"
    }
}

object TrustedFolderMoveToolUI : ToolUIRenderer {
    override val toolName: String = "trusted_folder_move"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.ArrowDataTransferHorizontal

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path") ?: "?"
        val target = context.arguments.getStringContent("target_dir") ?: "根目录"
        return "移动：$path → $target"
    }
}

object TrustedFolderDeleteToolUI : ToolUIRenderer {
    override val toolName: String = "trusted_folder_delete"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Delete01

    @Composable
    override fun title(context: ToolUIContext): String {
        val path = context.arguments.getStringContent("path") ?: "?"
        return "删除文件：$path"
    }
}

object TrustedFolderCheckLinksToolUI : ToolUIRenderer {
    override val toolName: String = "trusted_folder_check_links"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.Alert01

    @Composable
    override fun title(context: ToolUIContext): String = "检查断链/空笔记"
}
