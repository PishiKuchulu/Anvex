package com.anvex.app.ui.screens.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.anvex.app.i18n.tr
import com.anvex.app.ui.Routes
import com.anvex.app.ui.components.AnvexCard
import com.anvex.app.ui.components.AnvexIconTile
import com.anvex.app.ui.screens.home.NewProjectHandoff
import com.anvex.app.ui.screens.home.formatRelative
import com.anvex.app.ui.screens.home.formatSize
import com.anvex.app.ui.screens.home.starterLabel
import com.anvex.app.vm.HomeViewModel
import com.anvex.core.engine.ProjectTemplates
import com.anvex.core.model.Project

/**
 * Atelier IA: Projects is its own top-level destination (the design splits
 * the old combined Home into a composer-first Home and a full project
 * list here). Data and actions come from the same [HomeViewModel] the
 * classic Home uses. Delete lives on each row here (user request,
 * 2026-11-02: the list offered no way to remove a project); the rest of
 * project management (rename, pin, export) lives on Home's project cards
 * and in each project's chat.
 */
@Composable
fun ProjectsScreen(nav: NavController) {
    val vm: HomeViewModel = viewModel { HomeViewModel() }
    val projects by vm.projects.collectAsState()
    val visibleProjects by vm.visibleProjects.collectAsState()
    val query by vm.query.collectAsState()
    val newUi by vm.newProjectUi.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    var deleteProject by remember { mutableStateOf<Project?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Same folder picking as Home's new-project dialog: the user chooses
    // where the project lives, not just its name.
    val workspaceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            vm.updateNewProject { copy(parentWorkspaceUri = uri.toString()) }
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(tr("Projects"), style = MaterialTheme.typography.displaySmall)
                        Text(
                            "${projects.size} ${tr("projects")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { showCreate = true }) {
                        Icon(Icons.Filled.Add, contentDescription = tr("New project"))
                    }
                }
            }
            if (projects.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = vm::setQuery,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(tr("Search chats")) },
                    )
                }
            }
            if (projects.isEmpty()) {
                item {
                    // UI #5: a real empty state with the primary action
                    // inline (was a plain line of text).
                    AnvexCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            AnvexIconTile(
                                Icons.Filled.Code,
                                modifier = Modifier.size(56.dp),
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            )
                            Text(tr("Start your first project"), style = MaterialTheme.typography.titleLarge)
                            Text(
                                tr("Describe an idea, then review what the AI changes before you keep it."),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = { showCreate = true }) { Text(tr("Create project")) }
                        }
                    }
                }
            } else if (visibleProjects.isEmpty()) {
                item {
                    Text(
                        "${tr("No projects match")} \"$query\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(visibleProjects, key = { it.id }) { project ->
                ProjectRow(
                    project = project,
                    modifier = Modifier.animateItem(),
                    onClick = { nav.navigate(Routes.chat(project.id)) },
                    onDelete = { deleteProject = project },
                )
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(tr("New project")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newUi.name,
                        onValueChange = { v -> vm.updateNewProject { copy(name = v) } },
                        singleLine = true,
                        label = { Text(tr("Project name")) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Wiring gap 2: Template chips select a real starter.
                    Text(tr("Template"), style = MaterialTheme.typography.labelLarge)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ProjectTemplates.Starter.entries) { starter ->
                            FilterChip(
                                selected = newUi.starter == starter,
                                onClick = { vm.updateNewProject { copy(starter = starter) } },
                                label = { Text(starterLabel(starter)) },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newUi.parentWorkspaceUri?.let {
                                Uri.parse(it).lastPathSegment?.substringAfterLast(":") ?: it
                            } ?: tr("App private storage"),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(tr("Project folder")) },
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        IconButton(onClick = { workspaceLauncher.launch(null) }) {
                            Icon(Icons.Filled.Folder, contentDescription = tr("Choose project folder"))
                        }
                    }
                    Text(
                        tr("Choose the project folder. If it already contains files they are kept and the folder is opened as the project."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newUi.name.isNotBlank(),
                    onClick = {
                        NewProjectHandoff.pendingPrompt = newUi.prompt
                        showCreate = false
                        vm.createAndOpen { id -> nav.navigate(Routes.chat(id)) }
                    },
                ) { Text(tr("Create")) }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text(tr("Cancel")) }
            },
        )
    }

    deleteProject?.let { project ->
        com.anvex.app.ui.components.ConfirmDialog(
            title = tr("Delete project?"),
            text = tr("The project and all its files, chats and history will be permanently deleted. This cannot be undone."),
            confirmLabel = tr("Delete"),
            destructive = true,
            onConfirm = { vm.delete(project.id); deleteProject = null },
            onDismiss = { deleteProject = null },
        )
    }
}

@Composable
private fun ProjectRow(
    project: Project,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    AnvexCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnvexIconTile(
                Icons.Filled.Code,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    "${project.type.displayName} · ${formatRelative(project.updatedAt)} · ${formatSize(project.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = com.anvex.app.ui.theme.AnvexMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = tr("Delete"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
