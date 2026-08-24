package me.rerere.rikkahub.ui.modifier

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import me.rerere.rikkahub.ui.hooks.rememberHaptic

@Composable
fun Modifier.onClick(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val hapticController = rememberHaptic()
    return this.then(Modifier.clickable(
        onClick = {
            hapticController.lightTap()
            onClick()
        },
        interactionSource = remember { MutableInteractionSource() },
        indication = LocalIndication.current,
        role = Role.Button,
    ))
}
