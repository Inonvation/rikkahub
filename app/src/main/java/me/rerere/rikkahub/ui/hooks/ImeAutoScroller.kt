package me.rerere.rikkahub.ui.hooks

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import me.rerere.rikkahub.ui.pages.chat.isChatListPinnedToBottom

@Composable
fun ImeLazyListAutoScroller(
    lazyListState: LazyListState,
) {
    val ime = WindowInsets.ime
    val localDensity = LocalDensity.current
    var imeHeigh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        snapshotFlow {
            ime.getBottom(localDensity)
        }.collect { keyboardHeight ->
            if (keyboardHeight > 0) {
                // 仅当用户已停在列表底部时才随键盘下滚；阅读历史消息时调出输入法不打断
                val layout = lazyListState.layoutInfo
                val lastItem = layout.visibleItemsInfo.lastOrNull()
                val isPinnedToBottom = isChatListPinnedToBottom(
                    totalItemsCount = layout.totalItemsCount,
                    lastVisibleIndex = lastItem?.index,
                    lastItemEnd = lastItem?.let { it.offset + it.size },
                    viewportEnd = layout.viewportEndOffset,
                    afterContentPadding = layout.afterContentPadding,
                )
                if (!lazyListState.isScrollInProgress && isPinnedToBottom) {
                    lazyListState.scrollBy((keyboardHeight - imeHeigh).toFloat())
                }
                imeHeigh = keyboardHeight
            } else {
                imeHeigh = 0
            }
        }
    }
}
