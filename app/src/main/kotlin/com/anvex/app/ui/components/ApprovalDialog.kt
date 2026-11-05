package com.anvex.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.anvex.app.i18n.tr
import com.anvex.core.android.runtime.pi.PiToolMapping
import com.anvex.core.engine.BoltAction

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
 *
 * H-03 (external audit, 2026-11-05): the detail used to be hard-capped
 * at 6 lines, so the user approved commands and file writes whose
 * payload they could not actually see. The body now scrolls (bounded
 * height) and the detail renders in full — the policy side raised its
 * preview cap to match (ActionSafetyPolicy.MAX_DETAIL_CHARS).
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
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(summary, style = MaterialTheme.typography.bodyMedium)
                if (!detail.isNullOrBlank()) {
                    Text(
                        detail,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
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
    ApprovalDialog(summary = ask.summary, detail = approvalDetailOf(ask.action), onAnswer = onAnswer)
}

/**
 * The full payload behind a Pi ask, for the scrollable detail area:
 * until now Pi asks showed only the 300-char summary line, so a long
 * bash command or file write was approved sight unseen (H-03). The
 * rendering clip is generous (20k chars) — it exists to keep a
 * pathological payload from breaking composition, not to hide content.
 */
private fun approvalDetailOf(action: BoltAction): String? {
    val raw = when (action) {
        is BoltAction.ShellAction, is BoltAction.StartAction -> action.content
        is BoltAction.FileAction -> action.filePath + "\n" + action.content
        is BoltAction.ArtifactAction -> action.content
        is BoltAction.ToolAction -> (action.argument + "\n" + action.content).trim()
        is BoltAction.UnknownAction -> action.content
    }
    if (raw.isBlank()) return null
    return if (raw.length > 20_000) raw.take(20_000) + "…" else raw
}
