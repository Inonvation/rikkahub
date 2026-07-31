package me.rerere.rikkahub.ui.components.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import me.rerere.rikkahub.ui.hooks.rememberHaptic

@Composable
fun RikkaConfirmDialog(
    show: Boolean,
    title: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    text: @Composable () -> Unit,
) {
    if (!show) {
        return
    }

    val hapticController = rememberHaptic()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text,
        confirmButton = {
            TextButton(onClick = {
                hapticController.perform(HapticFeedbackType.KeyboardTap)
                onConfirm()
            }) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = {
                hapticController.perform(HapticFeedbackType.KeyboardTap)
                onDismiss()
            }) {
                Text(dismissText)
            }
        }
    )
}
