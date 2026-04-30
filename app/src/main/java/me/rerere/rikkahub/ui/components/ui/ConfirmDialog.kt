package me.rerere.rikkahub.ui.components.ui

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

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

    val hapticFeedback = LocalHapticFeedback.current  // ← 新增

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = text,
        confirmButton = {
            TextButton(onClick = {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress) // ← 新增
                onConfirm()
            }) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress) // ← 新增
                onDismiss()
            }) {
                Text(dismissText)
            }
        }
    )
}