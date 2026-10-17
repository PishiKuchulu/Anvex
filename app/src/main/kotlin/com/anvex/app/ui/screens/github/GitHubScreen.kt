package com.anvex.app.ui.screens.github

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.anvex.core.android.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.anvex.app.i18n.tr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubScreen(nav: NavController, projectId: String) {
    val graph = AppGraph.get()
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showPullConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(tr("GitHub Sync")) }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("GitHub REST sync"), style = MaterialTheme.typography.titleMedium)
                    Text(tr("Push or pull project files without requiring a git executable. Pull replaces matching workspace files with the selected repository branch."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(token, { token = it }, label = { Text(tr("Fine-grained GitHub token")) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(owner, { owner = it }, label = { Text(tr("Owner")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(repo, { repo = it }, label = { Text(tr("Repository")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(branch, { branch = it }, label = { Text(tr("Branch")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { graph.github.saveToken(token); token = ""; status = "Token saved securely in Android Keystore." }, enabled = token.isNotBlank() && !busy, modifier = Modifier.weight(1f)) {
                    Text(tr("Save token"))
                }
                OutlinedButton(onClick = { graph.github.clearToken(); status = "Saved GitHub token cleared." }, enabled = graph.github.hasToken() && !busy, modifier = Modifier.weight(1f)) {
                    Text(tr("Clear token"))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy && owner.isNotBlank() && repo.isNotBlank() && graph.github.hasToken(),
                    onClick = {
                        busy = true; status = "Pushing…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { graph.github.push(graph.projects.fileSystemForAsync(projectId), owner, repo, branch) }
                            status = result.fold({ "Pushed ${it} file(s) to $owner/$repo@$branch." }, { it.message ?: "GitHub push failed." })
                            busy = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Icon(Icons.Filled.CloudUpload, null); Spacer(Modifier.width(6.dp)); Text(tr("Push")) }
                OutlinedButton(
                    enabled = !busy && owner.isNotBlank() && repo.isNotBlank() && graph.github.hasToken(),
                    onClick = { showPullConfirm = true },
                    modifier = Modifier.weight(1f),
                ) { Icon(Icons.Filled.CloudDownload, null); Spacer(Modifier.width(6.dp)); Text(tr("Pull")) }
            }
            status?.let { Text(it, color = if (it.contains("failed", true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
        }
    }

    if (showPullConfirm) {
        AlertDialog(
            onDismissRequest = { if (!busy) showPullConfirm = false },
            title = { Text(tr("Pull from GitHub?")) },
            text = { Text("This will write the files from $owner/$repo@$branch into the current workspace. Continue only if the remote branch is the version you want.") },
            confirmButton = {
                TextButton(onClick = {
                    showPullConfirm = false; busy = true; status = "Pulling…"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { graph.github.pull(graph.projects.fileSystemForAsync(projectId), owner, repo, branch) }
                        status = result.fold({ "Pulled ${it} file(s) from $owner/$repo@$branch." }, { it.message ?: "GitHub pull failed." })
                        busy = false
                    }
                }) { Text(tr("Pull")) }
            },
            dismissButton = { TextButton(onClick = { showPullConfirm = false }) { Text(tr("Cancel")) } },
        )
    }
}
