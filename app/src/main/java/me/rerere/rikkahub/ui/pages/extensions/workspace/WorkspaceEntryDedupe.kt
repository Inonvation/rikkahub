package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.workspace.WorkspaceFileEntry

/**
 * 去掉批量选择中「目录与其内部子孙」的子孙条目，只保留最外层节点。
 *
 * 根因：批量操作逐条执行，若同时选中目录及其内部文件，先整体操作目录后（移入回收站/彻底删除/移动），
 * 子文件已随目录一并处理，再对其单独操作会因路径已不存在而报「路径不存在」的误报错（数据不丢，仅提示难看）。
 *
 * 方案：目录的 path 无尾斜杠，子孙的 path 以「dirPath/」为前缀，据此过滤掉被任一已选目录覆盖的条目。
 * 不误伤同前缀的兄弟目录（如 `sub2/x` 不以 `sub/` 开头）。
 *
 * internal：供本模块单元测试验证。
 */
internal fun dedupeNestedEntries(entries: List<WorkspaceFileEntry>): List<WorkspaceFileEntry> {
    val dirs = entries.filter { it.isDirectory }
    if (dirs.isEmpty()) return entries
    return entries.filter { entry ->
        dirs.none { dir -> entry.path.startsWith(dir.path + "/") }
    }
}
