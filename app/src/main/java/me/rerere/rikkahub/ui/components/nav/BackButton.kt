package me.rerere.rikkahub.ui.components.nav

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import me.rerere.rikkahub.ui.theme.CustomColors

@Composable
fun BackButton(modifier: Modifier = Modifier) {
    val navController = LocalNavController.current
    val hapticFeedback = LocalHapticFeedback.current  // ← 新增

    FilledTonalIconButton(
        onClick = {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress) // ← 新增
            navController.popBackStack()
        },
        modifier = modifier,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = CustomColors.listItemColors.containerColor
        ),
    ) {
        Icon(
            imageVector = HugeIcons.ArrowLeft01,
            contentDescription = stringResource(R.string.back)
        )
    }
}