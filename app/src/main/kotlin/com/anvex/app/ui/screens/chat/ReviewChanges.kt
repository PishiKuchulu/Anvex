package com.anvex.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.anvex.app.i18n.tr
import com.anvex.app.ui.theme.CodeFont
import com.anvex.core.engine.BatchEntryStatus
import com.anvex.core.engine.BatchMutation
import com.anvex.core.engine.SensitivePaths
import com.anvex.core.model.MutationOp

private enum class DiffKind { CONTEXT, ADD, DEL }
private data class DiffLine(val kind: DiffKind, val text: String, val oldNo: Int?, val newNo: Int?)

/**
 * Line diff via LCS. Sides are capped; beyond the cap the view falls back
 * to a whole-block removal + addition listing so a huge generated file can
 * never blow up the table.
 */
private fun computeDiff(before: String?, after: String?): List<DiffLine> {
    val a = before?.lines() ?: emptyList()
    val b = after?.lines() ?: emptyList()
    if (a.size > 600 || b.size > 600) {
        val out = ArrayList<DiffLine>()
        a.forEachIndexed { i, line -> out += DiffLine(DiffKind.DEL, line, i + 1, null) }
        b.forEachIndexed { j, line -> out += DiffLine(DiffKind.ADD, line, null, j + 1) }
        return out
    }
    val dp = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.size - 1 downTo 0) {
        for (j in b.size - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
    }
    val out = ArrayList<DiffLine>()
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        when {
            a[i] == b[j] -> {
                out += DiffLine(DiffKind.CONTEXT, a[i], i + 1, j + 1); i++; j++
            }
            dp[i + 1][j] >= dp[i][j + 1] -> {
                out += DiffLine(DiffKind.DEL, a[i], i + 1, null); i++
            }
            else -> {
                out += DiffLine(DiffKind.ADD, b[j], null, j + 1); j++
            }
        }
    }
    while (i < a.size) { out += DiffLine(DiffKind.DEL, a[i], i + 1, null); i++ }
    while (j < b.size) { out += DiffLine(DiffKind.ADD, b[j], null, j + 1); j++ }
    return out
}

@Composable
private fun DiffLineRow(line: DiffLine) {
    val bg = when (line.kind) {
        DiffKind.ADD -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.13f)
        DiffKind.DEL -> MaterialTheme.colorScheme.error.copy(alpha = 0.11f)
        DiffKind.CONTEXT -> MaterialTheme.colorScheme.background
    }
    val marker = when (line.kind) {
        DiffKind.ADD -> "+"
        DiffKind.DEL -> "−"
        DiffKind.CONTEXT -> " "
    }
    Row(
        modifier = Modifier.fillMaxWidth().background(bg)
            .padding(horizontal = 12.dp, vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            (line.newNo ?: line.oldNo)?.toString() ?: "",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp),
        )
        Text(
            marker,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont),
            color = when (line.kind) {
                DiffKind.ADD -> MaterialTheme.colorScheme.tertiary
                DiffKind.DEL -> MaterialTheme.colorScheme.error
                DiffKind.CONTEXT -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.width(14.dp),
        )
        Text(
            line.text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFont),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Atelier "Review changes" screen (design screen 04), upgraded to the F4
 * batch composer: the last response's recorded mutations as a full-screen
 * accordion — every file expandable to its real line diff, an explicit
 * per-file decision (Pending / Kept / Reverted), and global Accept All /
 * Reject All in the sticky bottom bar. Changes in Anvex are applied live
 * and journaled, so "accept" records the keep decision and "reject" runs
 * the journal revert; a file is marked Reverted only when its revert
 * actually succeeded. Nothing on this screen is decorative.
 *
 * Decisions are local review state keyed by [BatchMutation.batchId]: a new
 * turn (a new batch id) starts undecided again. The journal itself is
 * never purged here — it also backs Version History.
 */
