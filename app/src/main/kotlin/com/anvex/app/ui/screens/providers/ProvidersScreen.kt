package com.anvex.app.ui.screens.providers

import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.anvex.app.ui.Routes
import com.anvex.app.vm.ProvidersViewModel
import com.anvex.core.ai.zai.ZaiAccountCoordinator
import com.anvex.core.model.ProviderKind
import com.anvex.app.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Provider/model management (brief §13): list, add, edit, delete, test
 * connection, default selection, model listing for arbitrary custom endpoints.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(nav: NavController) {
    val vm: ProvidersViewModel = viewModel { ProvidersViewModel() }
    val providers by vm.providers.collectAsState()
    val brokenProviders by vm.brokenProviders.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text(tr("AI providers")) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.providerEdit("new")) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(tr("Add provider")) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // v4.4: Z.ai account hero card (login flow) — always first.
            item { ZaiAccountCard(vm) }
            // M-08: providers that exist in the database but cannot be
            // loaded (undecryptable key, corrupt row) — visible with a
            // reason and a Remove action, never silently dropped.
            if (brokenProviders.isNotEmpty()) {
                item {
                    Card(colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                tr("Some saved providers could not be loaded. Re-add them, or remove the broken entries:"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            brokenProviders.forEach { broken ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        "${broken.name} (${broken.reason})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { vm.removeBroken(broken.id) }) {
                                        Text(tr("Remove"))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (providers.isEmpty()) {
                item {
                    Text(
                        tr("No other providers configured yet.\n\nAdd an OpenAI, Anthropic, Gemini, OpenRouter, DeepSeek, Mistral, Groq, OpenCode or custom cloud endpoint with your API key — or connect your Z.ai account above. Anvex 4.0 is cloud-only; local model servers are no longer supported."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // v4.4.6: guided first key — where to get one, without pricing claims.
                item { ProviderKeyGuideCard() }
            } else {
                items(providers, key = { it.id }) { provider ->
                    Card(onClick = { nav.navigate(Routes.providerEdit(provider.id)) }) {
                        ListItem(
                            overlineContent = { Text(provider.kind.displayLabel) },
                            headlineContent = { Text(provider.name) },
                            supportingContent = {
                                Text(
                                    provider.model + if (provider.baseUrl.isNotBlank() && provider.kind.supportsBaseUrlOverride)
                                        "\n${provider.baseUrl}" else ""
                                )
                            },
                            trailingContent = {
                                if (provider.isDefault) {
                                    Text(tr("default"), color = MaterialTheme.colorScheme.primary)
                                } else {
                                    TextButton(onClick = { vm.setDefault(provider.id) }) { Text(tr("set default")) }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * v4.4: Z.ai GLM Coding Plan account card — native port of the untitled.zip web
 * prototype's ZaiAccountCard visuals with the REAL ZCode polling flow behind it.
 * States: Idle (connect button) / Running (stage + authorize URL + cancel) /
 * Failed (honest upstream reason) / Connected (existing provider row).
 * No fake quotas, no simulated success.
 */
@Composable
private fun ZaiAccountCard(vm: ProvidersViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val login by vm.zaiLogin.collectAsState()
    val providers by vm.providers.collectAsState()
    val connected = providers.firstOrNull { it.kind == ProviderKind.ZAI_ACCOUNT }
    val extras = com.anvex.app.ui.theme.LocalBoltExtras.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .background(extras.accentBrush, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) { Text("Z", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleMedium) }
                Column(Modifier.weight(1f)) {
                    Text("Z.ai · GLM Coding Plan", style = MaterialTheme.typography.titleMedium)
                    Text(
                        tr("Account login — no pasted API key. Connects with your own Z.ai account."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    // v4.4.5 (UX): a static badge — the old AssistChip showed a
                    // tap ripple with zero effect (dead control).
                    connected != null -> Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Text(
                            tr("Connected"),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    login is ProvidersViewModel.ZaiLoginUi.Running -> {}
                    else -> {}
                }
            }

            when (val state = login) {
                is ProvidersViewModel.ZaiLoginUi.Running -> {
                    val label = when (state.stage) {
                        ZaiAccountCoordinator.Stage.INITIATING -> tr("Starting login flow…")
                        ZaiAccountCoordinator.Stage.WAITING_FOR_BROWSER -> tr("Waiting for you in the browser…")
                        ZaiAccountCoordinator.Stage.POLLING -> tr("Waiting for authorization") + (state.attempt?.let { " · ${tr("attempt")} $it" } ?: "")
                        ZaiAccountCoordinator.Stage.EXCHANGING -> tr("Exchanging the Z.ai token…")
                        ZaiAccountCoordinator.Stage.MINTING_KEY -> tr("Provisioning the coding-plan key…")
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                    state.authorizeUrl?.let { url ->
                        Text(
                            tr("Authorization URL") + ":",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            url,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            state.authorizeUrl?.let { url ->
                                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                            }
                        }, enabled = state.authorizeUrl != null) { Text(tr("Open browser")) }
                        TextButton(onClick = { vm.cancelZaiLogin() }) { Text(tr("Cancel"), color = MaterialTheme.colorScheme.error) }
                    }
                }
                is ProvidersViewModel.ZaiLoginUi.Failed -> {
                    Text(
                        state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { vm.startZaiLogin { url ->
                        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                    } }) { Text(tr("Try again")) }
                }
                ProvidersViewModel.ZaiLoginUi.Idle -> {
                    if (connected != null) {
                        Text(
                            tr("Connected as") + " " + connected.name.removePrefix("Z.ai · ") + " · " + connected.model,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            tr("Sign in with your Z.ai account; Anvex detects completion automatically and provisions the GLM coding-plan key."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { vm.startZaiLogin { url ->
                            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                        } }) { Text(tr("Connect Z.ai account")) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderEditScreen(nav: NavController, id: String, initialKind: String = "") {
    val vm: ProvidersViewModel = viewModel(key = "provider-edit-$id") { ProvidersViewModel() }
    val ui by vm.edit.collectAsState()
    val testResult by vm.testResult.collectAsState()
    val testing by vm.testing.collectAsState()
    val models by vm.models.collectAsState()
    val loadingModels by vm.loadingModels.collectAsState()
    val modelTestResults by vm.modelTestResults.collectAsState()
    val testingModels by vm.testingModels.collectAsState()
    val modelTestProgress by vm.modelTestProgress.collectAsState()
    val modelTestingId by vm.modelTestingId.collectAsState()
    val saveMessage by vm.saveMessage.collectAsState()
    val credentialWarning by vm.credentialWarning.collectAsState()
    var showKey by remember { mutableStateOf(false) }
    // v4.4.5 (UX): deleting a provider discards encrypted credentials.
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(id, initialKind) { vm.loadForEdit(id, initialKind) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (ui.isNew) "Add provider" else ui.name.ifBlank { "Edit provider" }) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            KindSelector(ui.kind) { kind -> vm.update { copy(kind = kind) } }

            OutlinedTextField(
                value = ui.name,
                onValueChange = { vm.update { copy(name = it) } },
                label = { Text(tr("Provider name (e.g. My Provider)")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (ui.kind.supportsBaseUrlOverride || ui.isNew) {
                Column {
                    OutlinedTextField(
                        value = ui.baseUrl,
                        onValueChange = { vm.update { copy(baseUrl = it) } },
                        label = {
                            Text(
                                when {
                                    ui.kind == ProviderKind.CUSTOM_OPENAI || ui.kind == ProviderKind.CUSTOM_ANTHROPIC ->
                                        "API base URL (e.g. https://example.com/v1)"
                                    else -> "API base URL (optional override)"
                                }
                            )
                        },
                        placeholder = {
                            Text(
                                ui.kind.defaultBaseUrl.ifBlank { "https://example.com/v1" },
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // v4.4: credential UI adapts to the provider kind — honest about each tier.
            if (ui.kind.isCredentialFree) {
                // OPENCODE_FREE: no credential; the verified upstream 403 is reported as-is.
                OutlinedTextField(
                    value = ui.apiKey,
                    onValueChange = { vm.update { copy(apiKey = it) } },
                    label = { Text(tr("No key needed (free tier)")) },
                    placeholder = { Text(tr("leave blank — free models only")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    tr("Verified 15 Oct 2026: model discovery is public, but OpenCode answers chat requests from other clients with HTTP 403 — “OpenCode's free tier can only be used from within OpenCode.” Anvex shows that error exactly as the service returns it, does not impersonate the official client, and never claims a connection it does not have. For direct access use “OpenCode Console (API key)”. Free model availability and limits may change without notice."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (ui.kind.requiresAccountLogin) {
                // ZAI_ACCOUNT: credential comes from the account login card on the list screen.
                Text(
                    tr("The API key is provisioned automatically by the account login — use the “Connect Z.ai account” card on the providers list. Re-login refreshes it (no refresh token: it expires)."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                OutlinedTextField(
                    value = ui.apiKey,
                    onValueChange = { vm.update { copy(apiKey = it) } },
                    label = {
                        Text(
                            when {
                                ui.kind == ProviderKind.OPENCODE_ZEN -> if (ui.apiKeySet) tr("Console API key (saved — leave blank to keep)") else tr("Console API key")
                                else -> if (ui.apiKeySet) tr("API key (saved — leave blank to keep)") else tr("API key")
                            }
                        )
                    },
                    visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) tr("Hide") else tr("Show"))
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---- model row: free text + fetch from API ------------------------------
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(
                    value = ui.model,
                    onValueChange = { vm.update { copy(model = it) } },
                    label = { Text(tr("Model")) },
                    placeholder = {
                        Text(
                            tr("e.g. claude-3-5-sonnet-latest"),
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { vm.loadModels() },
                    // v4.0 audit fix (boolean precedence): parenthesised conjunction —
                    // previously `!loadingModels && a || b` let the button stay enabled
                    // during an in-flight model fetch whenever an API key existed.
                    enabled = !loadingModels && (
                        ui.model.isNotBlank() || ui.apiKey.isNotBlank()
                        ),
                ) {
                    Text(if (loadingModels) "Loading…" else "Fetch models")
                }
            }

            if (models.isNotEmpty()) {
                ModelPicker(
                    models = models,
                    current = ui.model,
                    testResults = modelTestResults,
                    testingModels = testingModels,
                    testProgress = modelTestProgress,
                    testingModelId = modelTestingId,
                    onTestModels = { vm.testModels() },
                ) { model ->
                    vm.update { copy(model = model) }
                }
            }

            OutlinedTextField(
                value = ui.extraHeaders,
                onValueChange = { vm.update { copy(extraHeaders = it) } },
                label = { Text(tr("Extra HTTP headers (optional, one per line)")) },
                placeholder = { Text("HTTP-Referer: https://myapp.example\nX-Title: My App") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = ui.temperature,
                    onValueChange = { vm.update { copy(temperature = it) } },
                    label = { Text(tr("Temperature")) },
                    placeholder = { Text(tr("0.7")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = ui.maxTokens,
                    onValueChange = { vm.update { copy(maxTokens = it) } },
                    label = { Text(tr("Max tokens")) },
                    placeholder = { Text(tr("8192")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(tr("Use as default"), modifier = Modifier.weight(1f))
                Switch(checked = ui.isDefault, onCheckedChange = { vm.update { copy(isDefault = it) } })
            }

            // ---- test connection ------------------------------------------------------
            // v4.4.5 (UX): equal weights — three unweighted buttons could
            // overflow narrow widths, and Delete appearing conditionally
            // shifted the others.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { vm.testConnection() }, enabled = !testing, modifier = Modifier.weight(1f)) {
                    Text(if (testing) "Testing…" else "Test connection", maxLines = 1)
                }
                Button(
                    onClick = { vm.save { nav.popBackStack() } },
                    enabled = ui.name.isNotBlank() && ui.model.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(tr("Save"), maxLines = 1)
                }
                if (!ui.isNew) {
                    TextButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.weight(1f),
                    ) { Text(tr("Delete"), color = MaterialTheme.colorScheme.error, maxLines = 1) }
                }
            }

            testResult?.let { result ->
                Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = if (result.ok) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (result.ok) "Connection OK" else "Connection failed",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(result.message, style = MaterialTheme.typography.bodySmall)
                        Text("${result.latencyMs} ms", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            credentialWarning?.let {
                Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            saveMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Text(
                "Keys are encrypted with the Android Keystore and never leave the device except to your provider's endpoint.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ui.kind.isLegacyLocalKind) {
                Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        "This provider uses a local-model server kind that Anvex 4.0 no longer supports. Re-create it as a custom cloud endpoint (HTTPS) to keep using it.",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            // v4.4.4: retired kinds (Antigravity, Cline) are read-only for legacy rows.
            if (ui.kind.isRetiredKind) {
                Card(
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        tr("This provider kind was removed in Anvex 4.4.4 and can no longer be edited. Create a new provider instead."),
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    if (confirmDelete) {
        com.anvex.app.ui.components.ConfirmDialog(
            title = tr("Delete provider?"),
            text = tr("The provider and its stored encrypted credentials will be permanently deleted."),
            confirmLabel = tr("Delete"),
            destructive = true,
            onConfirm = { vm.delete(id) { nav.popBackStack() } },
            onDismiss = { confirmDelete = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindSelector(current: ProviderKind, onSelect: (ProviderKind) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = current.displayLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(tr("Provider type")) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            // v4.0 (GOAL 5): cloud-only kinds; legacy local-server entries are hidden.
            ProviderKind.entries.filter { it.isSelectable }.forEach { kind ->
                DropdownMenuItem(
                    text = { Text(kind.displayLabel) },
                    onClick = {
                        onSelect(kind)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelPicker(
    models: List<com.anvex.core.model.ModelInfo>,
    current: String,
    testResults: Map<String, Boolean> = emptyMap(),
    testingModels: Boolean = false,
    testProgress: Pair<Int, Int> = 0 to 0,
    testingModelId: String? = null,
    onTestModels: () -> Unit = {},
    onPick: (String) -> Unit,
) {
    var query by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val shown = androidx.compose.runtime.remember(models, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) models else models.filter { it.id.lowercase().contains(q) || it.displayName.lowercase().contains(q) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                "Models from endpoint (${models.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.TextButton(onClick = onTestModels) {
                Text(if (testingModels) tr("Cancel test") else tr("Test models"))
            }
        }
        if (testingModels) {
            Text(
                "Testing ${testProgress.first}/${testProgress.second}…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (testResults.isNotEmpty()) {
            val okCount = testResults.values.count { it }
            Text(
                "$okCount of ${testResults.size} models work with this key",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(tr("Search models")) },
            leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Search, null) },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        )
        if (shown.isEmpty()) {
            Text("No model matches \"$query\".", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.height(240.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(shown, key = { it.id }) { model ->
                val selected = model.id == current
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onPick(model.id) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(model.id, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        if (model.displayName != model.id) {
                            Text(model.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                    // Key-test status wins over the selected check: green =
                    // a minimal request with this model succeeded, red = it
                    // failed for this key, spinner = currently being probed.
                    when {
                        testResults.containsKey(model.id) -> {
                            val ok = testResults[model.id] == true
                            androidx.compose.material3.Icon(
                                // Icons are extension properties: each one needs
                                // its own import (a fully-qualified path alone
                                // does not resolve them — that was the CI error).
                                if (ok) Icons.Filled.CheckCircle
                                else Icons.Filled.Error,
                                null,
                                tint = if (ok) androidx.compose.ui.graphics.Color(0xFF2E9E5B)
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                        testingModelId == model.id -> {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        selected -> {
                            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

/**
 * v4.4.6: guided first key. Two well-known places to obtain an API key,
 * opened in the system browser. No pricing or free-tier claims — those change;
 * the key pages themselves describe current terms.
 */
@Composable
private fun ProviderKeyGuideCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    Card {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("Where to get an API key"), style = MaterialTheme.typography.titleSmall)
            Text(
                tr("Create a key on one of these sites, then tap \"Add provider\" and paste it."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
            KeyLinkRow(
                label = "Google AI Studio",
                hint = tr("Gemini API key"),
                onClick = { openInSystemBrowser(context, "https://aistudio.google.com/apikey") },
            )
            KeyLinkRow(
                label = "OpenRouter",
                hint = tr("Many models, one key"),
                onClick = { openInSystemBrowser(context, "https://openrouter.ai/keys") },
            )
        }
    }
}

@Composable
private fun KeyLinkRow(label: String, hint: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Filled.OpenInBrowser,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Open a URL in the system browser (never a WebView); failures are swallowed silently. */
private fun openInSystemBrowser(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
}
