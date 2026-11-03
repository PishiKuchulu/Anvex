package com.anvex.app.ui.screens.git

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.anvex.app.ui.Routes
import com.anvex.app.i18n.tr
import com.anvex.app.ui.theme.AnvexDimens
import com.anvex.app.ui.theme.AnvexMono
import com.anvex.core.android.AppGraph
import com.anvex.core.android.runtime.git.GitBranchInfo
import com.anvex.core.android.runtime.git.GitBridge
import com.anvex.core.android.runtime.git.GitCommitInfo
import com.anvex.core.android.runtime.git.GitMirror
import com.anvex.core.android.runtime.git.GitRepository
import com.anvex.core.android.runtime.git.GitStatusEntry
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local Git panel (F1, P3) backed by libgit2: status + staging, commits,
 * branches, checkout/merge, conflict entry points and recent history.
 * Local operations only — clone/fetch/push stay on the GitHub screen.
 * SAF projects operate on the app-private mirror with explicit sync.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GitScreen(navController: NavHostController, projectId: String) {
    val graph = AppGraph.get()
    val scope = rememberCoroutineScope()

    var root by remember { mutableStateOf<GitRoot?>(null) }
    var repo by remember { mutableStateOf<GitRepository?>(null) }
    var isRepo by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<List<GitStatusEntry>>(emptyList()) }
    var branches by remember { mutableStateOf<List<GitBranchInfo>>(emptyList()) }
    var log by remember { mutableStateOf<List<GitCommitInfo>>(emptyList()) }
    var conflicts by remember { mutableStateOf<List<String>>(emptyList()) }
    var diffText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<String?>(null) }
    var commitMessage by remember { mutableStateOf("") }
    var authorName by remember { mutableStateOf("Anvex User") }
    var authorEmail by remember { mutableStateOf("anvex@localhost") }
    var showNewBranch by remember { mutableStateOf(false) }
    var newBranchName by remember { mutableStateOf("") }

    suspend fun refresh() {
        val r = repo ?: return
        busy = true
        isRepo = r.isRepo
        if (isRepo) {
            status = r.status().getOrDefault(emptyList())
            branches = r.branches().getOrDefault(emptyList())
            log = r.log(30).getOrDefault(emptyList())
            conflicts = r.conflicts().getOrDefault(emptyList())
            diffText = r.diff().getOrDefault("")
        }
        busy = false
    }

    LaunchedEffect(projectId) {
        busy = true
        val resolved = resolveGitRoot(graph, projectId)
        root = resolved
        if (resolved != null) {
            repo = GitRepository(resolved.dir.absolutePath)
            // First open of a mirrored project: pull the project files in.
            if (resolved.mirrored && !GitRepository(resolved.dir.absolutePath).isRepo) {
                val fs = graph.androidRuntime.fileSystem(projectId)
                if (fs != null) GitMirror.syncIn(fs, resolved.dir)
            }
            refresh()
        } else {
            busy = false
        }
    }

    fun act(block: suspend () -> Result<*>, done: String? = null) {
        scope.launch {
            busy = true
            val result = block()
            notice = result.fold(
                onSuccess = { done },
                onFailure = { it.message ?: "Git operation failed" },
            )
            refresh()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Git")) },
                navigationIcon = {
                    IconButton({ navController.popBackStack() }) {
                        Icon(Icons.Default.Close, contentDescription = tr("Close"))
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { refresh() } }, enabled = !busy) {
                        Icon(Icons.Default.Refresh, contentDescription = tr("Refresh"))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (!GitBridge.available) {
                Text(
                    tr("Git is unavailable on this device"),
                    Modifier.padding(AnvexDimens.page),
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }
            notice?.let {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val currentRoot = root
            if (currentRoot == null) {
                Text(tr("No workspace available"), Modifier.padding(AnvexDimens.page))
                return@Column
            }
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = AnvexDimens.page),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                item {
                    Column {
                        Text(currentRoot.dir.absolutePath, style = MaterialTheme.typography.bodySmall, fontFamily = AnvexMono)
                        if (currentRoot.mirrored) {
                            Text(tr("Mirrored workspace"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        val fs = graph.androidRuntime.fileSystem(projectId)
                                        notice = if (fs == null) "No project filesystem" else
                                            GitMirror.syncIn(fs, currentRoot.dir).fold(
                                                onSuccess = { n -> "Synced $n file(s) from the project" },
                                                onFailure = { e -> e.message ?: "Sync failed" },
                                            )
                                        refresh()
                                    }
                                }) { Text(tr("Sync from project")) }
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        val fs = graph.androidRuntime.fileSystem(projectId)
                                        notice = if (fs == null) "No project filesystem" else
                                            GitMirror.syncOut(fs, currentRoot.dir).fold(
                                                onSuccess = { n -> "Published $n file(s) to the project" },
                                                onFailure = { e -> e.message ?: "Publish failed" },
                                            )
                                    }
                                }) { Text(tr("Publish to project")) }
                            }
                        }
                        Text(tr("Local git only"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!isRepo) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(tr("Not a git repository"), style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = { act({ repo!!.init() }, done = "Repository initialized") }) {
                                    Text(tr("Initialize repository"))
                                }
                            }
                        }
                    }
                } else {
                    if (conflicts.isNotEmpty()) {
                        item {
                            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("${tr("Conflicts")} (${conflicts.size})", style = MaterialTheme.typography.titleSmall)
                                    for (path in conflicts) {
                                        Text(
                                            path,
                                            Modifier.fillMaxWidth().clickable {
                                                navController.navigate(Routes.gitConflict(projectId, path))
                                            }.padding(vertical = 6.dp),
                                            fontFamily = AnvexMono,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    OutlinedButton(onClick = { act({ repo!!.abortMerge() }, done = "Merge aborted") }) {
                                        Text(tr("Abort merge"))
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${tr("Changes")} (${status.size})", style = MaterialTheme.typography.titleSmall)
                                    TextButton(onClick = { act({ repo!!.add(".") }, done = "All changes staged") }, enabled = status.isNotEmpty()) {
                                        Text(tr("Stage all"))
                                    }
                                }
                                if (status.isEmpty()) {
                                    Text(tr("Working tree clean"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                for (entry in status) {
                                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("${entry.index}${entry.workdir}", fontFamily = AnvexMono, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                        Text(entry.path, fontFamily = AnvexMono, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(tr("Commit"), style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(commitMessage, { commitMessage = it.take(500) }, Modifier.fillMaxWidth(), label = { Text(tr("Commit message")) })
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(authorName, { authorName = it.take(100) }, Modifier.weight(1f), label = { Text(tr("Author name")) }, singleLine = true)
                                    OutlinedTextField(authorEmail, { authorEmail = it.take(100) }, Modifier.weight(1f), label = { Text(tr("Author email")) }, singleLine = true)
                                }
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        act({ repo!!.commit(commitMessage.trim(), authorName.trim(), authorEmail.trim()) }, done = "Committed")
                                        commitMessage = ""
                                    },
                                    enabled = commitMessage.isNotBlank() && status.any { it.index != '.' && it.index != '?' },
                                ) { Text(tr("Commit")) }
                            }
                        }
                    }
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(tr("Branches"), style = MaterialTheme.typography.titleSmall)
                                    TextButton(onClick = { showNewBranch = true }) { Text(tr("New branch")) }
                                }
                                for (branch in branches) {
                                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            (if (branch.current) "● " else "○ ") + branch.name,
                                            Modifier.weight(1f),
                                            fontFamily = AnvexMono,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        if (!branch.current) {
                                            TextButton(onClick = { act({ repo!!.checkout(branch.name) }, done = "Checked out ${branch.name}") }) {
                                                Text(tr("Checkout"))
                                            }
                                            TextButton(onClick = {
                                                act({ repo!!.merge(branch.name) }, done = "Merged ${branch.name}")
                                            }) { Text(tr("Merge")) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (diffText.isNotBlank()) {
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(tr("Diff"), style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        diffText.take(64_000),
                                        fontFamily = AnvexMono,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(tr("History"), style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.height(8.dp))
                                if (log.isEmpty()) {
                                    Text(tr("No commits yet"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                for (commit in log) {
                                    Column(Modifier.padding(vertical = 4.dp)) {
                                        Text(commit.subject, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${commit.oid.take(8)} · ${commit.authorName} · ${formatEpoch(commit.epochSeconds)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewBranch) {
        AlertDialog(
            onDismissRequest = { showNewBranch = false },
            title = { Text(tr("New branch")) },
            text = {
                OutlinedTextField(newBranchName, { newBranchName = it.take(100) }, label = { Text(tr("Branch name")) }, singleLine = true)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newBranchName.trim()
                        showNewBranch = false
                        newBranchName = ""
                        if (name.isNotEmpty()) act({ repo!!.createBranch(name) }, done = "Branch created")
                    },
                    enabled = newBranchName.isNotBlank(),
                ) { Text(tr("Create")) }
            },
            dismissButton = { TextButton(onClick = { showNewBranch = false }) { Text(tr("Cancel")) } },
        )
    }
}

private fun formatEpoch(epochSeconds: Long): String =
    if (epochSeconds <= 0) "" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochSeconds * 1000))
