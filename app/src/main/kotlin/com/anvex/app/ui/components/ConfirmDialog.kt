package com.anvex.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.anvex.app.i18n.tr

/**
 * v4.0 audit fix: destructive Clear/Delete actions route through an explicit
 * confirmation dialog. DestructiveButton colors live at the call site.
 *
 * 2026-11-03 audit: the default labels were hard-coded English, so any call
 * site that forgot to pass labels shipped English buttons in Persian mode.
 * Labels are now nullable and resolved through [tr] inside the composable.
 * A destructive confirm is a filled error-container button, not just
 * red text, so irreversible actions carry real visual weight.
 */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String? = null,
    dismissLabel: String? = null,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val confirmText = confirmLabel ?: tr("Confirm")
    val dismissText = dismissLabel ?: tr("Cancel")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            if (destructive) {
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) { Text(confirmText) }
            } else {
                TextButton(onClick = onConfirm) {
                    Text(confirmText, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissText) } },
    )
}
