package com.anvex.app.ui.screens.extensions

import androidx.activity.compose.rememberLauncherForActivityResult
import com.anvex.app.vm.ExtensionsViewModel
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.anvex.app.i18n.tr

/** Phase 6: add and manage skills and MCP servers directly from the app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(nav: NavController) {
    val vm: ExtensionsViewModel = viewModel { ExtensionsViewModel() }
    val skills by vm.skills.collectAsState()
    val servers by vm.servers.collectAsState()
    val tools by vm.tools.collectAsState()
    val status by vm.status.collectAsState()
    val busy by vm.busy.collectAsState()

    var tab by remember { mutableStateOf(0) }
    var githubUrl by remember { mutableStateOf("") }
    var serverName by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }
    var serverToken by remember { mutableStateOf("") }

    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.installSkillZip(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Skills & MCP")) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxWidth()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(tr("Skills")) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(tr("MCP servers")) })
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            status?.let {
                Text(
                    it,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (tab == 0) {
                LazyColumn(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(tr("Add a skill"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Skills are declarative instructions. They cannot run code on this device.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(
                            onClick = { zipPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                            enabled = !busy,
                        ) { Text(tr("Install from ZIP")) }
                    }
                    item {
                        OutlinedTextField(
                            value = githubUrl,
                            onValueChange = { githubUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(tr("GitHub repository (https://github.com/owner/repo)")) },
                        )
                        Button(
                            onClick = { vm.installSkillFromGitHub(githubUrl) },
                            enabled = !busy && githubUrl.trim().startsWith("https://github.com/"),
                        ) { Text(tr("Install from GitHub")) }
                    }
                    item { HorizontalDivider() }
                    item { Text("Installed (${skills.size})", style = MaterialTheme.typography.titleSmall) }
                    if (skills.isEmpty()) {
                        item { Text(tr("No skills installed yet."), style = MaterialTheme.typography.bodySmall) }
                    }
                    items(skills, key = { it.id }) { plugin ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(plugin.name, style = MaterialTheme.typography.titleSmall)
                                Text("${plugin.version} · ${plugin.source}", style = MaterialTheme.typography.bodySmall)
                                Text(plugin.description, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                            }
                            Switch(
                                checked = plugin.enabled,
                                onCheckedChange = { vm.toggleSkill(plugin.id, it) },
                                enabled = !plugin.builtIn,
                            )
                            if (!plugin.builtIn) {
                                TextButton(onClick = { vm.removeSkill(plugin.id) }) { Text(tr("Remove")) }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(tr("Add an MCP server"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Remote servers using streamable HTTP. HTTPS is required. Tokens are stored encrypted.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = serverName,
                            onValueChange = { serverName = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(tr("Name")) },
                        )
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(tr("Server URL (https://…)")) },
                        )
                        OutlinedTextField(
                            value = serverToken,
                            onValueChange = { serverToken = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(tr("Bearer token (optional)")) },
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        Button(
                            onClick = {
                                vm.addServer(serverName, serverUrl, serverToken)
                                if (serverUrl.isNotBlank()) {
                                    serverName = ""
                                    serverUrl = ""
                                    serverToken = ""
                                }
                            },
                            enabled = !busy && serverUrl.isNotBlank(),
                        ) { Text(tr("Add server")) }
                    }
                    item { HorizontalDivider() }
                    item { Text("Servers (${servers.size})", style = MaterialTheme.typography.titleSmall) }
                    if (servers.isEmpty()) {
                        item { Text(tr("No MCP servers added yet."), style = MaterialTheme.typography.bodySmall) }
                    }
                    items(servers, key = { it.id }) { server ->
                        Column(Modifier.fillMaxWidth()) {
                            Text(server.name, style = MaterialTheme.typography.titleSmall)
                            Text(server.url, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            tools[server.id]?.let { names ->
                                Text(
                                    "Tools: ${names.joinToString().ifBlank { "none" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(tr("Let the assistant call tools"), style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (server.tools.isEmpty()) "Test the connection first to load tools."
                                        else "Calls run only for the tools listed above.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Switch(
                                    checked = server.allowCalls,
                                    onCheckedChange = { vm.setAllowCalls(server, it) },
                                    enabled = server.tools.isNotEmpty(),
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { vm.testServer(server) }, enabled = !busy) {
                                    Text(tr("Test connection"))
                                }
                                TextButton(onClick = { vm.removeServer(server.id) }) { Text(tr("Remove")) }
                            }
                        }
                    }
                }
            }
        }
    }
}
