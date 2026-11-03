package com.anvex.app.ui.screens.developer

import android.Manifest
import android.os.Build
import android.content.Intent

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.anvex.app.runtime.AgentServiceController
import com.anvex.app.ui.components.*
import com.anvex.app.ui.theme.AnvexDimens
import com.anvex.core.android.AppGraph
import com.anvex.core.model.TaskRunItem
import com.anvex.core.android.artifacts.ArtifactManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.anvex.app.i18n.tr

/** Agent workspace: goal -> durable task -> exact approval -> execution -> recovery. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AgentCenterScreen(nav: NavController? = null) {
    val graph = AppGraph.get()
    val scope = rememberCoroutineScope()
    val projects by graph.projects.observeAll().collectAsState(initial = emptyList())
    val tasks by graph.reliability.observeTaskItems(50).collectAsState(initial = emptyList())
    val caps = remember { graph.deviceCapabilities.detect() }
    var selected by remember(projects) { mutableStateOf(projects.firstOrNull()) }
    var goal by remember { mutableStateOf("") }
    var context by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var artifacts by remember { mutableStateOf<List<ArtifactManager.GeneratedArtifact>>(emptyList()) }
    // Wiring gap 1: Pi-engine tasks are durable tasks too (type
    // "pi-agent") — without this they never appeared in the queue.
    val agentTasks = tasks.filter { it.type == "agent" || it.type == "pi-agent" }
    // Notice fragments resolved in composition (the coroutine callbacks
    // below are not composable and cannot call tr() themselves).
    val taskWord = tr("Task")
    val queuedSuffix = tr("queued.")
    val couldNotQueueText = tr("Could not queue task.")
    val approvedPrefix = tr("Exact action approved for")
    val noApprovalPrefix = tr("No pending approval for")
    val resumedSuffix = tr("resumed.")
    val cannotResumeSuffix = tr("cannot resume yet.")
    val cancelPrefix = tr("Cancellation requested for")
    val cannotCancelSuffix = tr("cannot be cancelled.")

    LaunchedEffect(selected?.id, tasks) {
        val projectId = selected?.id ?: return@LaunchedEffect
        artifacts = withContext(kotlinx.coroutines.Dispatchers.IO) { graph.artifactManager.list(projectId) }
    }
    val activeCount = agentTasks.count { it.state in setOf("QUEUED", "RUNNING", "RETRYING", "WAITING_APPROVAL") }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { AgentServiceController.start(graph.context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Agent Workspace")) },
                navigationIcon = {
                    if (nav != null) IconButton({ nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AnvexCard(Modifier.fillMaxWidth().padding(horizontal = AnvexDimens.page)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AnvexIconTile(Icons.Filled.AutoAwesome)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(tr("Agent Workspace"), style = MaterialTheme.typography.titleLarge)
                                Text(tr("Plan, execute, pause for approval, and recover safely."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AnvexStatusBadge("$activeCount ${tr("active")}", if (activeCount > 0) AnvexStatus.INFO else AnvexStatus.SUCCESS)
                            AnvexStatusBadge(tr("Exact approval"), AnvexStatus.SUCCESS)
                        }
                    }
                }
            }

            item { AnvexSectionHeader(tr("1 · Create task"), tr("Give the agent one concrete outcome at a time.")) }
            item {
                AnvexCard(Modifier.fillMaxWidth().padding(horizontal = AnvexDimens.page)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (projects.isEmpty()) {
                            Text(tr("Create or import a project first."), color = MaterialTheme.colorScheme.error)
                        } else {
                            Text(tr("Project"), style = MaterialTheme.typography.labelLarge)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
                                items(projects.take(8), key = { it.id }) { p ->
                                    FilterChip(
                                        selected = selected?.id == p.id,
                                        onClick = { selected = p },
                                        label = { Text(p.name, maxLines = 1) },
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = goal,
                                onValueChange = { goal = it.take(8000) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(tr("Goal")) },
                                placeholder = { Text(tr("Fix the settings screen and verify the build…")) },
                                minLines = 3,
                            )
                            OutlinedTextField(
                                value = context,
                                onValueChange = { context = it.take(32000) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(tr("Context · optional")) },
                                minLines = 2,
                            )
                            Button(
                                enabled = !busy && goal.isNotBlank() && selected != null,
                                onClick = {
                                    val p = selected ?: return@Button
                                    busy = true
                                    scope.launch {
                                        // Wiring gap 1: engine-aware enqueue —
                                        // Pi-selected goals go to PiTaskBridge,
                                        // native goals to AgentTaskBridge.
                                        // R2-2: the Context field is passed
                                        // through — it used to be collected
                                        // and silently discarded here.
                                        runCatching { graph.enqueueAgentTask(goal, p.id, context) }
                                            .onSuccess { notice = "$taskWord #$it $queuedSuffix"; goal = ""; context = "" }
                                            .onFailure { notice = it.message ?: couldNotQueueText }
                                        busy = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = AnvexDimens.control),
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (busy) tr("Queueing…") else tr("Start agent"))
                            }
                        }
                    }
                }
            }

            item { AnvexSectionHeader(tr("2 · Runtime"), tr("Capabilities available to the on-device agent.")) }
            item {
                AnvexCard(Modifier.fillMaxWidth().padding(horizontal = AnvexDimens.page)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Android ${caps.apiLevel} · ${caps.abi}", style = MaterialTheme.typography.titleSmall)
                        CapabilityLine("ARM64", caps.arm64)
                        CapabilityLine(tr("Wireless ADB"), caps.wirelessAdb)
                        CapabilityLine(tr("Foreground service"), caps.foregroundService)
                        CapabilityLine(tr("Web server"), caps.webServer)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({
                                if (Build.VERSION.SDK_INT >= 33) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    AgentServiceController.start(graph.context)
                                }
                            }, modifier = Modifier.weight(1f)) { Text(tr("Start service")) }
                            OutlinedButton({ AgentServiceController.stop(graph.context) }, modifier = Modifier.weight(1f)) { Text(tr("Stop")) }
                        }
                    }
                }
            }

            item { AnvexSectionHeader(tr("3 · Runtime capability matrix"), tr("Platform capability and actual project-runtime capability are shown separately.")) }
            item {
                AnvexCard(Modifier.fillMaxWidth().padding(horizontal = AnvexDimens.page)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val runtime = com.anvex.core.engine.RuntimeCapabilities.CURRENT
                        // v4.6.1: Node.js/npm describe the DEVICE runtime, not
                        // the Preview table — RuntimeCapabilities.CURRENT is the
                        // WebView preview's static table (it predates the
                        // bundled Node runtime and reported Node "Unavailable"
                        // while npm, which needs Node, showed "Available").
                        // The bundled npm ships with the runtime, so the two
                        // rows can never disagree again.
                        val nodeAvailable = com.anvex.core.android.runtime.node.NodeMobileRuntime.isBundledAvailable()
                        CapabilityLine(tr("File editing"), runtime.fileEditing == com.anvex.core.engine.CommandCapability.SUPPORTED)
                        CapabilityLine(tr("React offline preview"), runtime.offlineReactPreview == com.anvex.core.engine.CommandCapability.SUPPORTED)
                        CapabilityLine("Node.js", nodeAvailable)
                        CapabilityLine("npm", nodeAvailable)
                        CapabilityLine("Vite", runtime.vite == com.anvex.core.engine.CommandCapability.SUPPORTED)
                        CapabilityLine(tr("Shell"), runtime.shell != com.anvex.core.engine.CommandCapability.UNSUPPORTED)
                    }
                }
            }

            item { AnvexSectionHeader(tr("3 · Artifacts"), tr("Generated files from the Agent stay in app-private storage until you choose to share them.")) }
            if (selected == null || artifacts.isEmpty()) {
                item {
                    AnvexCard(Modifier.fillMaxWidth().padding(horizontal = AnvexDimens.page)) {
                        Text(
                            if (selected == null) tr("Select a project to view artifacts.") else tr("No generated artifacts yet."),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(artifacts, key = { it.id }) { artifact ->
                    val shareArtifactText = tr("Share artifact")
                    AnvexCard(Modifier.fillMaxWidth().padding(horizontal = AnvexDimens.page)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            AnvexIconTile(when (artifact.format) {
                                com.anvex.core.engine.ArtifactFormat.ZIP -> Icons.Filled.FolderZip
                                com.anvex.core.engine.ArtifactFormat.PDF -> Icons.Filled.PictureAsPdf
                                else -> Icons.Filled.Description
                            })
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(artifact.file.name.substringAfter('-', artifact.file.name), style = MaterialTheme.typography.titleSmall, maxLines = 2)
                                Text("${artifact.format.name} · ${artifact.file.length()} bytes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = {
                                val uri = graph.artifactManager.shareUri(artifact)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = artifact.mimeType
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                graph.context.startActivity(Intent.createChooser(intent, shareArtifactText).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }) { Icon(Icons.Filled.Share, contentDescription = tr("Share")) }
                        }
                    }
                }
            }

            item { AnvexSectionHeader(tr("4 · Task queue"), tr("Approvals are exact-action scoped. No blanket shell permission is granted.")) }
            if (agentTasks.isEmpty()) {
                item { EmptyAgentTasks() }
            } else {
                items(agentTasks, key = { it.id }) { task ->
                    AgentTaskCard(
                        task = task,
                        projectName = projects.firstOrNull { it.id == task.projectId }?.name,
                        onApprove = {
                            scope.launch {
                                notice = if (graph.agentTaskBridge.approveWaitingTask(task.id)) "$approvedPrefix #${task.id}." else "$noApprovalPrefix #${task.id}."
                            }
                        },
                        onResume = {
                            scope.launch {
                                notice = if (graph.agentTaskBridge.resumeAfterApproval(task.id)) "$taskWord #${task.id} $resumedSuffix" else "$taskWord #${task.id} $cannotResumeSuffix"
                            }
                        },
                        onPause = {
                            scope.launch { graph.reliability.pauseTask(task.id) }
                        },
                        onCancel = {
                            scope.launch {
                                notice = if (graph.reliability.cancelTask(task.id)) "$cancelPrefix #${task.id}." else "$taskWord #${task.id} $cannotCancelSuffix"
                            }
                        },
                    )
                }
            }
            notice?.let { message ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = AnvexDimens.page),
                    ) {
                        Text(message, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun CapabilityLine(label: String, available: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        AnvexStatusBadge(if (available) tr("Available") else tr("Unavailable"), if (available) AnvexStatus.SUCCESS else AnvexStatus.ERROR)
    }
}

@Composable
private fun EmptyAgentTasks() {
    AnvexCard(Modifier.fillMaxWidth().padding(horizontal = AnvexDimens.page)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(tr("No agent tasks yet"), style = MaterialTheme.typography.titleMedium)
            Text(tr("Queued, running, waiting and completed work will remain visible here."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Localized task-state badge (was the raw enum: "WAITING_APPROVAL"). */
