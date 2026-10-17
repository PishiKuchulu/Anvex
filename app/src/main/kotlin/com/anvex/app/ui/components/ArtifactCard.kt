package com.anvex.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anvex.core.android.session.ChatSession
import com.anvex.core.model.ActionStatus
import com.anvex.app.i18n.tr

/**
 * One snapshot of a file reconstructed from the mutation journal
 * (architect review item 3 — artifact version history).
 */
data class FileVersion(
    /** 1-based version number, oldest first. */
    val index: Int,
    /** Content of this version; null = no content (file not yet created / deleted). */
    val content: String?,
    /** When the journal entry that produced this version was recorded. */
    val timestamp: Long,
    /** Journal operation that produced this version (CREATE/MODIFY/DELETE/RENAME). */
    val operation: String,
    /** True for the newest journal state of the file. */
    val isCurrent: Boolean,
)

/** Result of a "Restore this version" action, shown inside [VersionSheet]. */
data class RestoreOutcome(val ok: Boolean, val detail: String? = null)

/**
 * Live artifact card — the mobile equivalent of bolt.diy's Artifact component:
 * title, ordered actions, per-action status, streaming byte counts (brief §8/§21).
 *
 * Architect review item 3: when the mutation journal holds multiple versions of a
 * file touched by this artifact, a v1/v2/v3 chip row lets the user open any
 * version read-only in [VersionSheet].
 */
@Composable
fun ArtifactCard(
    artifact: ChatSession.ArtifactUi,
    versionsFor: (String) -> List<FileVersion> = { emptyList() },
    onShowVersion: (path: String, versionIndex: Int) -> Unit = { _, _ -> },
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    artifact.title.ifBlank { "Project update" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
            artifact.actions.forEach { action ->
                ActionRow(action)
                val path = action.filePath
                if (action.type == "file" && path != null) {
                    VersionChipRow(path = path, versionsFor = versionsFor, onShowVersion = onShowVersion)
                }
            }
        }
    }
}

/**
 * v1/v2/… chip row for one file path — shown only when the journal recorded more
 * than one version. Tapping a chip opens that version read-only.
 */
@Composable
fun VersionChipRow(
    path: String,
    versionsFor: (String) -> List<FileVersion>,
    onShowVersion: (path: String, versionIndex: Int) -> Unit,
) {
    val versions = remember(path) { versionsFor(path) }
    if (versions.size < 2) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        versions.forEach { version ->
            FilterChip(
                selected = version.isCurrent,
                onClick = { onShowVersion(path, version.index) },
                label = {
                    Text(
                        if (version.isCurrent) "v${version.index} · ${tr("current")}" else "v${version.index}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
            )
        }
    }
}

/**
 * Read-only version viewer (architect review item 3): chip selector, the selected
 * version's content, and a "Restore this version" action. Viewing never touches
 * the file; restoring re-applies the historical content through the journaled
 * write path so the restore itself stays revertible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionSheet(
    path: String,
    versions: List<FileVersion>,
    initialVersion: Int,
    restoreOutcome: RestoreOutcome? = null,
    onRestore: (FileVersion) -> Unit,
    onOpenEditor: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(path, versions.size) {
        mutableStateOf(initialVersion.coerceIn(1, versions.size.coerceAtLeast(1)))
    }
    val version = versions.firstOrNull { it.index == selected }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                path,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                "${tr("Version history")} · ${versions.size}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (versions.isEmpty()) {
                Text(
                    tr("No recorded versions for this file yet."),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    versions.forEach { v ->
                        FilterChip(
                            selected = v.index == selected,
                            onClick = { selected = v.index },
                            label = {
                                Text(
                                    if (v.isCurrent) "v${v.index} · ${tr("current")}" else "v${v.index}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
                if (version == null || version.content == null) {
                    Text(
                        tr("This version has no content (file was deleted or not yet created)."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                } else {
                    val lines = remember(selected, version.content) { version.content.lines() }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp),
                    ) {
                        items(lines.take(MAX_VERSION_LINES).size, key = { it }) { i ->
                            val line = lines[i]
                            Text(
                                line.ifBlank { " " },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (lines.size > MAX_VERSION_LINES) {
                            item {
                                Text(
                                    "… ${lines.size - MAX_VERSION_LINES} ${tr("more lines — open in editor for the full file")}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
            restoreOutcome?.let { outcome ->
                val text = when {
                    outcome.ok && outcome.detail == null -> tr("Version restored.")
                    outcome.ok -> tr(outcome.detail ?: "")
                    else -> "${tr("Restore failed")}: ${outcome.detail ?: tr("unknown error")}"
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (outcome.ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(modifier = Modifier.padding(vertical = 12.dp)) {
                if (version != null && !version.isCurrent && version.content != null) {
                    TextButton(onClick = { onRestore(version) }) { Text(tr("Restore this version")) }
                }
                TextButton(onClick = onOpenEditor) { Text(tr("Open in editor")) }
                TextButton(onClick = onDismiss) { Text(tr("Close")) }
            }
        }
    }
}

@Composable
fun ActionRow(action: ChatSession.ActionUi) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        val (icon, tint) = when (action.status) {
            ActionStatus.COMPLETE -> Icons.Filled.Check to MaterialTheme.colorScheme.tertiary
            ActionStatus.PARTIAL -> Icons.Filled.RemoveCircleOutline to MaterialTheme.colorScheme.secondary
            ActionStatus.FAILED -> Icons.Filled.Error to MaterialTheme.colorScheme.error
            ActionStatus.ABORTED -> Icons.Filled.Warning to MaterialTheme.colorScheme.secondary
            else -> null to MaterialTheme.colorScheme.onSurfaceVariant
        }
        if (icon != null) {
            Icon(icon, contentDescription = action.status.name, tint = tint, modifier = Modifier.size(16.dp))
        } else {
            Text(tr("•"), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            val filePath = action.filePath
            val command = action.command
            when {
                action.type == "file" && filePath != null -> {
                    Text(
                        filePath,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        action.note ?: "${action.streamingBytes} chars streaming…",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                command != null -> {
                    Text(
                        "$ $command",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 2,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    action.note?.let {
                        Text(
                            it,
                            fontSize = 11.sp,
                            color = if (action.status == ActionStatus.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                else -> Text(action.type, fontSize = 12.sp)
            }
        }
    }
}

private const val MAX_VERSION_LINES = 800
