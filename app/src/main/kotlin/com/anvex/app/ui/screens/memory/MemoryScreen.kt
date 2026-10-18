package com.anvex.app.ui.screens.memory

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.anvex.app.ui.components.BidiText
import com.anvex.app.ui.components.EmptyState
import com.anvex.app.ui.components.GlassCard
import com.anvex.app.ui.components.GradientButton
import com.anvex.app.ui.components.NebulaBackground
import com.anvex.app.ui.components.NebulaChip
import com.anvex.app.ui.components.NebulaTopBar
import com.anvex.core.android.AppGraph
import com.anvex.core.model.MemoryCategory
import com.anvex.core.model.MemoryItem
import kotlinx.coroutines.launch
import com.anvex.app.i18n.tr

/** Everything the assistant remembers about the user — fully visible, editable and deletable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(nav: NavController) {
    val graph = remember { AppGraph.get() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val items by graph.memory.items.collectAsState()
    val settings by graph.settings.settings.collectAsState(initial = null)
    var filter by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var rejected by remember { mutableStateOf(false) }
    val copyMemoryLabel = tr("Anvex memory")

    val shown = items
        .filter { filter == null || it.category == filter }
        .sortedWith(compareByDescending<MemoryItem> { it.pinned }.thenByDescending { it.updatedAt })

    NebulaBackground {
        Scaffold(
            // v4.4.5: explicit contentColor — contentColorFor(Color.Transparent)
            // resolves to Unspecified, whose ultimate text fallback is BLACK,
            // which made default-colored text unreadable on the dark canvas.
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            topBar = {
                NebulaTopBar(
                    title = "Memory",
                    subtitle = "${items.size} things I know about you",
                    onBack = if (nav.previousBackStackEntry != null) ({ nav.popBackStack() }) else null,
                    actions = {
                        IconButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText(copyMemoryLabel, graph.memory.exportJson()))
                        }) { Icon(Icons.Filled.ContentCopy, "Copy as JSON") }
                        IconButton(onClick = { confirmClear = true }, enabled = items.isNotEmpty()) {
                            Icon(Icons.Filled.DeleteSweep, "Clear all")
                        }
                    },
                )
            },
            floatingActionButton = {
                GradientButton(text = "Add", icon = Icons.Filled.Add, onClick = { adding = true })
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tr("Learn from our chats"), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "The assistant saves durable preferences and facts (never passwords or keys) and uses them in every project.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = settings?.memoryEnabled ?: true,
                                onCheckedChange = { v -> scope.launch { graph.settings.setMemoryEnabled(v) } },
                            )
                        }
                    }
                }
                item {
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { NebulaChip("All", selected = filter == null, onClick = { filter = null }) }
                        items(MemoryCategory.ALL) { c ->
                            NebulaChip(c.replaceFirstChar { it.uppercase() }, selected = filter == c, onClick = { filter = c })
                        }
                    }
                }
                if (rejected) {
                    item { Text(tr("That text was not saved (empty, too short, or it looks like a secret)."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                if (shown.isEmpty()) {
                    item {
                        EmptyState(
                            Icons.Filled.Psychology,
                            "Nothing remembered yet",
                            "As we work together I'll note your preferences — language, tech stack, style. You can also add or edit them yourself.",
                        )
                    }
                }
                items(shown, key = { it.id }) { m ->
                    GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, top = 12.dp, bottom = 4.dp, end = 4.dp)) {
                        Column {
                            BidiText(m.text, style = MaterialTheme.typography.bodyMedium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${m.category} · ${if (m.source == MemoryItem.SOURCE_USER) "added by you" else "learned"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { scope.launch { graph.memory.setPinned(m.id, !m.pinned) } }) {
                                    Icon(if (m.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, "Pin", tint = if (m.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { editing = m }) { Icon(Icons.Filled.Edit, "Edit") }
                                IconButton(onClick = { scope.launch { graph.memory.delete(m.id) } }) { Icon(Icons.Filled.Delete, "Delete") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding || editing != null) {
        var text by remember(editing, adding) { mutableStateOf(editing?.text.orEmpty()) }
        AlertDialog(
            onDismissRequest = { adding = false; editing = null },
            title = { Text(if (editing == null) "Add a memory" else "Edit memory") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(300) }, minLines = 2, maxLines = 5,
                    placeholder = { Text(tr("e.g. Prefers Persian replies and Kotlin/Compose")) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = editing
                    scope.launch {
                        val ok = if (target == null) graph.memory.addManual(text) else graph.memory.edit(target.id, text)
                        rejected = !ok
                    }
                    adding = false; editing = null
                }) { Text(tr("Save")) }
            },
            dismissButton = { TextButton(onClick = { adding = false; editing = null }) { Text(tr("Cancel")) } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(tr("Forget everything?")) },
            text = { Text("All ${items.size} memories will be deleted from this device.") },
            confirmButton = { TextButton(onClick = { scope.launch { graph.memory.clear() }; confirmClear = false }) { Text(tr("Delete all")) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("Cancel")) } },
        )
    }
}
