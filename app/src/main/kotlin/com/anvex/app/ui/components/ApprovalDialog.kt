package com.anvex.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import com.anvex.app.i18n.tr
import com.anvex.core.android.runtime.pi.PiToolMapping

/**
 * Shared approval dialog for agent actions that need a human decision —
 * one component for BOTH engines so the fail-closed contract and wording
 * can never drift between them:
 *
 * - Pi engine: rendered from [PiToolMapping.ApprovalAsk] (ChatScreen
 *   previously inlined this as a raw AlertDialog).
 * - Native engine: `ChatViewModel.pendingApproval` (a summary string,
 *   answered via `answerApproval(Boolean)`, fail-closed on clear) maps
 *   onto the primary overload directly.
 * - MCP tool calls: summary + the raw arguments as [detail].
 *
 * Fail-closed by construction: dismissing (back button, outside tap)
 * answers `false` — exactly like pressing Deny.
 */
@Composable
fun ApprovalDialog(
    summary: String,
    detail: String? = null,
    onAnswer: (Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(tr("Approval required")) },
        text = {
            Column {
                Text(summary, style = MaterialTheme.typography.bodyMedium)
                if (!detail.isNullOrBlank()) {
                    Text(
                        detail,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 6,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }) { Text(tr("Allow")) }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }) { Text(tr("Deny")) }
        },
    )
}

/** Pi-engine form: the ask already carries a human-readable [PiToolMapping.ApprovalAsk.summary]. */
@Composable
fun ApprovalDialog(
    ask: PiToolMapping.ApprovalAsk,
    onAnswer: (Boolean) -> Unit,
) {
    ApprovalDialog(summary = ask.summary, detail = null, onAnswer = onAnswer)
}
