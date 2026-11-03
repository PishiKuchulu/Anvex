package com.anvex.app.ui.screens.github

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.anvex.core.android.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.anvex.app.i18n.tr

/**
 * UI #5: GitHub status is TYPED — the old code colored it by checking
 * `contains("failed")` on an English string, so localized or reworded
 * failures rendered green. Color now derives from the type.
 */
private sealed interface GhStatus {
    val text: String
    data class Ok(override val text: String) : GhStatus
    data class Err(override val text: String) : GhStatus
    data class Busy(override val text: String) : GhStatus
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubScreen(nav: NavController, projectId: String) {
    val graph = AppGraph.get()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var token by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var status by remember { mutableStateOf<GhStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showPullConfirm by remember { mutableStateOf(false) }
    // Status strings resolved in composition (coroutine/flow callbacks
    // below are not composable and cannot call tr() themselves).
    val enterClientIdText = tr("Enter your GitHub OAuth client ID first.")
    val connectedText = tr("GitHub connected — token saved securely in Android Keystore.")
    val saveTokenFailText = tr("Could not save the GitHub token.")
    val expiredText = tr("The code expired. Start again to get a new one.")
    val startFailText = tr("Could not start GitHub authorization.")
    val tokenSavedText = tr("Token saved securely in Android Keystore.")
    val tokenClearedText = tr("Saved GitHub token cleared.")
    val pushingText = tr("Pushing…")
    val pullingText = tr("Pulling…")
    val pushFailText = tr("GitHub push failed.")
    val pullFailText = tr("GitHub pull failed.")
    val codeCopiedText = tr("Code copied.")
    val pushedText = tr("Pushed")
    val pulledText = tr("Pulled")
    val filesToText = tr("file(s) to")
    val filesFromText = tr("file(s) from")
    val skippedText = tr("Skipped")
    val protectedFilesText = tr("protected file(s)")
    // v4.4.6: GitHub Device Flow state.
    var clientId by remember { mutableStateOf(graph.github.oauthClientId()) }
    var device by remember { mutableStateOf<com.anvex.core.android.repo.GitHubRepository.DeviceCode?>(null) }
    var deviceBusy by remember { mutableStateOf(false) }
    var pollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun stopDeviceFlow() {
        pollJob?.cancel()
        pollJob = null
        device = null
        deviceBusy = false
    }

    fun startDeviceFlow() {
        val id = clientId.trim()
        if (id.isEmpty()) {
            status = GhStatus.Err(enterClientIdText)
            return
        }
        graph.github.saveOauthClientId(id)
        deviceBusy = true
        status = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { graph.github.requestDeviceCode(id) }
            result.fold(
                onSuccess = { code ->
                    device = code
                    deviceBusy = false
                    // Poll until granted / failed / cancelled.
                    pollJob = scope.launch {
                        var interval = code.intervalSec
                        val deadline = System.currentTimeMillis() + code.expiresInSec * 1000L
                        while (System.currentTimeMillis() < deadline) {
                            kotlinx.coroutines.delay(interval * 1000L)
                            when (val poll = withContext(Dispatchers.IO) { graph.github.pollDeviceToken(id, code) }) {
                                is com.anvex.core.android.repo.GitHubRepository.DevicePoll.Granted -> {
                                    runCatching { graph.github.saveToken(poll.accessToken) }
                                        .onSuccess { status = GhStatus.Ok(connectedText) }
                                        .onFailure { status = GhStatus.Err(it.message ?: saveTokenFailText) }
                                    stopDeviceFlow()
                                    return@launch
                                }
                                is com.anvex.core.android.repo.GitHubRepository.DevicePoll.Failed -> {
                                    status = GhStatus.Err(poll.message)
                                    stopDeviceFlow()
                                    return@launch
                                }
                                is com.anvex.core.android.repo.GitHubRepository.DevicePoll.SlowDown -> interval += 5
                                is com.anvex.core.android.repo.GitHubRepository.DevicePoll.Pending -> Unit
                            }
                        }
                        status = GhStatus.Err(expiredText)
                        stopDeviceFlow()
                    }
                },
                onFailure = {
                    status = GhStatus.Err(it.message ?: startFailText)
                    deviceBusy = false
                },
            )
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(tr("GitHub Sync")) }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back")) } }) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("GitHub REST sync"), style = MaterialTheme.typography.titleMedium)
                    Text(tr("Push or pull project files without requiring a git executable. Pull replaces matching workspace files with the selected repository branch."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // v4.4.6: one-tap GitHub authorization via the Device Flow.
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Connect with GitHub"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        tr("Tap Connect, enter the shown code on GitHub, and Anvex receives a token automatically — no manual token paste needed."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        clientId,
                        { clientId = it },
                        label = { Text(tr("OAuth client ID")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        tr("Create once at github.com → Settings → Developer settings → OAuth Apps (any name, no callback URL needed for device flow), then paste the Client ID here."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { startDeviceFlow() },
                        enabled = !deviceBusy && !busy && clientId.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (deviceBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(tr("Connect with GitHub"))
                    }
                }
            }
            OutlinedTextField(token, { token = it }, label = { Text(tr("Fine-grained GitHub token")) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(owner, { owner = it }, label = { Text(tr("Owner")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(repo, { repo = it }, label = { Text(tr("Repository")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(branch, { branch = it }, label = { Text(tr("Branch")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { graph.github.saveToken(token); token = ""; status = GhStatus.Ok(tokenSavedText) }, enabled = token.isNotBlank() && !busy, modifier = Modifier.weight(1f)) {
                    Text(tr("Save token"))
                }
                OutlinedButton(onClick = { graph.github.clearToken(); status = GhStatus.Ok(tokenClearedText) }, enabled = graph.github.hasToken() && !busy, modifier = Modifier.weight(1f)) {
                    Text(tr("Clear token"))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy && owner.isNotBlank() && repo.isNotBlank() && graph.github.hasToken(),
                    onClick = {
                        busy = true; status = GhStatus.Busy(pushingText)
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { graph.github.push(graph.projects.fileSystemForAsync(projectId), owner, repo, branch) }
                            status = result.fold({ outcome ->
                                GhStatus.Ok(
                                    "$pushedText ${outcome.count} $filesToText $owner/$repo@$branch." +
                                        if (outcome.skipped.isEmpty()) "" else " $skippedText ${outcome.skipped.size} $protectedFilesText: ${outcome.skipped.take(3).joinToString(", ")}${if (outcome.skipped.size > 3) "…" else ""}"
                                )
                            }, { GhStatus.Err(it.message ?: pushFailText) })
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
            status?.let { s -> Text(s.text, color = if (s is GhStatus.Err) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
        }
    }

    // Device-flow dialog: shows the user code while polling for authorization.
    device?.let { code ->
        AlertDialog(
            onDismissRequest = { stopDeviceFlow() },
            title = { Text(tr("Authorize on GitHub")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr("Enter this code on GitHub to connect Anvex:"), style = MaterialTheme.typography.bodyMedium)
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                code.userCode,
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.width(12.dp))
                            IconButton(onClick = {
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("GitHub code", code.userCode))
                                status = GhStatus.Ok(codeCopiedText)
                            }) {
                                Icon(androidx.compose.material.icons.Icons.Filled.ContentCopy, contentDescription = tr("Copy"))
                            }
                        }
                    }
                    Text(
                        tr("The code expires in 15 minutes. Waiting for authorization…"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(code.verificationUri)))
                    }
                }) { Text(tr("Open GitHub")) }
            },
            dismissButton = {
                TextButton(onClick = { stopDeviceFlow() }) { Text(tr("Cancel")) }
            },
        )
    }

    if (showPullConfirm) {
        AlertDialog(
            onDismissRequest = { if (!busy) showPullConfirm = false },
            title = { Text(tr("Pull from GitHub?")) },
            text = { Text("${tr("This will write the files from")} $owner/$repo@$branch ${tr("into the current workspace. Continue only if the remote branch is the version you want.")}") },
            confirmButton = {
                TextButton(onClick = {
                    showPullConfirm = false; busy = true; status = GhStatus.Busy(pullingText)
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { graph.github.pull(graph.projects.fileSystemForAsync(projectId), owner, repo, branch) }
                        status = result.fold({ outcome ->
                            GhStatus.Ok(
                                "$pulledText ${outcome.count} $filesFromText $owner/$repo@$branch." +
                                    if (outcome.skipped.isEmpty()) "" else " $skippedText ${outcome.skipped.size} $protectedFilesText."
                            )
                        }, { GhStatus.Err(it.message ?: pullFailText) })
                        busy = false
                    }
                }) { Text(tr("Pull")) }
            },
            dismissButton = { TextButton(onClick = { showPullConfirm = false }) { Text(tr("Cancel")) } },
        )
    }
}
