package me.rerere.rikkahub.ui.hooks

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 拖拽排序的本地状态封装。
 *
 * 背景：sh.calvin.reorderable 的 onMove 在拖动过程中高频触发，且每次触发后都要等
 * layout 更新完才继续处理下一次移动。旧写法在 onMove 里读取外部状态（如 settings）
 * 再异步写回，快速拖动时多次回调拿到的都是同一份旧快照，中间移动互相覆盖，
 * 直观表现就是"其他条目的顺序被打乱"。逐次异步写盘还会拖慢 onMove 完成，加重卡顿。
 *
 * 这里把排序数据放到本地 State：onMove 同步修改本地列表（所见即所得），
 * 落盘只在整个拖拽结束（onDragStopped）时执行一次；拖动中途若异常中断，
 * 还有 500ms 防抖兜底保存。
 *
 * @param items 外部源列表（如 settings 中某段列表）。非拖动期间源列表变化会同步回本地。
 * @param persist 拖拽结束时的落盘回调，参数为最终顺序。
 * @param toLocalIndex LazyColumn 索引到可排序子列表索引的映射（如列表前面有 header 项时使用）。
 */
@Composable
fun <T : Any> rememberReorderUiState(
    lazyListState: LazyListState,
    items: List<T>,
    persist: (List<T>) -> Unit,
    toLocalIndex: (index: Int) -> Int = { it },
): ReorderUiState<T> {
    var localItems by remember { mutableStateOf(items) }
    var dirty by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val persistJob = remember { mutableStateOf<Job?>(null) }

    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromIndex = toLocalIndex(from.index)
        val toIndex = toLocalIndex(to.index)
        if (fromIndex == toIndex) return@rememberReorderableLazyListState
        if (fromIndex !in localItems.indices || toIndex !in 0..localItems.size) {
            return@rememberReorderableLazyListState
        }

        val newList = localItems.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
        localItems = newList
        dirty = true

        // 兜底：onDragStopped 未触发时（如拖动异常中断）延迟保存一次
        persistJob.value?.cancel()
        persistJob.value = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            if (dirty) {
                dirty = false
                persist(localItems)
            }
        }
    }

    // 外部列表变化（设置回流、过滤条件变化等）在非拖动期间同步回本地，避免覆盖进行中的拖动
    LaunchedEffect(items) {
        if (!reorderableState.isAnyItemDragging) {
            localItems = items
            dirty = false
        }
    }

    return ReorderUiState(
        items = localItems,
        reorderableState = reorderableState,
        persistNow = {
            persistJob.value?.cancel()
            if (dirty) {
                dirty = false
                persist(localItems)
            }
        },
    )
}

data class ReorderUiState<T>(
    /** 当前展示顺序（拖动时同步更新，不依赖外部状态回流） */
    val items: List<T>,
    val reorderableState: ReorderableLazyListState,
    /** 立即把当前顺序落盘一次，供 onDragStopped 调用 */
    val persistNow: () -> Unit,
)

private const val PERSIST_DEBOUNCE_MS = 500L
