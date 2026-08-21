package me.rerere.rikkahub.ui.hooks

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity

@Composable
fun ImeLazyListAutoScroller(
    lazyListState: LazyListState,
) {
    val ime = WindowInsets.ime
    val localDensity = LocalDensity.current
    LaunchedEffect(lazyListState) {
        var prevImeBottom = ime.getBottom(localDensity)
        snapshotFlow {
            ime.getBottom(localDensity)
        }.collect { imeBottom ->
            val delta = imeBottom - prevImeBottom
            prevImeBottom = imeBottom
            // 与上游一致：键盘高度变化多少，列表就同步滚动多少。
            // 不论是否钉在底部，可视内容都会跟随键盘一起上移/下移，而不是被截断。
            if (delta != 0 && !lazyListState.isScrollInProgress) {
                lazyListState.scrollBy(delta.toFloat())
            }
        }
    }
}