@Composable
fun ReviewChangesSheet(
    batch: BatchMutation,
    initialPath: String?,
    onRevert: (String, (Boolean) -> Unit) -> Unit,
    onOpenEditor: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val changes = remember(batch) { batch.entries.map { it.mutation } }
    var decisions by remember(batch.batchId) {
        mutableStateOf(batch.entries.associate { it.path to it.status })
    }
    var expandedPath by remember(batch.batchId) {
        mutableStateOf(initialPath ?: batch.entries.firstOrNull()?.path)
    }
    val anySensitive = changes.any {
        SensitivePaths.isSensitive(it.path) ||
            (it.renamedTo?.let { dest -> SensitivePaths.isSensitive(dest) } == true)
    }
    fun statusOf(path: String): BatchEntryStatus = decisions[path] ?: BatchEntryStatus.PENDING
    val pendingPaths = batch.entries
        .filter { statusOf(it.path) == BatchEntryStatus.PENDING }
        .map { it.path }
    val anyRevertiblePending = batch.entries.any {
        statusOf(it.path) == BatchEntryStatus.PENDING && it.revertible
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(tr("Review changes"), style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${changes.size} ${tr("files changed")}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = tr("Close"))
                    }
                }
                if (batch.droppedCount > 0) {
                    // MR-3 (audit round 2): the engine journal evicted its
                    // oldest entries at the log bounds — say so instead of
                    // silently presenting a journal that begins mid-turn.
                    Text(
                        tr("Journal trimmed: %1\$d earlier change(s) not shown").format(batch.droppedCount),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Sensitive-file notice (design: "no sensitive file changed"
                // reassurance, or a warning when one did change).
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (anySensitive) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                ) {
                    Text(
                        if (anySensitive) tr("A sensitive file changed")
                        else tr("No sensitive file changed"),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (anySensitive) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (batch.entries.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            tr("No changes to review"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(batch.entries, key = { it.path + (it.mutation.renamedTo ?: "") }) { entry ->
                            val change = entry.mutation
                            val status = statusOf(entry.path)
                            val expanded = expandedPath == entry.path
                            val diff = remember(change) { computeDiff(change.before, change.after) }
                            val adds = diff.count { it.kind == DiffKind.ADD }
                            val dels = diff.count { it.kind == DiffKind.DEL }
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { expandedPath = if (expanded) null else entry.path }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    when (change.operation) {
                                        MutationOp.CREATE -> "+"
                                        MutationOp.DELETE -> "−"
                                        MutationOp.RENAME -> "→"
                                        MutationOp.MODIFY -> "~"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        change.path.substringAfterLast('/'),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        change.path,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = CodeFont),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    "+$adds",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "−$dels",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.width(10.dp))
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = when (status) {
                                        BatchEntryStatus.PENDING -> MaterialTheme.colorScheme.surfaceVariant
                                        BatchEntryStatus.KEPT -> MaterialTheme.colorScheme.tertiaryContainer
                                        BatchEntryStatus.REVERTED -> MaterialTheme.colorScheme.errorContainer
                                    },
                                ) {
                                    Text(
                                        when (status) {
                                            BatchEntryStatus.PENDING -> tr("Pending")
                                            BatchEntryStatus.KEPT -> tr("Kept")
                                            BatchEntryStatus.REVERTED -> tr("Reverted")
                                        },
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                Icon(
                                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (expanded) {
                                diff.forEach { line -> DiffLineRow(line) }
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (status == BatchEntryStatus.PENDING) {
                                        TextButton(onClick = {
                                            decisions = decisions + (entry.path to BatchEntryStatus.KEPT)
                                        }) { Text(tr("Keep")) }
                                        TextButton(
                                            onClick = {
                                                onRevert(entry.path) { ok ->
                                                    if (ok) decisions =
                                                        decisions + (entry.path to BatchEntryStatus.REVERTED)
                                                }
                                            },
                                            enabled = entry.revertible,
                                        ) {
                                            Text(tr("Revert this file"), color = MaterialTheme.colorScheme.error)
                                        }
                                        if (!entry.revertible) {
                                            Text(
                                                tr("Not revertible"),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = { onOpenEditor(entry.path) }) {
                                        Text(tr("Open in editor"))
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            // Reject All: revert every pending revertible
                            // file; each entry flips to REVERTED only when
                            // its own revert reports success.
                            batch.entries
                                .filter {
                                    statusOf(it.path) == BatchEntryStatus.PENDING && it.revertible
                                }
                                .forEach { entry ->
                                    onRevert(entry.path) { ok ->
                                        if (ok) decisions =
                                            decisions + (entry.path to BatchEntryStatus.REVERTED)
                                    }
                                }
                        },
                        enabled = anyRevertiblePending,
                    ) {
                        Text(tr("Reject all"), color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            decisions = decisions + pendingPaths.associateWith { BatchEntryStatus.KEPT }
                        },
                        enabled = pendingPaths.isNotEmpty(),
                    ) { Text(tr("Accept all")) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onDismiss) { Text(tr("Done")) }
                }
            }
        }
    }
}
