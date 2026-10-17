package com.anvex.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * v4.0 audit fix: destructive Clear/Delete actions route through an explicit
 * confirmation dialog. DestructiveButton colors live at the call site.
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String = "Confirm",
    dismissLabel: String = "Cancel",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = if (destructive) androidx.compose.material3.MaterialTheme.colorScheme.error
                    else androidx.compose.material3.MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}
