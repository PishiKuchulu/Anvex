package com.anvex.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory as MemoryIcon
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.anvex.app.i18n.tr
import com.anvex.app.ui.Routes
import com.anvex.app.ui.components.AnvexCard
import com.anvex.app.ui.components.AnvexIconTile
import com.anvex.app.ui.components.AnvexSectionHeader
import com.anvex.app.ui.components.AnvexStatusBadge
import com.anvex.app.ui.components.AnvexStatus
import com.anvex.app.ui.screens.home.formatSize
import com.anvex.app.vm.SettingsViewModel
import com.anvex.core.android.AppGraph
import com.anvex.core.android.repo.AppLanguage
import com.anvex.core.android.repo.NodeBackend
import com.anvex.core.android.repo.ThemeMode
import com.anvex.core.android.repo.UiThemeStyle
import com.anvex.core.model.Project
import kotlinx.coroutines.launch

/**
 * v4.4 — categorized settings.
 *
 * Ported natively from the untitled.zip web prototype's CategorizedSettingsView:
 * accent-coded category chips (AI=cyan, Memory=purple, Workspace=amber,
 * Appearance=pink, Data=emerald, Developer=slate) with counts, and every row
 * backed by the REAL settings storage — no mock toggles, no fake states.
 * Rows inside each category are ordered (language first, destructive last).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    nav: NavController,
    updateController: com.anvex.app.ui.components.AppUpdateController,
) {
    val vm: SettingsViewModel = viewModel { SettingsViewModel() }
    val graph = AppGraph.get()
    val settings by vm.settings.collectAsState()
    val projects by vm.projects.collectAsState()
    val cacheBytes by vm.cacheBytes.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var category by rememberSaveable { mutableStateOf("ai") }
    var showTerminalPicker by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    // v4.0 audit fix: destructive actions require an explicit confirmation dialog.
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showDeleteHistoryDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var instructions by remember(settings?.customInstructions) { mutableStateOf(settings?.customInstructions.orEmpty()) }

    LaunchedEffect(Unit) { vm.refreshCacheSize() }

    data class Category(val id: String, val label: String, val icon: ImageVector, val tint: Color, val count: Int)
    val categories = listOf(
        Category("ai", tr("AI & providers"), Icons.Filled.Psychology, MaterialTheme.colorScheme.primary, 4),
        Category("memory", tr("Memory"), Icons.Filled.MemoryIcon, Color(0xFFA78BFA), 2),
        Category("workspace", tr("Workspace & build"), Icons.Filled.Terminal, Color(0xFF34D399), 4),
        Category("appearance", tr("Appearance"), Icons.Filled.Language, Color(0xFF7DD3FC), 5),
        Category("data", tr("Data & security"), Icons.Filled.Security, Color(0xFFFBBF24), 3),
        Category("developer", tr("Developer & about"), Icons.Filled.DeveloperMode, Color(0xFF94A3B8), 5),
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(tr("Settings"), style = MaterialTheme.typography.titleLarge)
                        Text(tr("Control your workspace and runtime"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            // ---- category chips (web-prototype port: accent-coded, horizontally scrollable)
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(categories) { c ->
                    FilterChip(
                        selected = category == c.id,
                        onClick = { category = c.id },
                        label = { Text("${c.label} · ${c.count}") },
                        leadingIcon = { androidx.compose.material3.Icon(c.icon, null, Modifier.size(16.dp), tint = c.tint) },
                    )
                }
            }

            when (category) {
                "ai" -> AiCategory(
                    settings = settings,
                    nav = nav,
                    instructions = instructions,
                    onInstructions = { instructions = it },
                    onSaveInstructions = { scope.launch { graph.settings.setCustomInstructions(instructions) } },
                    onWebTools = { v -> scope.launch { graph.settings.setWebToolsEnabled(v) } },
                )
                "memory" -> MemoryCategory(
                    settings = settings,
                    nav = nav,
                    onMemory = { v -> scope.launch { graph.settings.setMemoryEnabled(v) } },
                )
                "workspace" -> WorkspaceCategory(
                    settings = settings,
                    projects = projects,
                    onTerminal = { showTerminalPicker = true },
                    onNodeBackend = { b -> scope.launch { graph.settings.setNodeBackend(b) } },
                    onTermuxBridge = { v -> scope.launch { graph.settings.setTermuxBridgeEnabled(v) } },
                    nav = nav,
                )
                "appearance" -> AppearanceCategory(
                    settings = settings,
                    vm = vm,
                    showLanguageDialog = { showLanguageDialog = true },
                )
                "data" -> DataCategory(
                    cacheBytes = cacheBytes,
                    nav = nav,
                    onClearCache = { showClearCacheDialog = true },
                    onDeleteHistory = { showDeleteHistoryDialog = true },
                )
                "developer" -> DeveloperCategory(
                    nav = nav,
                    updateController = updateController,
                    onShowUpdateDialog = { showUpdateDialog = true },
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
        }
    }

    // ---- dialogs ---------------------------------------------------------------

    if (showUpdateDialog) {
        com.anvex.app.ui.components.AppUpdateDialog(updateController) { showUpdateDialog = false }
    }

    if (showTerminalPicker) {
        AlertDialog(
            onDismissRequest = { showTerminalPicker = false },
            title = { Text(tr("Terminal")) },
            text = {
                Column {
                    TextButton(onClick = {
                        showTerminalPicker = false
                        nav.navigate(Routes.terminal(com.anvex.core.android.runtime.AndroidRuntimeGateway.HOME_PROJECT_ID))
                    }) { Text(tr("App-wide terminal (no project)")) }
                    if (projects.isEmpty()) {
                        Text(tr("Create or import a project first."))
                    } else {
                        projects.forEach { project: Project ->
                            TextButton(onClick = {
                                showTerminalPicker = false
                                nav.navigate(Routes.terminal(project.id))
                            }) { Text(project.name) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showTerminalPicker = false }) { Text(tr("Close")) } },
        )
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(tr("App language")) },
            text = {
                Column {
                    AppLanguage.entries.forEach { language ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = settings?.language == language,
                                onClick = {
                                    showLanguageDialog = false
                                    vm.setLanguage(language)
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when (language) {
                                        AppLanguage.SYSTEM -> tr("System default")
                                        AppLanguage.ENGLISH -> "English"
                                        AppLanguage.PERSIAN -> "فارسی"
                                    }
                                )
                                if (language == AppLanguage.PERSIAN) {
                                    Text(
                                        "رابط کاربری کامل فارسی و راست‌به‌چپ",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) { Text(tr("Close")) }
            },
        )
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text(tr("Clear preview cache?")) },
            text = { Text(tr("This removes cached preview artifacts") + " (${formatSize(cacheBytes)}). " + tr("Project files are not affected.")) },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearCache()
                    showClearCacheDialog = false
                }) { Text(tr("Clear"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearCacheDialog = false }) { Text(tr("Cancel")) } },
        )
    }

    if (showDeleteHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteHistoryDialog = false },
            title = { Text(tr("Delete all conversations?")) },
            text = { Text(tr("Every chat message across all projects will be permanently deleted. Project files and settings remain untouched. This cannot be undone.")) },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearChatHistory()
                    showDeleteHistoryDialog = false
                }) { Text(tr("Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteHistoryDialog = false }) { Text(tr("Cancel")) } },
        )
    }
}

// ---------------------------------------------------------------- categories

@Composable
private fun AiCategory(
    settings: com.anvex.core.android.repo.AppSettings?,
    nav: NavController,
    instructions: String,
    onInstructions: (String) -> Unit,
    onSaveInstructions: () -> Unit,
    onWebTools: (Boolean) -> Unit,
) {
    AnvexSectionHeader(tr("AI & providers"), tr("Providers, usage, tools and standing instructions"))
    SettingsRow(Icons.Filled.Key, tr("AI providers"), tr("OpenAI, Anthropic, Gemini, Z.ai (GLM Coding Plan), OpenCode, OpenRouter, DeepSeek, Mistral, Groq and custom cloud endpoints"), nav = nav, route = Routes.PROVIDERS)
    SettingsRow(Icons.Filled.DataUsage, tr("AI usage & tokens"), tr("Review token usage by model and provider"), nav = nav, route = Routes.USAGE)
    AnvexCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            ListItem(
                headlineContent = { Text(tr("Web tools")) },
                supportingContent = { Text(tr("Let the agent search the web and read pages (HTTPS, public sites only)")) },
                trailingContent = {
                    Switch(checked = settings?.webToolsEnabled ?: true, onCheckedChange = onWebTools)
                },
            )
            OutlinedTextField(
                value = instructions,
                onValueChange = { onInstructions(it.take(4_000)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text(tr("Custom instructions")) },
                placeholder = { Text(tr("e.g. Answer in Persian. Prefer Kotlin + Compose. Be concise.")) },
                minLines = 3,
                maxLines = 8,
            )
            TextButton(
                onClick = onSaveInstructions,
                enabled = instructions != (settings?.customInstructions ?: ""),
            ) { Text(tr("Save instructions")) }
        }
    }
}

@Composable
private fun MemoryCategory(
    settings: com.anvex.core.android.repo.AppSettings?,
    nav: NavController,
    onMemory: (Boolean) -> Unit,
) {
    AnvexSectionHeader(tr("Memory"), tr("Long-term memory across chats"))
    AnvexCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            ListItem(
                headlineContent = { Text(tr("Long-term memory")) },
                supportingContent = { Text(tr("Learn your preferences over time and use them in every project")) },
                trailingContent = { Switch(checked = settings?.memoryEnabled ?: true, onCheckedChange = onMemory) },
            )
            ListItem(
                headlineContent = { Text(tr("Manage memories")) },
                supportingContent = { Text(tr("See, edit or delete everything that is remembered")) },
                trailingContent = { TextButton(onClick = { nav.navigate(Routes.MEMORY) }) { Text(tr("Open")) } },
            )
        }
    }
}

@Composable
private fun WorkspaceCategory(
    settings: com.anvex.core.android.repo.AppSettings?,
    projects: List<Project>,
    onTerminal: () -> Unit,
    onNodeBackend: (NodeBackend) -> Unit,
    onTermuxBridge: (Boolean) -> Unit,
    nav: NavController,
) {
    AnvexSectionHeader(tr("Workspace & build"), tr("Terminal, Node.js runtime and the agent"))
    AnvexCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), onClick = { nav.navigate(Routes.AGENT_CENTER) }) {
        Row(Modifier.padding(16.dp)) {
            AnvexIconTile(Icons.Filled.AutoAwesome)
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Agent workspace"), style = MaterialTheme.typography.titleMedium)
                    AnvexStatusBadge("Available", AnvexStatus.SUCCESS)
                }
                Text(
                    tr("Durable tasks, approvals, skills, plugins and runtime capabilities."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    SettingsRow(Icons.Filled.Terminal, tr("Terminal"), tr("Interactive shell in your project workspace") + " — " + tr("quick access"), onClick = onTerminal)
    AnvexCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(tr("Node.js runtime"), style = MaterialTheme.typography.titleSmall)
            Text(
                tr("In-app: the bundled real Node.js 24 runtime with npm (pure-JS packages; native addons can't build on-device). Termux: forward node/npm through the RUN_COMMAND bridge to a real Node installation."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                FilterChip(
                    selected = settings?.nodeBackend != NodeBackend.TERMUX,
                    onClick = { onNodeBackend(NodeBackend.IN_APP) },
                    label = { Text(tr("In-app (Node.js 24)")) },
                )
                FilterChip(
                    selected = settings?.nodeBackend == NodeBackend.TERMUX,
                    onClick = { onNodeBackend(NodeBackend.TERMUX) },
                    label = { Text("Termux") },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ListItem(
                headlineContent = { Text(tr("Termux bridge (RUN_COMMAND)")) },
                supportingContent = {
                    Text(
                        tr("Let Anvex send commands to Termux when you ask for gradle, python, node or git. Output stays inside Termux; nothing is forged.")
                    )
                },
                trailingContent = { Switch(checked = settings?.termuxBridgeEnabled ?: false, onCheckedChange = onTermuxBridge) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppearanceCategory(
    settings: com.anvex.core.android.repo.AppSettings?,
    vm: SettingsViewModel,
    showLanguageDialog: () -> Unit,
) {
    AnvexSectionHeader(tr("Appearance"), tr("Make the workspace comfortable on your device"))
    AnvexCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), onClick = showLanguageDialog) {
        ListItem(
            leadingContent = { AnvexIconTile(Icons.Filled.Language, modifier = Modifier.size(40.dp)) },
            headlineContent = { Text(tr("Language")) },
            supportingContent = {
                Text(
                    when (settings?.language ?: AppLanguage.SYSTEM) {
                        AppLanguage.SYSTEM -> tr("System default")
                        AppLanguage.ENGLISH -> "English"
                        AppLanguage.PERSIAN -> "فارسی"
                    }
                )
            },
            trailingContent = {
                Text(tr("Change"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            },
        )
    }
    AnvexCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(vertical = 4.dp)) {
            ListItem(
                headlineContent = { Text(tr("Theme")) },
                supportingContent = { Text(tr("Dark is the v4 default; System follows Android; Light stays fixed")) },
                trailingContent = {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            TextButton(onClick = { vm.setTheme(mode) }) {
                                Text(
                                    when (mode) {
                                        ThemeMode.SYSTEM -> "System"
                                        ThemeMode.LIGHT -> "Light"
                                        ThemeMode.DARK -> "Dark"
                                    },
                                    color = if (settings?.themeMode == mode) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
            )
            ListItem(
                headlineContent = { Text(tr("Style")) },
                supportingContent = {
                    // v4.4.5 (UX): the four style buttons used to live in
                    // trailingContent and squeezed the headline column to one
                    // character wide ("Style" wrapped vertically). They are now
                    // a wrapping single-select chip group with full width.
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            when (settings?.uiThemeStyle ?: UiThemeStyle.PROFESSIONAL) {
                                UiThemeStyle.AURORA -> "Aurora — cyan/slate dark ladder, mono badges, status accents (web-prototype port)"
                                UiThemeStyle.NEBULA -> "Nebula — deep-space canvas, violet→cyan gradient, glass cards"
                                UiThemeStyle.PROFESSIONAL -> "Professional — Command Deck: Linear/Vercel dark, JetBrains Mono, 8pt grid (default)"
                                UiThemeStyle.GLASS -> "Glass — Glassmorphism / Expressive Material 3"
                                UiThemeStyle.MIDNIGHT -> "Midnight — deep-navy dark, ice-blue accent; calm and focused"
                                UiThemeStyle.FOREST -> "Forest — deep-green dark, mint accent; easy on the eyes"
                                UiThemeStyle.EMBER -> "Ember — warm-charcoal dark, amber accent; cozy contrast"
                                UiThemeStyle.MINT -> "Mint — near-black green dark, bright mint accent, pill-rounded surfaces (Lovable design port)"
                            }
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            UiThemeStyle.entries.forEach { style ->
                                FilterChip(
                                    selected = (settings?.uiThemeStyle ?: UiThemeStyle.PROFESSIONAL) == style,
                                    onClick = { vm.setThemeStyle(style) },
                                    label = {
                                        Text(
                                            when (style) {
                                                UiThemeStyle.AURORA -> "Aurora"
                                                UiThemeStyle.NEBULA -> "Nebula"
                                                UiThemeStyle.PROFESSIONAL -> "Professional"
                                                UiThemeStyle.GLASS -> "Glass"
                                                UiThemeStyle.MIDNIGHT -> "Midnight"
                                                UiThemeStyle.FOREST -> "Forest"
                                                UiThemeStyle.EMBER -> "Ember"
                                                UiThemeStyle.MINT -> "Mint"
                                            }
                                        )
                                    },
                                )
                            }
                        }
                    }
                },
            )
            ListItem(
                headlineContent = { Text(tr("Dynamic color")) },
                supportingContent = { Text(tr("Use the Android 12+ wallpaper palette instead of the designed themes (off by default)")) },
                trailingContent = { Switch(checked = settings?.dynamicColor ?: false, onCheckedChange = vm::setDynamicColor) },
            )
        }
    }
    AnvexCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row {
                Text(tr("Editor font size"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text("${settings?.editorFontSize ?: 13} sp", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = (settings?.editorFontSize ?: 13).toFloat(),
                onValueChange = { vm.setEditorFont(it.toInt()) },
                valueRange = 10f..24f,
                steps = 13,
            )
            ListItem(
                headlineContent = { Text(tr("Auto-save")) },
                supportingContent = { Text(tr("Save files about 0.7s after you stop typing")) },
                trailingContent = { Switch(checked = settings?.editorAutoSave ?: true, onCheckedChange = vm::setAutoSave) },
            )
        }
    }
}

@Composable
private fun DataCategory(
    cacheBytes: Long,
    nav: NavController,
    onClearCache: () -> Unit,
    onDeleteHistory: () -> Unit,
) {
    AnvexSectionHeader(tr("Data & security"), tr("Local data stays on the device"))
    AnvexCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        ListItem(
            headlineContent = { Text(tr("Preview cache")) },
            supportingContent = { Text(formatSize(cacheBytes)) },
            trailingContent = { Button(onClick = onClearCache, enabled = cacheBytes > 0) { Text(tr("Clear")) } },
        )
        ListItem(
            headlineContent = { Text(tr("Conversation history")) },
            supportingContent = { Text(tr("Delete all chat messages; project files remain")) },
            trailingContent = { Button(onClick = onDeleteHistory) { Text(tr("Delete"), color = MaterialTheme.colorScheme.error) } },
        )
        ListItem(
            headlineContent = { Text(tr("Encrypted backup")) },
            supportingContent = { Text(tr("Transfer settings and provider credentials between devices")) },
            trailingContent = { TextButton(onClick = { nav.navigate(Routes.BACKUP) }) { Text(tr("Open")) } },
        )
    }
    AnvexCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        ListItem(
            headlineContent = { Text(tr("Keys stay on this device")) },
            supportingContent = {
                Text(
                    tr("API keys use Android Keystore-backed encryption and are sent only to the provider endpoint you configure. The optional LAN preview server is plain HTTP — use it only on networks you trust.")
                )
            },
        )
    }
}

@Composable
private fun DeveloperCategory(
    nav: NavController,
    updateController: com.anvex.app.ui.components.AppUpdateController,
    onShowUpdateDialog: () -> Unit,
) {
    AnvexSectionHeader(tr("Developer & about"), tr("Diagnostics, recovery and credits"))
    SettingsRow(Icons.Filled.BugReport, tr("Debug panel"), tr("Runtime status, memory, logs and provider tests"), nav = nav, route = Routes.DEBUG)
    SettingsRow(Icons.Filled.DeveloperMode, tr("Developer Center"), tr("Health, recovery, tasks, snapshots, skills and LAN access"), nav = nav, route = Routes.DEVELOPER)
    SettingsRow(Icons.Filled.SettingsSuggest, tr("Agent Center"), tr("Launch and monitor real durable agent tasks"), nav = nav, route = Routes.AGENT_CENTER)
    SettingsRow(Icons.Filled.Key, tr("Signing key"), tr("Generate an APK signing key on-device and push secrets to GitHub"), nav = nav, route = Routes.SIGNING_KEY)
    // v4.9: agent engine selection (native vs Pi) + live Pi test bench.
    PiEnginePanel()
    // v4.7: in-app update check (GitHub Releases = the distribution channel).
    AnvexSectionHeader(tr("App update"), tr("New versions are published on GitHub Releases"))
    AnvexCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        val updateState = updateController.state
        ListItem(
            headlineContent = { Text("Anvex ${updateController.currentVersion}") },
            supportingContent = {
                Text(
                    when (updateState) {
                        is com.anvex.app.ui.components.AppUpdateController.State.Checking -> tr("Checking for updates…")
                        is com.anvex.app.ui.components.AppUpdateController.State.UpToDate -> tr("You're up to date")
                        is com.anvex.app.ui.components.AppUpdateController.State.Available ->
                            tr("Update available") + ": v${updateState.info.version} (${formatSize(updateState.info.sizeBytes)})"
                        is com.anvex.app.ui.components.AppUpdateController.State.Downloading -> tr("Downloading…")
                        is com.anvex.app.ui.components.AppUpdateController.State.Downloaded -> tr("Download complete — tap Install")
                        is com.anvex.app.ui.components.AppUpdateController.State.Failed -> updateState.message
                        else -> tr("Check whether a newer version has been released")
                    }
                )
            },
            trailingContent = {
                when (updateState) {
                    is com.anvex.app.ui.components.AppUpdateController.State.Checking ->
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    is com.anvex.app.ui.components.AppUpdateController.State.Available ->
                        Button(onClick = onShowUpdateDialog) { Text(tr("Download update")) }
                    is com.anvex.app.ui.components.AppUpdateController.State.Downloading ->
                        Button(onClick = onShowUpdateDialog) { Text(tr("Downloading…")) }
                    is com.anvex.app.ui.components.AppUpdateController.State.Downloaded ->
                        Button(onClick = onShowUpdateDialog) { Text(tr("Install")) }
                    else ->
                        Button(onClick = { updateController.check() }) { Text(tr("Check for update")) }
                }
            },
        )
    }
    AnvexSectionHeader(tr("About"))
    AnvexCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        ListItem(
            headlineContent = { Text("Anvex ${updateController.currentVersion}") },
            supportingContent = {
                Text(
                    tr("Android-native AI development workspace based on bolt.diy (MIT). Durable agent harness: chat, editor, preview, terminal, git, artifacts, skills, MCP and in-app APK builds.")
                )
            },
        )
        ListItem(
            headlineContent = { Text(tr("Developer")) },
            supportingContent = { Text("developer farzad sholeh") },
        )
        ListItem(
            headlineContent = { Text(tr("Privacy")) },
            supportingContent = {
                Text(tr("API keys use Android Keystore-backed encryption and are sent only to the provider endpoint you configure."))
            },
        )
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit = {},
    nav: NavController? = null,
    route: String? = null,
) {
    AnvexCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        onClick = if (route != null && nav != null) ({ nav.navigate(route) }) else onClick,
    ) {
        Row(Modifier.padding(12.dp)) {
            AnvexIconTile(icon, modifier = Modifier.size(40.dp))
            Column(
                Modifier.weight(1f).padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(tr("›"), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
