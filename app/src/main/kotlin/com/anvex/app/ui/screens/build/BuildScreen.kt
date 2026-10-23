package com.anvex.app.ui.screens.build

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.anvex.app.i18n.tr
import com.anvex.app.ui.Routes
import com.anvex.app.vm.BuildViewModel
import java.text.DateFormat
import java.util.Date

/**
 * v4.0 (GOAL 3): in-app APK build surface — real Gradle runs through the
 * imported toolchain with live log streaming and honest status reporting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildScreen(nav: NavController, projectId: String) {
    val vm: BuildViewModel = remember(projectId) { BuildViewModel(projectId) }
    val toolchain by vm.toolchainStatus.collectAsState()
    val logLines by vm.logLines.collectAsState()
    val building by vm.building.collectAsState()
    val buildError by vm.buildError.collectAsState()
    val importMessage by vm.importMessage.collectAsState()
    val artifacts by vm.artifacts.collectAsState()
    val useWrapper by vm.useWrapper.collectAsState()
    val riskAcknowledged by vm.wrapperRiskAcknowledged.collectAsState()
    var variant by remember { mutableStateOf("debug") }

    // Cloud build (GitHub Actions) state — same saved token as GitHub Sync.
    val cloudBusy by vm.cloudBusy.collectAsState()
    val cloudStatus by vm.cloudStatus.collectAsState()
    val cloudError by vm.cloudError.collectAsState()
    val cloudRun by vm.cloudRun.collectAsState()
    val cloudLogLines by vm.cloudLogLines.collectAsState()
    var cloudOwner by remember { mutableStateOf("") }
    var cloudRepo by remember { mutableStateOf("") }
    var cloudBranch by remember { mutableStateOf("main") }
    var cloudVariant by remember { mutableStateOf("debug") }
    var cloudLogOpen by remember { mutableStateOf(false) }
    var cloudOpenError by remember { mutableStateOf<String?>(null) }
    // v4.4.5 (UX): removing the toolchain bundle is expensive to undo.
    var confirmRemoveToolchain by remember { mutableStateOf(false) }
    var cloudToken by remember { mutableStateOf("") }
    var tokenMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var hasGithubToken by remember { mutableStateOf(vm.hasGithubToken()) }
    val cloudReady = hasGithubToken && cloudOwner.isNotBlank() && cloudRepo.isNotBlank() && cloudBranch.isNotBlank()
    val runSucceeded = cloudRun?.status.equals("completed", ignoreCase = true) &&
        cloudRun?.conclusion.equals("success", ignoreCase = true)
    val noBrowserError = tr("No browser app is available to open the run page.")

    val pickBundle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importToolchain(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Build APK")) },
                navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---- toolchain ------------------------------------------------------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("On-device toolchain"), style = MaterialTheme.typography.titleMedium)
                    // v4.10.1 (ARCH-01): say it BEFORE the user imports a
                    // toolchain and taps Build — Android 10+ (W^X) forbids
                    // executing binaries from app storage, so on-device
                    // Gradle cannot run on modern devices.
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        Text(
                            tr("On Android 10 and newer, on-device Gradle builds are blocked by the system (W^X policy). The cloud build below is the working path."),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val status = toolchain
                    if (status.valid) {
                        Text(
                            tr("Toolchain imported") + " — JDK ${status.manifest?.jdkVersion ?: "?"}, " +
                                "Gradle ${status.manifest?.gradleVersion ?: "?"}, ABI ${status.manifest?.abi ?: "?"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            tr("No valid toolchain imported.") + " " +
                                tr("Import a bundle with an Android JDK, SDK and Gradle built for this device."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        status.problems.take(2).forEach {
                            Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { pickBundle.launch(arrayOf("application/zip")) }, enabled = !building, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Archive, null); Spacer(Modifier.width(6.dp)); Text(tr("Import bundle"))
                        }
                        OutlinedButton(
                            onClick = { confirmRemoveToolchain = true },
                            enabled = !building && toolchain.valid,
                            modifier = Modifier.weight(1f),
                        ) { Text(tr("Remove")) }
                    }
                    importMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }

            // ---- build controls -------------------------------------------------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Build"), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = variant == "debug", onClick = { variant = "debug" }, label = { Text("debug") })
                        FilterChip(selected = variant == "release", onClick = { variant = "release" }, label = { Text("release") })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = useWrapper, onCheckedChange = { vm.setUseWrapper(it) })
                        Column {
                            Text(tr("Use project wrapper (./gradlew)"), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                tr("Build scripts are executable project code — requires the risk acknowledgement."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (useWrapper) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(checked = riskAcknowledged, onCheckedChange = { vm.setWrapperRiskAcknowledged(it) })
                            Text(tr("I reviewed the build scripts and accept the risk"), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.startBuild(variant) },
                            enabled = !building && (!useWrapper || riskAcknowledged),
                            modifier = Modifier.weight(1f),
                        ) { Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(tr("Start build")) }
                        OutlinedButton(onClick = { vm.cancelBuild() }, enabled = building, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Close, null); Spacer(Modifier.width(6.dp)); Text(tr("Cancel"))
                        }
                    }
                    buildError?.let { errorText ->
                        Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        // v4.4 (architect feature 5 — Auto-Fix): hand the failure to the
                        // assistant with the real log tail. The user stays in control —
                        // the share intake shows exactly what will be sent.
                        val fixPrompt = "The Android build failed with this error. Fix the cause and re-emit the affected files:\n\n$errorText\n\nRecent log lines:\n" +
                            logLines.takeLast(20).joinToString("\n").take(4_000)
                        TextButton(onClick = {
                            com.anvex.app.share.IncomingShare.offer(
                                com.anvex.app.share.IncomingShare.Payload(fixPrompt, emptyList())
                            )
                            nav.navigate(Routes.chat(projectId))
                        }) { Text(tr("Ask the agent to fix it")) }
                    }
                }
            }

            // ---- cloud build (GitHub Actions) ---------------------------------
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Cloud build (GitHub Actions)"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        tr("Pushes this project to GitHub, builds the APK on GitHub Actions and downloads the result onto this phone. Uses the token saved in GitHub Sync; the repository must already exist with at least one commit on its default branch."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!hasGithubToken) {
                        Text(
                            tr("A GitHub token with repository Contents and Actions access is required."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedTextField(
                            cloudToken,
                            { cloudToken = it },
                            label = { Text(tr("Fine-grained GitHub token")) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                tokenMessage = vm.saveGithubToken(cloudToken)
                                cloudToken = ""
                                hasGithubToken = vm.hasGithubToken()
                            },
                            enabled = cloudToken.isNotBlank() && !cloudBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(tr("Save token")) }
                        tokenMessage?.let {
                            Text(
                                tr(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (it.contains("invalid", true) || it.contains("could not", true)) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                    }
                    OutlinedTextField(cloudOwner, { cloudOwner = it }, label = { Text(tr("Owner")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(cloudRepo, { cloudRepo = it }, label = { Text(tr("Repository")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(cloudBranch, { cloudBranch = it }, label = { Text(tr("Branch")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = cloudVariant == "debug", onClick = { cloudVariant = "debug" }, label = { Text("debug") })
                        FilterChip(selected = cloudVariant == "release", onClick = { cloudVariant = "release" }, label = { Text("release") })
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.pushAndBuild(cloudOwner.trim(), cloudRepo.trim(), cloudBranch.trim(), cloudVariant) },
                            enabled = !cloudBusy && cloudReady,
                            modifier = Modifier.weight(1f),
                        ) { Icon(Icons.Filled.CloudUpload, null); Spacer(Modifier.width(6.dp)); Text(tr("Push project & build")) }
                        if (cloudBusy) {
                            OutlinedButton(onClick = { vm.stopCloudWatch() }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.Close, null); Spacer(Modifier.width(6.dp)); Text(tr("Stop watching"))
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = !cloudBusy && cloudRun?.htmlUrl?.startsWith("https://") == true,
                            onClick = {
                                val url = cloudRun?.htmlUrl
                                if (url != null && url.startsWith("https://")) {
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        cloudOpenError = null
                                    } catch (missing: android.content.ActivityNotFoundException) {
                                        cloudOpenError = noBrowserError
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Icon(Icons.Filled.OpenInNew, null); Spacer(Modifier.width(6.dp)); Text(tr("Open run on GitHub")) }
                        if (runSucceeded) {
                            Button(
                                onClick = { vm.downloadCloudApk(cloudOwner.trim(), cloudRepo.trim()) },
                                enabled = !cloudBusy && cloudReady,
                                modifier = Modifier.weight(1f),
                            ) { Icon(Icons.Filled.CloudDownload, null); Spacer(Modifier.width(6.dp)); Text(tr("Download APK")) }
                        }
                    }
                    if (cloudBusy) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            cloudStatus?.let { Text(tr(it), style = MaterialTheme.typography.bodySmall) }
                        }
                    } else {
                        cloudStatus?.let {
                            Text(tr(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    cloudError?.let { Text(tr(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    cloudOpenError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = cloudRun != null,
                            onClick = {
                                if (cloudLogOpen) {
                                    cloudLogOpen = false
                                } else {
                                    cloudLogOpen = true
                                    vm.tailCloudLog(cloudOwner.trim(), cloudRepo.trim())
                                }
                            },
                        ) { Text(if (cloudLogOpen) tr("Hide build log") else tr("Tail build log")) }
                        if (cloudRun != null && !cloudBusy) {
                            TextButton(onClick = { vm.refreshCloudRun(cloudOwner.trim(), cloudRepo.trim(), cloudBranch.trim()) }) {
                                Text(tr("Refresh status"))
                            }
                        }
                    }
                    if (cloudLogOpen && cloudLogLines.isNotEmpty()) {
                        SelectionContainer {
                            LazyColumn(
                                Modifier.fillMaxWidth().heightIn(max = 320.dp).background(Color(0x0A000000)),
                            ) {
                                // v4.4.4 (smoothness): stable keys for log inserts.
                                items(cloudLogLines.size, key = { it }) { i ->
                                    val line = cloudLogLines[i]
                                    Text(
                                        line,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---- live log --------------------------------------------------------
            if (logLines.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (building) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text(tr("Build log"), style = MaterialTheme.typography.titleSmall)
                        }
                        SelectionContainer {
                            LazyColumn(
                                Modifier.fillMaxWidth().heightIn(max = 320.dp).background(Color(0x0A000000)),
                            ) {
                                // v4.4.4 (smoothness): stable keys for log inserts.
                                items(logLines.size, key = { it }) { i ->
                                    val line = logLines[i]
                                    Text(
                                        line,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---- artifacts -------------------------------------------------------
            if (artifacts.isNotEmpty()) {
                Text(tr("Build artifacts"), style = MaterialTheme.typography.titleMedium)
                artifacts.forEach { artifact ->
                    Card(Modifier.fillMaxWidth()) {
                        ListItem(
                            overlineContent = { Text(artifact.variant.uppercase(), style = MaterialTheme.typography.labelSmall) },
                            headlineContent = { Text(artifact.file.name, style = MaterialTheme.typography.bodySmall) },
                            supportingContent = {
                                Text(
                                    "${artifact.file.length() / (1024 * 1024)} MB · " +
                                        DateFormat.getDateTimeInstance().format(Date(artifact.createdAt)),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { vm.deleteArtifact(artifact) }) { Icon(Icons.Filled.Delete, "Delete") }
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (confirmRemoveToolchain) {
        com.anvex.app.ui.components.ConfirmDialog(
            title = tr("Remove toolchain?"),
            text = tr("The imported Android JDK/SDK/Gradle bundle will be deleted from this device. You will need to import it again to build."),
            confirmLabel = tr("Remove"),
            destructive = true,
            onConfirm = { vm.removeToolchain(); confirmRemoveToolchain = false },
            onDismiss = { confirmRemoveToolchain = false },
        )
    }
}
