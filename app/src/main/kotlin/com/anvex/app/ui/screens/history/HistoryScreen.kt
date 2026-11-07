package com.anvex.app.ui.screens.history

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.anvex.core.android.AppGraph
import com.anvex.core.model.FileMutation
import com.anvex.core.model.MutationOp
import com.anvex.core.model.MutationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.anvex.app.i18n.tr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(nav: NavController, projectId: String) {
    val graph = AppGraph.get(); val scope = rememberCoroutineScope()
    val changes by graph.mutations.observeRecent(projectId).collectAsStateWithLifecycle(initialValue = emptyList())
    var selected by remember { mutableStateOf<FileMutation?>(null) }
    // UI #5: a failed revert used to close the dialog like a success did —
    // the failure now stays visible inside the dialog.
    var revertError by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text(tr("Change history")) }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back")) } }) }) { padding ->
        if (changes.isEmpty()) Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { Text(tr("No changes recorded yet.")) }
        else LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(changes, key = { "${it.path}:${it.timestamp}:${it.source.name}:${it.operation.name}" }) { m ->
                Card(Modifier.fillMaxWidth().animateItem()) {
                    ListItem(
                        headlineContent = { Text(m.path) },
                        supportingContent = {
                            Text(
                                "${operationLabel(m.operation)} • ${sourceLabel(m.source)} • " +
                                    java.text.DateFormat.getDateTimeInstance(
                                        java.text.DateFormat.SHORT, java.text.DateFormat.SHORT,
                                    ).format(java.util.Date(m.timestamp)),
                            )
                        },
                        trailingContent = {
                            IconButton({ selected = m; revertError = null }) {
                                Icon(Icons.Filled.Restore, tr("Revert"))
                            }
                        },
                    )
                }
            }
        }
    }
    selected?.let { mutation ->
        val revertFailedText = tr("Revert failed")
        val renamedGoneText = tr("Renamed destination no longer exists")
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(tr("Revert change?")) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(mutation.path, style = MaterialTheme.typography.bodyMedium)
                Text(tr("Restore this file to the state before this change?"))
                revertError?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }, confirmButton = { TextButton(onClick = {
            scope.launch(Dispatchers.IO) {
                val fs = graph.projects.fileSystemForAsync(projectId)
                // Local snapshot: cross-module val properties cannot be smart-cast.
                val renamedTo = mutation.renamedTo
                val before = mutation.before
                val op = mutation.operation
                var failure: String? = null
                runCatching {
                    when {
                        op == com.anvex.core.model.MutationOp.RENAME && renamedTo != null -> {
                            if (fs.exists(renamedTo)) fs.move(renamedTo, mutation.path)
                            else error(renamedGoneText)
                        }
                        before == null -> fs.delete(mutation.path, true)
                        else -> fs.writeFile(mutation.path, before)
                    }
                    graph.mutations.recordAll(projectId, listOf(
                        com.anvex.core.model.FileMutation(
                            path = if (op == com.anvex.core.model.MutationOp.RENAME && renamedTo != null) renamedTo else mutation.path,
                            operation = if (op == com.anvex.core.model.MutationOp.RENAME) com.anvex.core.model.MutationOp.RENAME else com.anvex.core.model.MutationOp.MODIFY,
                            before = mutation.after,
                            after = before,
                            source = com.anvex.core.model.MutationSource.USER_EDIT,
                            renamedTo = if (op == com.anvex.core.model.MutationOp.RENAME) mutation.path else null,
                        )
                    ))
                }.onFailure { failure = it.message ?: revertFailedText }
                // UI #5: close ONLY on success; on failure the dialog stays
                // open and shows the error (was: failure swallowed, dialog
                // closed exactly like a success).
                withContext(Dispatchers.Main) {
                    if (failure == null) selected = null else revertError = failure
                }
            }
        }) { Text(tr("Revert")) } }, dismissButton = { TextButton({ selected = null }) { Text(tr("Cancel")) } })
    }
}

/** Localized journal labels (were raw enum names like AI_FILE_ACTION). */
@Composable
private fun operationLabel(op: MutationOp): String = when (op) {
    MutationOp.CREATE -> tr("Created")
    MutationOp.MODIFY -> tr("Modified")
    MutationOp.DELETE -> tr("Deleted")
    MutationOp.RENAME -> tr("Renamed")
}

@Composable
private fun sourceLabel(source: MutationSource): String = when (source) {
    MutationSource.AI_FILE_ACTION -> tr("AI file action")
    MutationSource.AI_SHELL_COMMAND -> tr("AI shell command")
    MutationSource.AI_START_COMMAND -> tr("AI start command")
    MutationSource.USER_EDIT -> tr("Your edit")
    MutationSource.IMPORT -> tr("Import")
    MutationSource.TEMPLATE -> tr("Template")
}
