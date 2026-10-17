package com.anvex.app.ui.screens.home

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.animateItem
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.anvex.app.ui.Routes
import com.anvex.app.ui.components.AnvexCard
import com.anvex.app.ui.components.AnvexIconTile
import com.anvex.app.ui.components.AnvexSectionHeader
import com.anvex.app.ui.components.AnvexStatusBadge
import com.anvex.app.ui.components.AnvexStatus
import com.anvex.app.vm.HomeViewModel
import com.anvex.core.engine.ProjectTemplates
import com.anvex.core.model.Project
import com.anvex.core.model.ProjectType
import com.anvex.core.model.ProviderConfig
import com.anvex.app.i18n.tr

/**
 * In-memory hand-off for "create then describe" flow.
 *
 * v4.0 audit fix (thread safety): the pending prompt is now guarded — it is written
 * from the Home composition and atomically consumed by the Chat composition, which
 * may run on different threads after navigation.
 */
object NewProjectHandoff {
    @Volatile
    private var pending: String = ""

    var pendingPrompt: String
        get() = synchronized(this) { pending }
        set(value) { synchronized(this) { pending = value } }

    /** Atomically reads and clears the pending prompt exactly once. */
    fun takePendingPrompt(): String = synchronized(this) {
        val value = pending
        pending = ""
        value
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavController) {
    val vm: HomeViewModel = viewModel { HomeViewModel() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val projects by vm.projects.collectAsState()
    val visibleProjects by vm.visibleProjects.collectAsState()
    val query by vm.query.collectAsState()
    val pinnedIds by vm.pinned.collectAsState()
    val providers by vm.providers.collectAsState()
    val newUi by vm.newProjectUi.collectAsState()
    val events by vm.events.collectAsState(initial = null as String?)
    var showNewDialog by remember { mutableStateOf(false) }

    // Phase 3.2: content shared from another app -> choose the chat that receives it.
    val sharePayload by vm.sharePending.collectAsState()
    sharePayload?.let { payload ->
        AlertDialog(
            onDismissRequest = vm::dismissShare,
            title = { Text(tr("Send to chat")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val summary = buildString {
                        payload.text?.takeIf { it.isNotBlank() }?.let { append("Text: ").append(it.take(120)) }
                        if (payload.uris.isNotEmpty()) {
                            if (isNotEmpty()) append("\n")
                            append("${payload.uris.size} file(s)")
                        }
                    }
                    Text(summary, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    projects.forEach { project ->
                        TextButton(
                            onClick = {
                                vm.shareInto(project.id)
                                nav.navigate(Routes.chat(project.id))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(project.name) }
                    }
                    TextButton(
                        onClick = { vm.shareIntoNewChat { id -> nav.navigate(Routes.chat(id)) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(tr("New chat")) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::dismissShare) { Text(tr("Cancel")) } },
        )
    }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renameFor by remember { mutableStateOf<String?>(null) }

    val openExistingLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }
            runCatching {
                val name = context.contentResolver.query(
                    uri,
                    arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null, null, null,
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Workspace"
                vm.openExistingFolder(uri, name) { nav.navigate(Routes.chat(it)) }
            }
        }
    }

    val workspaceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
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

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Imported project"
            vm.importZip(name.replace(Regex("\\.(zip|bolt)$"), ""), uri) {
                nav.navigate(Routes.chat(it))
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val id = menuFor
        if (uri != null && id != null) vm.exportZip(id, uri)
        menuFor = null
    }

    LaunchedEffect(Unit) {
        vm.newProjectUi.value = vm.newProjectUi.value.copy(providerId = null)
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    com.anvex.app.ui.components.GradientBadge(Icons.Filled.AutoAwesome, size = 36)
                    Spacer(Modifier.width(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(tr("Anvex"), style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Your AI agent, with memory",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    ) {
                        Icon(Icons.Filled.Archive, contentDescription = "Import ZIP")
                    }
                    IconButton(onClick = { openExistingLauncher.launch(null) }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = "Open folder")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
        },
        floatingActionButton = {
            com.anvex.app.ui.components.GradientButton(
                text = "New project",
                icon = Icons.Filled.Add,
                onClick = { showNewDialog = true },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 108.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AnvexCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { nav.navigate(Routes.AGENT_CENTER) },
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AnvexIconTile(
                            Icons.Filled.AutoAwesome,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tr("Agent workspace"), style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.width(8.dp))
                                AnvexStatusBadge("Ready", AnvexStatus.SUCCESS)
                            }
                            Text(
                                "Plan, approve and run durable tasks through the Android runtime.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (providers.isEmpty()) {
                item { SetupProviderBanner(nav) }
            }

            item {
                OutlinedButton(
                    onClick = { nav.navigate(Routes.EXTENSIONS) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("Skills & MCP servers")) }
            }

            item {
                AnvexSectionHeader(
                    title = "Workspace",
                    subtitle = if (projects.isEmpty()) "Create or import your first project" else "${projects.size} project${if (projects.size == 1) "" else "s"}",
                )
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
                    EmptyState(
                        onCreate = { showNewDialog = true },
                        onImport = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    )
                }
            } else {
                if (visibleProjects.isEmpty()) {
                    item {
                        Text(
                            "No chats match \"$query\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(visibleProjects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        // v4.4.4 (Command Deck): smooth insert/remove motion.
                        modifier = Modifier.animateItem(),
                        onClick = { nav.navigate(Routes.chat(project.id)) },
                        onMenu = { menuFor = project.id },
                    )
                }
            }

            item {
                AnvexSectionHeader(title = tr("Quick actions"), subtitle = tr("Jump directly into your developer tools"))
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    QuickAction(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Build,
                        title = "Build",
                        subtitle = "APK",
                        onClick = {
                            projects.firstOrNull()?.let { nav.navigate(Routes.build(it.id)) }
                        },
                    )
                    QuickAction(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Terminal,
                        title = tr("Terminal"),
                        subtitle = tr("Shell"),
                        onClick = {
                            // App-wide terminal: works without any project; project terminals live in the project menu.
                            nav.navigate(Routes.terminal(com.anvex.core.android.runtime.AndroidRuntimeGateway.HOME_PROJECT_ID))
                        },
                    )
                    QuickAction(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.Cloud,
                        title = "GitHub",
                        subtitle = "Sync",
                        onClick = {
                            projects.firstOrNull()?.let { nav.navigate(Routes.github(it.id)) }
                        },
                    )
                }
            }

            events?.let { msg ->
                item {
                    AnvexCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            msg,
                            modifier = Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        NewProjectDialog(
            ui = newUi,
            providers = providers,
            onUpdate = vm::updateNewProject,
            onCreate = {
                NewProjectHandoff.pendingPrompt = newUi.prompt
                showNewDialog = false
                vm.createAndOpen { id -> nav.navigate(Routes.chat(id)) }
            },
            onChooseFolder = { workspaceLauncher.launch(null) },
            onDismiss = { showNewDialog = false },
        )
    }

    if (renameFor != null) {
        val renameTarget = requireNotNull(renameFor) { "Rename target project id is missing." }
        RenameDialog(
            initial = projects.firstOrNull { it.id == renameTarget }?.name ?: "",
            onConfirm = { name ->
                vm.rename(renameTarget, name)
                renameFor = null
            },
            onDismiss = { renameFor = null },
        )
    }

    menuFor?.let { menuId ->
        val project = projects.firstOrNull { it.id == menuId }
        if (project != null) {
            DropdownMenu(expanded = true, onDismissRequest = { menuFor = null }) {
                DropdownMenuItem(
                    text = { Text(if (project.id in pinnedIds) "Unpin" else "Pin") },
                    onClick = { vm.togglePin(project.id); menuFor = null },
                )
                DropdownMenuItem(
                    text = { Text(tr("Share chat")) },
                    onClick = {
                        menuFor = null
                        vm.buildTranscript(project.id) { text ->
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/markdown"
                                putExtra(Intent.EXTRA_SUBJECT, project.name)
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(send, "Share chat"))
                        }
                    },
                )
                DropdownMenuItem(text = { Text(tr("Rename")) }, onClick = { renameFor = project.id; menuFor = null })
                DropdownMenuItem(text = { Text(tr("Duplicate")) }, onClick = { vm.duplicate(project.id); menuFor = null })
                DropdownMenuItem(text = { Text(tr("Open editor")) }, onClick = { nav.navigate(Routes.editor(project.id)); menuFor = null })
                DropdownMenuItem(text = { Text(tr("Terminal")) }, onClick = { nav.navigate(Routes.terminal(project.id)); menuFor = null })
                DropdownMenuItem(text = { Text(tr("Build APK")) }, onClick = { nav.navigate(Routes.build(project.id)); menuFor = null })
                DropdownMenuItem(text = { Text(tr("GitHub sync")) }, onClick = { nav.navigate(Routes.github(project.id)); menuFor = null })
                DropdownMenuItem(text = { Text(tr("Export ZIP")) }, onClick = { exportLauncher.launch("${project.name}.zip") })
                DropdownMenuItem(text = { Text(tr("Delete")) }, onClick = { vm.delete(project.id); menuFor = null })
            }
        }
    }
}

@Composable
private fun QuickAction(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    AnvexCard(modifier = modifier, onClick = onClick) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnvexIconTile(icon, modifier = Modifier.size(40.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SetupProviderBanner(nav: NavController) {
    AnvexCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { nav.navigate(Routes.PROVIDERS) },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AnvexIconTile(
                Icons.Filled.AutoAwesome,
                modifier = Modifier.size(40.dp),
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("Connect your AI provider"), style = MaterialTheme.typography.titleSmall)
                Text(
                    "OpenAI, Anthropic, Gemini, OpenRouter and custom endpoints.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(tr("Set up"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun EmptyState(onCreate: () -> Unit, onImport: () -> Unit) {
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
                "Describe an idea, then review what the AI changes before you keep it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCreate) { Text(tr("Create project")) }
                OutlinedButton(onClick = onImport) { Text(tr("Import ZIP")) }
            }
        }
    }
}

@Composable
private fun ProjectCard(project: Project, modifier: Modifier = Modifier, onClick: () -> Unit, onMenu: () -> Unit) {
    AnvexCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnvexIconTile(
                Icons.Filled.Code,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                // v4.4.4 (Command Deck): technical metadata in mono.
                Text(
                    "${project.type.displayName} · ${formatRelative(project.updatedAt)} · ${formatSize(project.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = com.anvex.app.ui.theme.AnvexMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Project actions")
            }
        }
    }
}

@Composable
private fun NewProjectDialog(
    ui: HomeViewModel.NewProjectUi,
    providers: List<ProviderConfig>,
    onUpdate: (HomeViewModel.NewProjectUi.() -> HomeViewModel.NewProjectUi) -> Unit,
    onCreate: () -> Unit,
    onChooseFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("New project")) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = ui.name,
                    onValueChange = { onUpdate { copy(name = it) } },
                    label = { Text(tr("Project name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = ui.parentWorkspaceUri?.let { Uri.parse(it).lastPathSegment?.substringAfterLast(":") ?: it } ?: "App private storage",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(tr("Project folder")) },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    IconButton(onClick = onChooseFolder) {
                        Icon(Icons.Filled.Folder, contentDescription = "Choose project folder")
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
            TextButton(onClick = onCreate, enabled = ui.name.isNotBlank()) { Text(tr("Create")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("Cancel")) }
        },
    )
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Rename project")) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(tr("Name")) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(tr("Rename")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

internal fun formatRelative(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000} min ago"
        diff < 86_400_000 -> "${diff / 3_600_000} h ago"
        else -> "${diff / 86_400_000} d ago"
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024f)
    else -> "%.1f MB".format(bytes / (1024f * 1024f))
}