package com.anvex.app.ui.screens.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.anvex.app.ui.components.ProjectScaffold
import com.anvex.app.ui.components.ProjectTab
import com.anvex.app.ui.editor.CodeEditorField
import com.anvex.app.ui.editor.CodeLanguage
import com.anvex.app.vm.EditorViewModel
import com.anvex.app.i18n.tr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(nav: NavController, projectId: String, initialFile: String?) {
    val vm: EditorViewModel = viewModel(key = "editor-$projectId") { EditorViewModel(projectId) }
    val tabs by vm.tabs.collectAsState()
    val activePath by vm.activePath.collectAsState()
    val project by vm.project.collectAsState()
    val treeRevision by vm.treeRevision.collectAsState()
    val fileTree by vm.fileTreeState.collectAsState()
    val settings = rememberSettingsFontSize()

    var showExplorer by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }

    LaunchedEffect(initialFile) {
        if (initialFile != null) vm.openFile(initialFile)
    }

    val activeTab = tabs.firstOrNull { it.path == activePath }

    ProjectScaffold(
        nav = nav,
        projectId = projectId,
        title = project?.name ?: "Editor",
        currentTab = ProjectTab.EDITOR,
        actions = {
            IconButton(
                onClick = { showExplorer = true },
            ) {
                Icon(Icons.Filled.Folder, contentDescription = "Files")
            }
            IconButton(onClick = { showSearch = !showSearch }) {
                Icon(Icons.Filled.Search, contentDescription = "Search")
            }
        },
    ) { modifier ->
        Column(modifier.fillMaxSize()) {
            // ---- open file tabs --------------------------------------------------
            if (tabs.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    tabs.forEach { tab ->
                        TabChip(
                            name = tab.path.substringAfterLast('/'),
                            active = tab.path == activePath,
                            dirty = tab.dirty,
                            onClick = { vm.setActive(tab.path) },
                            onClose = { vm.closeTab(tab.path) },
                        )
                    }
                }
                androidx.compose.material3.HorizontalDivider()
                }
            }

            // v4.4 (architect feature 2 — Canvas shortcuts): task chips that send the
            // open file to the assistant with a prefilled instruction. Ported from the
            // web prototype's editor action chips; delivered through the existing share
            // intake so the user stays in control of what is sent.
            if (activeTab != null && !activeTab.readOnly) {
                CanvasShortcutRow(
                    path = activeTab.path,
                    content = activeTab.text.text,
                    projectId = projectId,
                    nav = nav,
                )
            }

            if (showSearch) {
                ProjectSearchBar(
                    query = searchQuery, replace = replaceText,
                    onQuery = { searchQuery = it }, onReplace = { replaceText = it },
                    onReplaceAll = { vm.projectReplaceAll(searchQuery, replaceText) },
                    onClose = { showSearch = false },
                    onResult = { path -> vm.openFile(path) },
                    resultsProvider = { vm.projectSearch(searchQuery) },
                )
            }

            // ---- editor area ------------------------------------------------------
            if (activeTab == null) {
                Column(
                    // weight (not fillMaxSize) so the bottom toolbar below keeps its height.
                    Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(tr("No file open"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Open a file from the project files, or ask the AI in Chat to create some.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { showExplorer = true }) { Text(tr("Browse files")) }
                }
            } else {
                if (activeTab.readOnly) {
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text(
                            "Read-only view: this file is larger than 1 MB. Editing is disabled to keep the app responsive; use Export or the AI to modify it.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                CodeEditorField(
                    value = activeTab.text,
                    onValueChange = { vm.updateField(activeTab.path, it) },
                    language = CodeLanguage.fromPath(activeTab.path),
                    fontSizeSp = settings,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    searchRanges = if (searchQuery.length >= 2 && showSearch) {
                        vm.findMatches(activeTab.path, searchQuery).map { it until it + searchQuery.length }
                    } else emptyList(),
                    readOnly = activeTab.readOnly,
                )
            }

            // ---- bottom toolbar ----------------------------------------------------
            Surface(tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { activePath?.let(vm::undo) },
                        enabled = activeTab != null && activeTab.undoStack.isNotEmpty(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(
                        onClick = { activePath?.let(vm::redo) },
                        enabled = activeTab != null && activeTab.redoStack.isNotEmpty(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        activePath ?: "",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    if (activeTab?.dirty == true) {
                        Text(
                            "unsaved",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        IconButton(onClick = { activePath?.let(vm::saveNow) }) {
                            Icon(Icons.Filled.Save, contentDescription = "Save")
                        }
                    }
                }
            }
        }
    }

    if (showExplorer) {
        if (fileTree == null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showExplorer = false },
                title = { Text(tr("Project files")) },
                text = { androidx.compose.material3.CircularProgressIndicator() },
                confirmButton = { TextButton(onClick = { showExplorer = false }) { Text(tr("Cancel")) } },
            )
        }
        fileTree?.let { tree ->
            FileExplorerSheet(
            root = tree,
            activePath = activePath,
            onOpenFile = { path ->
                vm.openFile(path)
            },
            onCreateEntry = { dir, name, isFolder ->
                if (isFolder) vm.createFolder(dir, name) else vm.createFile(dir, name)
            },
            onRename = { path, name -> vm.renameEntry(path, name) },
            onDelete = { path -> vm.deleteEntry(path) },
            onDuplicate = { path -> vm.duplicateEntry(path) },
            onDismiss = { showExplorer = false },
            )
        }
    }
}

