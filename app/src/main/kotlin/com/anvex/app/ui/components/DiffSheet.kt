package com.anvex.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anvex.core.engine.DiffEngine
import com.anvex.app.i18n.tr

/**
 * Change review: unified-style line diff with revert / open-in-editor actions
 * (brief §22 — AI changes are traceable and reversible, never silent).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiffSheet(
    path: String,
    before: String?,
    after: String?,
    operation: com.anvex.core.model.MutationOp? = null,
    renamedTo: String? = null,
    onRevert: () -> Unit,
    onOpenEditor: () -> Unit,
    onDismiss: () -> Unit,
) {
    val lines = remember(path, before, after) {
        DiffEngine.diff(before ?: "", after ?: "")
    }
    val added = lines.count { it.op == DiffEngine.Op.INSERT }
    val removed = lines.count { it.op == DiffEngine.Op.DELETE }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                path,
                fontFamily = com.anvex.app.ui.theme.CodeFont,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            val opLabel = when (operation) {
                com.anvex.core.model.MutationOp.CREATE -> "  · ${tr("new file")}"
                com.anvex.core.model.MutationOp.DELETE -> "  · ${tr("deleted")}"
                com.anvex.core.model.MutationOp.RENAME -> "  · ${tr("renamed")}"
                else -> if (before == null) "  · ${tr("new file")}" else ""
            }
            Text(
                "+$added  -$removed$opLabel" +
                    (renamedTo?.let { "  → $it" } ?: ""),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            // 2026-11-03 audit: lines used to wrap mid-token. The list
            // now sits in a horizontally scrollable box sized to the
            // longest line (12sp mono ≈ 7.2dp per char), so long lines
            // stay intact and scroll as one surface; the LazyColumn
            // itself keeps a bounded width.
            val longestLine = remember(lines) { lines.maxOfOrNull { it.text.length } ?: 0 }
            val contentWidth = ((longestLine + 2) * 7.2f).dp.coerceAtLeast(296.dp)
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .width(contentWidth)
                        .height(420.dp),
                ) {
                    items(lines, key = { "${it.oldLine}:${it.newLine}:${it.op}" }) { line ->
                        DiffLineRow(line)
                    }
                }
            }
            // v4.4.5 (UX): scrollable — Persian labels are long and the third
            // button was pushed off-screen on narrow phones.
            Row(modifier = Modifier.padding(vertical = 12.dp).horizontalScroll(rememberScrollState())) {
                if (operation != com.anvex.core.model.MutationOp.CREATE || before != null) {
                    TextButton(onClick = onRevert) { Text(tr("Revert this change")) }
                }
                TextButton(onClick = onOpenEditor) { Text(tr("Open in editor")) }
                TextButton(onClick = onDismiss) { Text(tr("Close")) }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffEngine.DiffLine) {
    // 2026-11-03 audit: colors were hard-coded constants (a light-green
    // insert on light themes was nearly unreadable, and this sheet looked
    // like a different app next to ReviewChanges). Both diff surfaces now
    // source the same theme roles ReviewChanges uses: tertiary = added,
    // error = removed, with matching alpha washes. The font is the
    // bundled mono (CodeFont), like every other code surface.
    val scheme = MaterialTheme.colorScheme
    val bg: Color?
    val fg: Color
    val prefix: String
    when (line.op) {
        DiffEngine.Op.EQUAL -> {
            bg = null; fg = Color.Unspecified; prefix = " "
        }
        DiffEngine.Op.INSERT -> {
            bg = scheme.tertiary.copy(alpha = 0.13f); fg = scheme.tertiary; prefix = "+"
        }
        DiffEngine.Op.DELETE -> {
            bg = scheme.error.copy(alpha = 0.11f); fg = scheme.error; prefix = "-"
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (bg != null) Modifier.background(bg) else Modifier),
    ) {
        Text(
            prefix,
            fontFamily = com.anvex.app.ui.theme.CodeFont,
            fontSize = 12.sp,
            color = if (fg != Color.Unspecified) fg else scheme.onSurfaceVariant,
        )
        Text(
            line.text,
            fontFamily = com.anvex.app.ui.theme.CodeFont,
            fontSize = 12.sp,
            softWrap = false,
            color = if (fg != Color.Unspecified) fg else scheme.onSurface,
        )
    }
}
