package com.anvex.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anvex.core.engine.QuickAction

/**
 * Renders the model-emitted `<bolt-quick-actions>` buttons.
 *
 * Wiring gap 5 (2026-11-03 audit): the parser → ChatSession pipeline has
 * always filled `ChatSession.UiState.quickActions`, but no composable ever
 * read the state, so the block was silently swallowed. This row is the
 * rendering half; the chat screens place it under the streaming/final
 * bubble and route taps by [QuickAction.type]:
 * - "message" → send [QuickAction.message] as a new user turn;
 * - "link"    → open [QuickAction.href];
 * - anything else (e.g. file actions) → open [QuickAction.path].
 * The component itself stays dumb: it only reports the tap.
 */
@Composable
fun QuickActionsRow(
    actions: List<QuickAction>,
    onAction: (QuickAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.forEach { action ->
            NebulaChip(
                label = action.label,
                selected = false,
                onClick = { onAction(action) },
            )
        }
    }
}
