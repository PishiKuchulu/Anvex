package com.anvex.app.ui.screens.developer


import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.anvex.core.android.AppGraph
import com.anvex.core.model.TaskRunItem
import com.anvex.core.android.reliability.ReliabilityRepository
import com.anvex.core.engine.ActionSafetyPolicy
import com.anvex.core.engine.ToolCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.anvex.app.i18n.tr

private const val APPROVAL_SESSION = "developer-center"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperCenterScreen(nav: NavController) {
    val graph = AppGraph.get()
    val scope = rememberCoroutineScope()
    val events by graph.reliability.observeEvents().collectAsState(initial = emptyList())
    val tasks by graph.reliability.observeTaskItems().collectAsState(initial = emptyList())
    val backups by graph.reliability.observeBackups().collectAsState(initial = emptyList())
    val plugins by graph.reliability.observePlugins().collectAsState(initial = emptyList())
    var health by remember { mutableStateOf<List<ReliabilityRepository.HealthItem>>(emptyList()) }
    var repairs by remember { mutableStateOf<List<ReliabilityRepository.RepairItem>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf("Health") }
    var command by remember { mutableStateOf("") }
    var lan by remember { mutableStateOf(graph.lanAccess.status()) }
    var approvalCapability by remember { mutableStateOf(ToolCapability.WORKSPACE_WRITE) }
    var approvalTtl by remember { mutableStateOf(15L * 60_000) }
    var approvalGranted by remember { mutableStateOf(false) }
    var exposeLan by remember { mutableStateOf(false) }

    val tabs = listOf("Health", "Repair", "Tasks", "Backups", "Skills", "Activity", "Safety", "LAN")
    val importPlugins = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val text = graph.context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (text != null) runCatching { graph.reliability.importPluginManifest(text) }
                .onSuccess { status = "Imported $it skill manifest(s)." }
                .onFailure { status = "Import failed: ${it.message ?: "invalid manifest"}" }
        }
    }
    val exportPlugins = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            runCatching {
                graph.reliability.exportPluginManifest().also { text ->
                    graph.context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                        ?: error("Could not open destination.")
                }
            }.onSuccess { status = "Skill manifest exported." }.onFailure { status = "Export failed: ${it.message}" }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(tr("Developer Center")) },
            navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back")) } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // v4.4.5 (UX): PrimaryTabRow split 8 tabs into ~50dp each and the
            // labels wrapped vertically ("He/alt/h"). ScrollableTabRow gives
            // each tab a readable minimum width instead.
            ScrollableTabRow(
                selectedTabIndex = tabs.indexOf(selected),
                edgePadding = 16.dp,
            ) {
                tabs.forEach { label ->
                    Tab(
                        selected = selected == label,
                        onClick = { selected = label },
                        text = { Text(label, maxLines = 1, softWrap = false) },
                        modifier = Modifier.widthIn(min = 88.dp),
                    )
                }
            }
            status?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.primary) }
            when (selected) {
                "Health" -> HealthTab(health, { scope.launch { health = graph.reliability.runHealthCheck() } }, graph.toolchain.isInstalled().let { ok -> if (ok) "Toolchain imported and valid" else "No valid toolchain imported" })
                "Repair" -> RepairCenterTab(repairs, onRefresh = { scope.launch { repairs = graph.reliability.runRepairCenter() } }, onFix = { id ->
                    scope.launch(Dispatchers.IO) {
                        val label = when (id) { "preview-cache" -> "Clear preview cache"; "stale-build" -> "Clean stale build workspaces"; else -> "Quarantine corrupt sessions" }
                        runCatching { graph.reliability.enqueueRepair(label) }.onSuccess { status = "$label queued and will be verified by the task engine." }.onFailure { status = "Repair failed to queue: ${it.message}" }
                    }
                })
                "Tasks" -> TaskManagerTab(tasks, { id -> scope.launch { status = if (graph.reliability.resumeTask(id)) "Task resumed." else "Task cannot be resumed." } }, { id -> scope.launch { status = if (graph.reliability.cancelTask(id)) "Cancellation requested." else "Task cannot be cancelled." } })
                "Backups" -> BackupTab(backups, graph.reliability.automaticBackupDirectory().absolutePath, { scope.launch(Dispatchers.IO) { graph.reliability.createAutomaticSnapshot("manual recovery snapshot"); status = "Encrypted recovery snapshot created." } }, { scope.launch(Dispatchers.IO) { graph.reliability.rotateAutomaticBackups(3); status = "Newest 3 automatic backups retained." } })
                "Skills" -> SkillsTab(plugins, { importPlugins.launch(arrayOf("application/json", "text/plain")) }, { exportPlugins.launch("anvex-skills.json") }, { id, enabled -> scope.launch { graph.reliability.setPluginEnabled(id, enabled) } }, { id -> scope.launch { status = if (graph.reliability.removePlugin(id)) "Skill removed." else "Built-in skill cannot be removed." } })
                "Activity" -> ActivityTab(events)
                "Safety" -> ApprovalTab(command, { command = it }, approvalCapability, { approvalCapability = it }, approvalTtl, { approvalTtl = it }, approvalGranted, onGrant = {
                    scope.launch { runCatching { graph.approvals.grant(APPROVAL_SESSION, null, setOf(approvalCapability), approvalTtl); approvalGranted = true }.onSuccess { status = "Approved ${approvalCapability.name} for ${approvalTtl / 60_000} minutes." }.onFailure { status = "Approval failed: ${it.message}" } }
                }, onRevoke = { scope.launch { graph.approvals.revoke(APPROVAL_SESSION); approvalGranted = false; status = "Approval revoked." } })
                "LAN" -> LanStatusTab(lan, exposeLan, onExposeLanChange = { exposeLan = it }, onStart = {
                    runCatching { lan = graph.lanAccess.start(
                        { "runningTasks=${tasks.count { it.state == "RUNNING" }}" },
                        exposeOnLan = exposeLan,
                    ) }.onSuccess { status = "LAN status endpoint started. Authentication token remains protected." }.onFailure { status = "LAN start failed: ${it.message}" }
                }, onStop = { graph.lanAccess.stop(); lan = graph.lanAccess.status(); status = "LAN status endpoint stopped." })
            }
        }
    }
}

