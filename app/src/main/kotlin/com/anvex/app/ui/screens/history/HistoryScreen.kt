package com.anvex.app.ui.screens.history

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.anvex.app.i18n.tr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(nav: NavController, projectId: String) {
    val graph = AppGraph.get(); val scope = rememberCoroutineScope()
    val changes by graph.mutations.observeRecent(projectId).collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf<FileMutation?>(null) }
    Scaffold(topBar = { TopAppBar(title = { Text(tr("Change history")) }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back")) } }) }) { padding ->
        if (changes.isEmpty()) Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { Text(tr("No changes recorded yet.")) }
        else LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(changes, key = { "${it.path}:${it.timestamp}:${it.source.name}:${it.operation.name}" }) { m ->
                Card(Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text(m.path) },
                        supportingContent = { Text("${m.operation.name} • ${m.source.name} • ${m.timestamp}") },
                        trailingContent = { IconButton({ selected = m }) { Icon(Icons.Filled.Restore, "Revert") } },
                    )
                }
            }
        }
    }
    selected?.let { mutation ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(tr("Revert change?")) }, text = { Text("Restore ${mutation.path} to the state before this change?") }, confirmButton = { TextButton(onClick = {
            scope.launch(Dispatchers.IO) {
                val fs = graph.projects.fileSystemForAsync(projectId)
                // Local snapshot: cross-module val properties cannot be smart-cast.
                val renamedTo = mutation.renamedTo
                val before = mutation.before
                val op = mutation.operation
                runCatching {
                    when {
                        op == com.anvex.core.model.MutationOp.RENAME && renamedTo != null -> {
                            if (fs.exists(renamedTo)) fs.move(renamedTo, mutation.path)
                            else error("Renamed destination no longer exists")
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
                }.onFailure { /* keep the dialog open so the user can retry */ }
                withContext(Dispatchers.Main) { selected = null }
            }
        }) { Text(tr("Revert")) } }, dismissButton = { TextButton({ selected = null }) { Text(tr("Cancel")) } })
    }
}