@Composable
private fun rememberSettingsFontSize(): Int {
    // settings flow via AppGraph directly (kept light for the editor screen)
    val graph = com.anvex.core.android.AppGraph.get()
    val settings by graph.settings.settings.collectAsState(initial = null)
    return settings?.editorFontSize ?: 13
}

@Composable
private fun TabChip(
    name: String,
    active: Boolean,
    dirty: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        color = if (active) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.padding(end = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 2.dp)
                .clickable { onClick() },
        ) {
            Text(
                name + if (dirty) " •" else "",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium,
            )
            IconButton(onClick = onClose, modifier = Modifier.width(28.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close tab",
                    modifier = Modifier.width(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ProjectSearchBar(
    query: String, replace: String, onQuery: (String) -> Unit, onReplace: (String) -> Unit,
    onReplaceAll: () -> Unit, onClose: () -> Unit, onResult: (String) -> Unit,
    resultsProvider: () -> List<com.anvex.core.engine.ProjectSearch.Match>,
) {
    var results by remember(query) { mutableStateOf(emptyList<com.anvex.core.engine.ProjectSearch.Match>()) }
    Surface(tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, onQuery, placeholder = { Text(tr("Search entire project")) }, singleLine = true, modifier = Modifier.weight(1f))
                IconButton(onClick = { results = resultsProvider() }) { Icon(Icons.Filled.Search, "Search") }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(replace, onReplace, placeholder = { Text(tr("Replace with")) }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = onReplaceAll, enabled = query.isNotBlank()) { Text(tr("Replace all")) }
            }
            results.take(50).forEach { match ->
                Text("${match.path}:${match.line}:${match.column}  ${match.preview}", modifier = Modifier.fillMaxWidth().clickable { onResult(match.path) }.padding(vertical = 3.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    replace: String,
    onQuery: (String) -> Unit,
    onReplace: (String) -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    placeholder = { Text(tr("Find")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close search")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = replace,
                    onValueChange = onReplace,
                    placeholder = { Text(tr("Replace with")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onReplaceAll, enabled = query.isNotBlank()) {
                    Text(tr("Replace all"))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- v4.4: Canvas shortcuts

/** Port-language choices for the "Port to language" chip. */
private val PORT_TARGETS = listOf("Kotlin", "Java", "Swift", "TypeScript", "Python", "Go", "C#")

/**
 * Task chips for the open file (architect feature 2, ported from the web
 * prototype): Review · Fix bugs · Add logs & comments · Optimize · Port.
 * Each chip builds an explicit instruction and delivers it through the
 * existing share intake — the user confirms what gets sent to the assistant.
 */
@Composable
private fun CanvasShortcutRow(
    path: String,
    content: String,
    projectId: String,
    nav: NavController,
) {
    var showPortDialog by remember { mutableStateOf(false) }
    // Hoisted composable labels (tr() cannot run inside onClick lambdas).
    val labelReview = tr("Review")
    val labelFix = tr("Fix bugs")
    val labelLogs = tr("Add logs & comments")
    val labelOptimize = tr("Optimize")
    val labelPort = tr("Port to language")
    val promptReview = "Review this code carefully. Point out bugs, risks and missing edge cases, and propose concrete fixes."
    val promptFix = "Fix the bugs in this file. Apply the fixes as a full updated file (or SEARCH/REPLACE blocks for small edits)."
    val promptLogs = "Add clarifying comments and lightweight logging to this file without changing its behavior."
    val promptOptimize = "Optimize this file for readability and performance. Keep the public API unchanged; explain each optimization briefly."
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(selected = false, onClick = { offerToAssistant(promptReview, path, content, projectId, nav) }, label = { Text(labelReview) })
        FilterChip(selected = false, onClick = { offerToAssistant(promptFix, path, content, projectId, nav) }, label = { Text(labelFix) })
        FilterChip(selected = false, onClick = { offerToAssistant(promptLogs, path, content, projectId, nav) }, label = { Text(labelLogs) })
        FilterChip(selected = false, onClick = { offerToAssistant(promptOptimize, path, content, projectId, nav) }, label = { Text(labelOptimize) })
        FilterChip(selected = false, onClick = { showPortDialog = true }, label = { Text(labelPort) })
    }

    if (showPortDialog) {
        AlertDialog(
            onDismissRequest = { showPortDialog = false },
            title = { Text(tr("Port to language")) },
            text = {
                Column {
                    PORT_TARGETS.forEach { target ->
                        TextButton(onClick = {
                            showPortDialog = false
                            offerToAssistant(
                                "Port this file to $target. Keep the behavior identical and use idiomatic $target.",
                                path, content, projectId, nav,
                            )
                        }) { Text(target) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showPortDialog = false }) { Text(tr("Cancel")) } },
        )
    }
}

/** Build the instruction + file payload and hand it to the share intake. */
private fun offerToAssistant(
    instruction: String,
    path: String,
    content: String,
    projectId: String,
    nav: NavController,
) {
    val capped = content.take(12_000) + if (content.length > 12_000) "\n… [truncated for the prompt]" else ""
    val prompt = buildString {
        append(instruction)
        append("\n\nFile: ")
        append(path)
        append("\n\n```\n")
        append(capped)
        append("\n```")
    }
    com.anvex.app.share.IncomingShare.offer(
        com.anvex.app.share.IncomingShare.Payload(prompt, emptyList())
    )
    nav.navigate(com.anvex.app.ui.Routes.chat(projectId))
}