@Composable private fun HealthTab(items: List<ReliabilityRepository.HealthItem>, onRun: () -> Unit, toolchainStatus: String) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(tr("System health"), style = MaterialTheme.typography.headlineSmall); IconButton(onClick = onRun) { Icon(Icons.Default.Refresh, "Run health check") } }
        Text(tr("Deterministic checks are informational; unavailable runtime capabilities remain explicit limitations."), style = MaterialTheme.typography.bodySmall)
        Text("Android build runtime: $toolchainStatus", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { items(items, key = { it.name }) { item -> Card(Modifier.fillMaxWidth()) { ListItem(leadingContent = { Icon(if (item.ok) Icons.Default.CheckCircle else Icons.Default.Error, null) }, headlineContent = { Text(item.name) }, supportingContent = { Text(item.detail) }) } } }
    }
}

@Composable private fun RepairCenterTab(items: List<ReliabilityRepository.RepairItem>, onRefresh: () -> Unit, onFix: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(tr("Repair Center"), style = MaterialTheme.typography.headlineSmall); IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Diagnose") } }
        Text(tr("Diagnose first; fixes are bounded, app-owned and executed through durable tasks."), style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item -> Card(Modifier.fillMaxWidth()) { ListItem(headlineContent = { Text(item.name) }, supportingContent = { Text(item.detail) }, trailingContent = { if (item.repairable) Button({ onFix(item.id) }) { Text(tr("Fix")) } }) } }
        }
        if (items.isEmpty()) OutlinedButton(onClick = onRefresh) { Text(tr("Run diagnosis")) }
    }
}

@Composable private fun TaskManagerTab(tasks: List<TaskRunItem>, onResume: (Long) -> Unit, onCancel: (Long) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(tr("Installation / Task Manager"), style = MaterialTheme.typography.headlineSmall); Text(tr("All long-running work is durable, cancellable and recoverable where the task declares resumability."), style = MaterialTheme.typography.bodySmall) }
        items(tasks, key = { it.id }) { task -> ListItem(headlineContent = { Text("#${task.id} ${task.label}") }, supportingContent = { Text("${task.type} • ${task.state} • attempt ${task.attempt}/${task.maxAttempts}${task.nextRunAt?.let { " • next ${java.util.Date(it)}" } ?: ""}\n${task.detail ?: ""}") }, trailingContent = { Row { if (task.state in setOf("INTERRUPTED", "PAUSED", "WAITING_APPROVAL") && task.resumable) TextButton({ onResume(task.id) }) { Text(tr("Resume")) }; if (task.state in setOf("QUEUED", "RUNNING", "RETRYING")) TextButton({ onCancel(task.id) }) { Text(tr("Cancel")) } } }) }
    }
}