@Composable
private fun taskStateLabel(state: String): String = when (state) {
    "QUEUED" -> tr("Queued")
    "RUNNING" -> tr("Running")
    "COMPLETED" -> tr("Completed")
    "FAILED" -> tr("Failed")
    "WAITING_APPROVAL" -> tr("Waiting approval")
    "RETRYING" -> tr("Retrying")
    else -> state.replace('_', ' ')
}

@Composable
private fun AgentTaskCard(task: TaskRunItem, projectName: String?, onApprove: () -> Unit, onResume: () -> Unit, onPause: () -> Unit, onCancel: () -> Unit) {
    AnvexCard(Modifier.fillMaxWidth().padding(horizontal = AnvexDimens.page)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("#${task.id} · ${task.label}", style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    Text(projectName ?: tr("Project"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (task.type == "pi-agent") {
                        Text(tr("Pi engine"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                AnvexStatusBadge(taskStateLabel(task.state), when (task.state) {
                    "COMPLETED" -> AnvexStatus.SUCCESS
                    "FAILED" -> AnvexStatus.ERROR
                    "WAITING_APPROVAL" -> AnvexStatus.WARNING
                    else -> AnvexStatus.INFO
                })
            }
            task.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4) }
            if (task.state in setOf("RUNNING", "RETRYING")) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (task.state == "WAITING_APPROVAL") {
                    Button(onClick = onApprove, modifier = Modifier.weight(1f)) { Text(tr("Approve")) }
                    OutlinedButton(onClick = onResume, modifier = Modifier.weight(1f)) { Text(tr("Resume")) }
                }
                // Wiring gap 8: pause was engine-only (pauseTask) with no
                // UI caller — running work can now be paused from here.
                if (task.state in setOf("RUNNING", "RETRYING")) {
                    OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) { Text(tr("Pause")) }
                }
                if (task.state in setOf("QUEUED", "RUNNING", "RETRYING")) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(tr("Cancel")) }
                }
            }
        }
    }
}

