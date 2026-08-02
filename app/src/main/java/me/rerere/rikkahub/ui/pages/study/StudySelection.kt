package me.rerere.rikkahub.ui.pages.study

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.TextSelection
import me.rerere.rikkahub.ui.components.ui.RikkaConfirmDialog

/**
 * 四个学习面板共用的批量选择状态。选中态下点击卡片改为勾选，而非打开详情。
 */
class StudySelectionState {
    var enabled by mutableStateOf(false)
        private set
    var selectedIds by mutableStateOf<Set<String>>(emptySet())
        private set
    val count: Int get() = selectedIds.size

    fun enter() {
        enabled = true
        selectedIds = emptySet()
    }

    fun exit() {
        enabled = false
        selectedIds = emptySet()
    }

    fun toggle(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun selectAll(ids: Collection<String>) {
        selectedIds = ids.toSet()
    }

    fun clear() {
        selectedIds = emptySet()
    }
}

@Composable
fun rememberStudySelectionState(): StudySelectionState = remember { StudySelectionState() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudySelectionTopBar(
    selectedCount: Int,
    scrollBehavior: TopAppBarScrollBehavior,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    onExit: () -> Unit,
) {
    LargeTopAppBar(
        title = { Text("已选择 $selectedCount 项") },
        navigationIcon = {
            IconButton(onClick = onExit) { Icon(HugeIcons.Cancel01, "退出选择") }
        },
        actions = {
            TextButton(onClick = onSelectAll) { Text("全选") }
            TextButton(onClick = onClear) { Text("取消") }
            IconButton(onClick = onDelete, enabled = selectedCount > 0) {
                Icon(HugeIcons.Delete01, "删除", tint = if (selectedCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

/**
 * 列表卡片进入选择模式的入口按钮（放在普通 TopBar actions 里）。
 */
@Composable
fun StudySelectionEntryIcon(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(HugeIcons.TextSelection, "选择") }
}

@Composable
fun DeleteSelectionConfirmDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    RikkaConfirmDialog(
        show = true,
        title = "删除 $count 项？",
        confirmText = "删除",
        dismissText = "取消",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        text = { Text("此操作不可撤销。") },
    )
}