@Composable private fun ApprovalTab(command: String, onCommandChange: (String) -> Unit, capability: ToolCapability, onCapability: (ToolCapability) -> Unit, ttl: Long, onTtl: (Long) -> Unit, granted: Boolean, onGrant: () -> Unit, onRevoke: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(tr("Dangerous Action Approval"), style = MaterialTheme.typography.headlineSmall); Text(tr("The UI is only an approval front-end. Actual execution must still pass the runtime safety gate and durable grant check."), style = MaterialTheme.typography.bodySmall) }
        item { OutlinedTextField(command, onCommandChange, label = { Text(tr("Command to classify")) }, modifier = Modifier.fillMaxWidth(), minLines = 2) }
        if (command.isNotBlank()) item { val a = ActionSafetyPolicy.assess(command); Card { ListItem(headlineContent = { Text(if (a.risk == ActionSafetyPolicy.Risk.REQUIRES_APPROVAL) "Approval required" else "Safe classification") }, supportingContent = { Text(a.reason) }) } }
        item { Text(tr("Capability grant"), style = MaterialTheme.typography.titleMedium) }
        // v4.4.5 (UX): FlowRow — the 6 capability chips overflowed a plain
        // Row and chips 4-6 were unreachable.
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) { ToolCapability.entries.filter { it != ToolCapability.READ_ONLY }.take(6).forEach { c -> FilterChip(selected = capability == c, onClick = { onCapability(c) }, label = { Text(c.name) }) } } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(5L to "5m", 15L to "15m", 60L to "1h").forEach { (minutes, label) -> FilterChip(selected = ttl == minutes * 60_000, onClick = { onTtl(minutes * 60_000) }, label = { Text(label) }) } } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(enabled = !granted, onClick = onGrant) { Text(tr("Allow")) }; OutlinedButton(enabled = granted, onClick = onRevoke) { Text(tr("Revoke")) } } }
    }
}

@Composable private fun LanStatusTab(
    status: com.anvex.core.android.reliability.LanAccessManager.Status,
    exposeLan: Boolean,
    onExposeLanChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr("Remote / LAN Status"), style = MaterialTheme.typography.headlineSmall)
        Text(tr("Read-only authenticated status only. Files, commands, provider credentials and agent execution are not exposed."), style = MaterialTheme.typography.bodySmall)
        ListItem(headlineContent = { Text(if (status.running) "Running" else "Stopped") }, supportingContent = { Text(status.address) })
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (status.running) "Authenticated status endpoint" else "Status endpoint", style = MaterialTheme.typography.titleMedium)
                if (!status.running) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("Expose on local network"), style = MaterialTheme.typography.bodyMedium)
                            Text(tr("Off = this device only. On = bind to LAN; use only on a trusted network."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = exposeLan, onCheckedChange = onExposeLanChange)
                    }
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text(if (exposeLan) "Start on LAN" else "Start on this device") }
                } else {
                    Text("Address: ${status.address}", style = MaterialTheme.typography.labelMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    Text(tr("Bearer token is held in memory and is hidden until you explicitly copy it."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            AppGraph.get().lanAccess.currentToken()?.let { clipboard.setText(androidx.compose.ui.text.AnnotatedString(it)) }
                        }) { Text(tr("Copy token")) }
                        OutlinedButton(onClick = onStop) { Text(tr("Stop")) }
                    }
                }
            }
        }
    }
}

@Composable private fun BackupTab(backups: List<com.anvex.core.android.db.BackupRecordEntity>, path: String, onSnapshot: () -> Unit, onRotate: () -> Unit) { LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { item { Text(tr("Recovery snapshots"), style = MaterialTheme.typography.headlineSmall); Text(tr("Automatic snapshots are Keystore-encrypted and credential-free."), style = MaterialTheme.typography.bodySmall); Text(path, style = MaterialTheme.typography.labelSmall); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = onSnapshot) { Text(tr("Create snapshot")) }; OutlinedButton(onClick = onRotate) { Text(tr("Keep newest 3")) } } }; items(backups, key = { it.id }) { b -> ListItem(headlineContent = { Text("${b.kind} • ${b.reason ?: "snapshot"}") }, supportingContent = { Text("${b.sizeBytes / 1024} KB • encrypted=${b.encrypted} • ${java.util.Date(b.createdAt)}") }) } } }
@Composable private fun SkillsTab(plugins: List<com.anvex.core.android.db.PluginEntity>, onImport: () -> Unit, onExport: () -> Unit, onToggle: (String, Boolean) -> Unit, onRemove: (String) -> Unit) { LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { item { Text(tr("Skills & plugins"), style = MaterialTheme.typography.headlineSmall); Text(tr("Declarative plugin packages and skills are capability-gated; arbitrary plugin code is never executed in the app process."), style = MaterialTheme.typography.bodySmall); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onImport) { Text(tr("Import")) }; OutlinedButton(onClick = onExport) { Text(tr("Export")) } } }; items(plugins, key = { it.id }) { p -> ListItem(leadingContent = { Icon(Icons.Default.Extension, null) }, headlineContent = { Text(p.name) }, supportingContent = { Text("${p.version} • ${p.source}\n${p.description}") }, trailingContent = { Row { Switch(p.enabled, { onToggle(p.id, it) }, enabled = !p.builtIn); if (!p.builtIn) TextButton({ onRemove(p.id) }) { Text(tr("Remove")) } } }) } } }
@Composable private fun ActivityTab(events: List<com.anvex.core.android.db.DiagnosticEventEntity>) { LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { items(events, key = { it.id }) { e -> ListItem(headlineContent = { Text("${e.level} • ${e.category}") }, supportingContent = { Text("${e.message}\n${e.details ?: ""}") }) } } }
