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

@Composable
fun ImeLazyListAutoScroller(
    lazyListState: LazyListState,
    reverseLayout: Boolean = false,
) {
    val ime = WindowInsets.ime
    val localDensity = LocalDensity.current
    var imeHeigh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        snapshotFlow {
            ime.getBottom(localDensity)
        }.collect { keyboardHeight ->
            if (reverseLayout) {
                // reverseLayout 列表底部天然锚定在 IME 上方，无需补偿滚动
                return@collect
            }
            if (keyboardHeight > 0) {
                // 手势进行中跳过补偿滚动，避免与用户滚动抢帧（手势结束后由跟随/布局自然调整）
                if (!lazyListState.isScrollInProgress) {
                    lazyListState.scrollBy((keyboardHeight - imeHeigh).toFloat())
                }
                imeHeigh = keyboardHeight
            } else {
                imeHeigh = 0
            }
        }
    }
}
