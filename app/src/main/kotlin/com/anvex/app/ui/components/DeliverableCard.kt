package com.anvex.app.ui.components

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.anvex.core.android.AppGraph
import com.anvex.core.model.ActionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.anvex.app.i18n.tr

/**
 * A real file produced by the agent (ZIP / PDF / MD / …). One tap saves it to Downloads,
 * another opens the share sheet — no browser detour, no HTML page in between.
 */
@Composable
fun DeliverableCard(projectId: String, record: ActionRecord, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { AppGraph.get().artifactManager }
    var status by remember { mutableStateOf<String?>(null) }
    val stored = record.filePath.orEmpty()
    val failed = record.status == "FAILED"
    val displayName = if (stored.length > 37 && stored[36] == '-') stored.substring(37) else stored
    val icon = when (record.command?.lowercase()) {
        "zip" -> Icons.Filled.FolderZip
        "pdf" -> Icons.Filled.PictureAsPdf
        "mp4" -> Icons.Filled.Movie
        else -> Icons.Filled.Description
    }
    GlassCard(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GradientBadge(icon, size = 40)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(displayName.ifBlank { "File" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (failed) (record.note ?: "Could not create the file")
                        else "${record.command?.uppercase().orEmpty()} · ${formatBytes(record.bytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!failed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GradientButton(
                        text = "Save",
                        icon = Icons.Filled.Download,
                        onClick = {
                            scope.launch {
                                status = runCatching {
                                    withContext(Dispatchers.IO) {
                                        val a = manager.find(projectId, stored) ?: error("The file was removed.")
                                        manager.saveToDownloads(a)
                                    }
                                }.fold({ "Saved to $it" }, { "Save failed: ${it.message}" })
                            }
                        },
                    )
                    OutlinedButton(onClick = {
                        runCatching {
                            val a = manager.find(projectId, stored) ?: error("The file was removed.")
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = a.mimeType
                                putExtra(Intent.EXTRA_STREAM, manager.shareUri(a))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure { status = "Share failed: ${it.message}" }
                    }) {
                        Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Share"))
                    }
                }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

private fun formatBytes(b: Long): String = when {
    b >= 1024 * 1024 -> "%.1f MB".format(b / (1024.0 * 1024.0))
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}
