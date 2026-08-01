package me.rerere.rikkahub.ui.components.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import com.petterp.floatingx.FloatingX
import com.petterp.floatingx.assist.FxGravity
import com.petterp.floatingx.listener.control.IFxAppControl
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.RikkahubTheme

@Composable
fun FloatingWindow(
    tag: String,
    visibility: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    var window: IFxAppControl? by remember { mutableStateOf(null) }

    LaunchedEffect(visibility) {
        if (visibility) {
            window?.show()
        } else {
            window?.hide()
        }
    }

    DisposableEffect(context) {
        window = FloatingX.install {
            setTag(tag)
            setContext(context)
            setGravity(FxGravity.LEFT_OR_BOTTOM)
            setOffsetXY(20f, -20f)
            setEnableAnimation(true)
            setLayoutView(ComposeView(context).apply {
                setContent {
                    RikkahubTheme {
                        CompositionLocalProvider(LocalSettings provides settings) {
                            content()
                        }
                    }
                }
            })
        }
        if (visibility) window?.show() else window?.hide()
        onDispose {
            window?.cancel()
        }
    }
}
