package com.anvex.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontFamily
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
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            val opLabel = when (operation) {
                com.anvex.core.model.MutationOp.CREATE -> "  · new file"
                com.anvex.core.model.MutationOp.DELETE -> "  · deleted"
                com.anvex.core.model.MutationOp.RENAME -> "  · renamed"
                else -> if (before == null) "  · new file" else ""
            }
            Text(
                "+$added  -$removed$opLabel" +
                    (renamedTo?.let { "  → $it" } ?: ""),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
            ) {
                items(lines, key = { "${it.oldLine}:${it.newLine}:${it.op}" }) { line ->
                    DiffLineRow(line)
                }
            }
            Row(modifier = Modifier.padding(vertical = 12.dp)) {
                if (operation != com.anvex.core.model.MutationOp.CREATE || before != null) {
                    TextButton(onClick = onRevert) { Text(tr("Revert this change")) }
                }
                TextButton(onClick = onOpenEditor) { Text(tr("Open in editor")) }
                TextButton(onClick = onDismiss) { Text(tr("Close")) }
            }
        }
    }
}

private val InsertBg = Color(0x1A3BB273)
private val DeleteBg = Color(0x1ACC5F5F)

private val DeleteFg = Color(0xFFCC5F5F)
// v4.0 fix: InsertFg was dropped from the packaged v3.3.9 source.
private val InsertFg = Color(0xFF6FCF97)

@Composable
private fun DiffLineRow(line: DiffEngine.DiffLine) {
    val bg: Color?
    val fg: Color
    val prefix: String
    when (line.op) {
        DiffEngine.Op.EQUAL -> {
            bg = null; fg = Color.Unspecified; prefix = " "
        }
        DiffEngine.Op.INSERT -> {
            bg = InsertBg; fg = InsertFg; prefix = "+"
        }
        DiffEngine.Op.DELETE -> {
            bg = DeleteBg; fg = DeleteFg; prefix = "-"
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (bg != null) Modifier.background(bg) else Modifier),
    ) {
        Text(
            prefix,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = if (fg != Color.Unspecified) fg else Color.Gray,
        )
        Text(
            line.text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = if (fg != Color.Unspecified) fg else MaterialTheme.colorScheme.onSurface,
        )
    }
}
