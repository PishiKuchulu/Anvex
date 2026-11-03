package com.anvex.app.ui.components

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
    // 2026-11-03 audit: the status line always used the primary (success)
    // color, so a failed save was announced in the success color. Success
    // vs failure is now carried as state, not inferred from the wording.
    var statusIsError by remember { mutableStateOf(false) }
    // Status strings are resolved here, in the composable body — the
    // click handlers below run in coroutine lambdas where tr() (a
    // @Composable) can never be called.
    val savedLabel = tr("Saved")
    val saveFailedLabel = tr("Save failed")
    val openFailedLabel = tr("Open failed")
    val shareFailedLabel = tr("Share failed")
    val removedText = tr("The file was removed.")
    val noViewerText = tr("No viewer for this file type — use Save or Share.")
    val shareLabel = tr("Share")
    val fileLabel = tr("File")
    // In-app viewer for text deliverables (user request 2026-11-02): until now
    // the card offered only Save/Share, so tapping a report showed nothing.
    var viewerText by remember { mutableStateOf<String?>(null) }
    var viewerMarkdown by remember { mutableStateOf(false) }
    var viewerMono by remember { mutableStateOf(false) }
    var viewerTruncated by remember { mutableStateOf(false) }
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
                    Text(displayName.ifBlank { fileLabel }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (failed) (record.note ?: tr("Could not create the file"))
                        else "${record.command?.uppercase().orEmpty()} · ${formatBytes(record.bytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!failed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GradientButton(
                        text = tr("Save"),
                        icon = Icons.Filled.Download,
                        onClick = {
                            scope.launch {
                                val result = runCatching {
                                    withContext(Dispatchers.IO) {
                                        val a = manager.find(projectId, stored) ?: error(removedText)
                                        manager.saveToDownloads(a)
                                    }
                                }
                                statusIsError = result.isFailure
                                status = result.fold(
                                    { "$savedLabel: $it" },
                                    { "$saveFailedLabel: ${it.message}" },
                                )
                            }
                        },
                    )
                    OutlinedButton(onClick = {
                        scope.launch {
                            runCatching {
                                val a = withContext(Dispatchers.IO) {
                                    manager.find(projectId, stored) ?: error(removedText)
                                }
                                when (a.format) {
                                    com.anvex.core.engine.ArtifactFormat.MD,
                                    com.anvex.core.engine.ArtifactFormat.TXT,
                                    com.anvex.core.engine.ArtifactFormat.JSON -> {
                                        val full = withContext(Dispatchers.IO) {
                                            a.file.readText(Charsets.UTF_8)
                                        }
                                        // 2026-11-03 audit: the 400k cap
                                        // used to cut silently mid-text;
                                        // the viewer now says so (footer).
                                        viewerTruncated = full.length > 400_000
                                        viewerMarkdown = a.format == com.anvex.core.engine.ArtifactFormat.MD
                                        viewerMono = a.format == com.anvex.core.engine.ArtifactFormat.JSON
                                        viewerText = full.take(400_000)
                                    }
                                    com.anvex.core.engine.ArtifactFormat.PDF,
                                    com.anvex.core.engine.ArtifactFormat.MP4 -> {
                                        val view = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(manager.shareUri(a), a.mimeType)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(view)
                                    }
                                    else -> error(noViewerText)
                                }
                            }.onFailure {
                                statusIsError = true
                                status = "$openFailedLabel: ${it.message}"
                            }
                        }
                    }) {
                        Icon(Icons.Filled.Visibility, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Open"))
                    }
                    OutlinedButton(onClick = {
                        runCatching {
                            val a = manager.find(projectId, stored) ?: error(removedText)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = a.mimeType
                                putExtra(Intent.EXTRA_STREAM, manager.shareUri(a))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, shareLabel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure {
                            statusIsError = true
                            status = "$shareFailedLabel: ${it.message}"
                        }
                    }) {
                        Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Share"))
                    }
                }
            }
            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (statusIsError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    viewerText?.let { text ->
        Dialog(
            onDismissRequest = { viewerText = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            displayName.ifBlank { fileLabel },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewerText = null }) {
                            Icon(Icons.Filled.Close, contentDescription = tr("Close"))
                        }
                    }
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    ) {
                        if (viewerMarkdown) {
                            MarkdownText(text)
                        } else {
                            Text(
                                text,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = if (viewerMono) FontFamily.Monospace else FontFamily.Default,
                                ),
                            )
                        }
                        if (viewerTruncated) {
                            Text(
                                tr("Preview truncated — open the saved file to see everything."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(b: Long): String = when {
    b >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", b / (1024.0 * 1024.0))
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}
