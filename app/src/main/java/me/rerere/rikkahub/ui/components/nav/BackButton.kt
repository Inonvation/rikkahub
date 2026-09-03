package me.rerere.rikkahub.ui.components.nav

import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.rememberHaptic
import me.rerere.rikkahub.ui.theme.CustomColors

@Composable
fun BackButton(
    modifier: Modifier = Modifier,
    /** 自定义返回行为(如脏状态拦截确认); 传 null 时执行默认 popBackStack */
    onClick: (() -> Unit)? = null,
) {
    val navController = LocalNavController.current
    val hapticController = rememberHaptic()
    FilledTonalIconButton(
        onClick = {
            hapticController.lightTap()
            if (onClick != null) onClick() else navController.popBackStack()
        },
        modifier = modifier,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = CustomColors.listItemColors.containerColor),
    ) {
        Icon(
            imageVector = HugeIcons.ArrowLeft01,
            contentDescription = stringResource(R.string.back)
        )
    }
}
