package com.anvex.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anvex.app.i18n.tr
import com.anvex.core.android.AppGraph
import com.anvex.core.android.runtime.rag.RagModelState
import kotlinx.coroutines.launch

/**
 * P7 (F6): per-project Code search (local RAG) controls — enable
 * toggle (default off), embedding-model download (~23 MB, on first
 * enable), index actions, and the stored-chunk count. Everything here
 * is wired to RagManager; there are no decorative controls.
 */
@Composable
fun CodeSearchDialog(projectId: String, onDismiss: () -> Unit) {
    val manager = remember { AppGraph.get().androidRuntime.ragManager() }
    if (manager == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(tr("Code search")) },
            text = { Text(tr("Code search is unavailable on this device.")) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close")) } },
        )
        return
    }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var chunks by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val modelState by manager.models.state.collectAsState()

    LaunchedEffect(projectId) {
        enabled = manager.isEnabledNow(projectId)
        chunks = manager.chunkCount(projectId)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Code search")) },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("Semantic code search for this project"),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = enabled,
                        enabled = !busy,
                        onCheckedChange = { want ->
                            busy = true
                            scope.launch {
                                manager.setEnabled(projectId, want)
                                enabled = want
                                if (want) {
                                    val ready = manager.models.ensureModel()
                                    if (ready) {
                                        manager.enqueueIndexNow(projectId)
                                        note = tr("Indexing queued.")
                                    } else {
                                        note = tr("Model download failed.")
                                    }
                                }
                                chunks = manager.chunkCount(projectId)
                                busy = false
                            }
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                when (val state = modelState) {
                    RagModelState.Ready ->
                        Text(tr("Embedding model: ready"), style = MaterialTheme.typography.bodySmall)
                    RagModelState.NotDownloaded -> {
                        Text(
                            tr("Embedding model: not downloaded (~23 MB, one time)"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(
                            onClick = {
                                busy = true
                                scope.launch {
                                    manager.models.ensureModel()
                                    busy = false
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.padding(top = 6.dp),
                        ) { Text(tr("Download model")) }
                    }
                    is RagModelState.Downloading -> {
                        Text(
                            tr("Downloading model… %1\$d%%").format(state.percent),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        LinearProgressIndicator(
                            progress = { state.percent / 100f },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                    is RagModelState.Failed ->
                        Text(
                            tr("Model error: ") + state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    tr("Indexed chunks: %1\$d").format(chunks),
                    style = MaterialTheme.typography.bodySmall,
                )
                note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        manager.enqueueIndexNow(projectId)
                        note = tr("Indexing queued.")
                    },
                    enabled = enabled && modelState is RagModelState.Ready && !busy,
                ) { Text(tr("Index now")) }
                TextButton(
                    onClick = {
                        scope.launch {
                            manager.clearProject(projectId)
                            chunks = 0
                            note = tr("Index cleared.")
                        }
                    },
                    enabled = chunks > 0 && !busy,
                ) { Text(tr("Clear index")) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Close")) }
        },
    )
}
