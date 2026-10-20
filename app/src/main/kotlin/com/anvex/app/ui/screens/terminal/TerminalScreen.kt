package com.anvex.app.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.anvex.app.i18n.tr
import com.anvex.app.ui.components.AnvexStatus
import com.anvex.app.ui.components.AnvexStatusBadge
import com.anvex.app.ui.theme.AnvexDimens
import com.anvex.app.ui.theme.AnvexMono
import com.anvex.core.android.AppGraph
import com.anvex.core.android.repo.NodeBackend
import com.anvex.core.android.runtime.AndroidTerminalSession
import com.anvex.core.android.runtime.TermuxBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Real on-device terminal for app-private project workspaces.
 *
 * v4.4: `node` / `npm` commands are dispatched by the Node.js backend setting —
 * TERMUX forwards them through the RUN_COMMAND bridge (output appears in Termux).
 * IN_APP runs them on the bundled real Node.js 18 runtime (nodejs-mobile) with
 * live-streamed output; on ABIs without a libnode it falls back to the older
 * WebView Node-compat layer (plain JS only). Everything else runs in the
 * interactive `/system/bin/sh` session as before.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(navController: NavHostController, projectId: String) {
    val graph = AppGraph.get()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val output = remember { mutableStateListOf<String>() }
    // v4.4.4 (smoothness): monotonic base so LazyColumn item keys stay stable
    // when the head of the buffer is trimmed.
    var outputBase by remember { mutableIntStateOf(0) }
    val history = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    var command by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<AndroidTerminalSession?>(null) }
    var starting by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(projectId) {
        starting = true
        error = null
        session = graph.androidRuntime.terminal(projectId, approval = { false })
        val s = session
        if (s == null || !s.start()) {
            error = "Terminal requires an app-private project workspace. SAF workspaces are intentionally blocked from raw shell access."
        } else {
            output.add("Anvex terminal ready")
            output.add("Working directory: project workspace")
            output.add("")
        }
        starting = false
    }

    LaunchedEffect(session) {
        while (session != null) {
            val text = runCatching { session?.readAvailable(50_000).orEmpty() }.getOrDefault("")
            if (text.isNotEmpty()) {
                output.addAll(text.trimEnd().split('\n'))
                // v4.4.4 (smoothness): cap the buffer — an unbounded list degrades
                // LazyColumn layout/scroll on long sessions. Keys stay stable via outputBase.
                if (output.size > 2000) {
                    val drop = output.size - 2000
                    output.removeRange(0, drop)
                    outputBase += drop
                }
            }
            delay(180)
        }
    }

    // v4.4.4 (smoothness): follow the tail only when the user is already at the
    // bottom — instant jump, no animation fighting the poll loop or stealing scroll.
    LaunchedEffect(output.size) {
        if (output.isNotEmpty()) {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            if (last == null || last >= output.lastIndex - 2) listState.scrollToItem(output.lastIndex)
        }
    }

    DisposableEffect(projectId) {
        onDispose { session?.close(); session = null }
    }

    /**
     * v4.4: route node/npm/npx through the configured backend.
     * Returns true when the command was consumed (handled elsewhere).
     */
    suspend fun dispatchNodeCommand(clean: String, parts: List<String>): Boolean {
        val tool = parts.firstOrNull() ?: return false
        val settings = graph.settings.settings.first()
        val root = graph.androidRuntime.realRootDirectory(projectId)

        if (settings.nodeBackend == NodeBackend.TERMUX) {
            if (!settings.termuxBridgeEnabled) {
                output.add("[anvex] The Termux bridge is disabled. Enable it in Settings → Workspace & build, or switch back to the in-app runtime.")
                return true
            }
            val bridge = graph.androidRuntime.termux() ?: run {
                output.add("[anvex] Termux bridge unavailable.")
                return true
            }
            val availability = bridge.availability()
            if (!availability.available) {
                output.add("[anvex] ${availability.reason ?: "Termux is not reachable."}")
                return true
            }
            val executable = TermuxBridge.TERMUX_PREFIX + "/bin/" + if (tool == "node") "node" else "npm"
            val args = parts.drop(1)
            val sent = bridge.send(root?.absolutePath ?: TermuxBridge.TERMUX_PREFIX, executable, args)
            output.add(if (sent) {
                "[anvex] Sent to Termux — the command runs there and its output appears in Termux."
            } else {
                "[anvex] Termux rejected the command (check the RUN_COMMAND permission and “Allow external apps”)."
            })
            return true
        }

        // IN_APP backend: the bundled real Node.js 18 runtime when this
        // device's ABI has a libnode; the WebView compat layer otherwise.
        val mobile = graph.androidRuntime.nodeMobile(projectId)
        if (mobile != null && mobile.isAvailable()) {
            val t0 = System.currentTimeMillis()
            output.add("[anvex] Node.js 18 (bundled runtime) · working directory: project workspace")
            val result = mobile.runCommand(tool, parts.drop(1), timeoutMs = 10 * 60_000L) { line ->
                if (output.size < 4_000) output.add(line.text)
            }
            output.add(
                if (result.success) "[node] exit 0 · ${result.lines.size} line(s) in ${(System.currentTimeMillis() - t0) / 1000.0}s"
                else "[node] ${result.error ?: "failed"} (exit ${result.exitCode})"
            )
            return true
        }

        // Compat-layer fallback (ABIs without libnode): plain JS files only.
        if (tool != "node") {
            output.add("[anvex] $tool is not available in the in-app compat layer (plain JS only). For real $tool, switch the Node backend to Termux in Settings → Workspace & build.")
            return true
        }
        val script = parts.getOrNull(1)?.takeIf { it.endsWith(".js") }
        if (script == null) {
            output.add("[anvex] In-app usage: node <file.js> — runs on the Node-compat layer (relative require + fs subset, no npm packages).")
            return true
        }
        val compat = graph.androidRuntime.nodeCompat(projectId)
        if (compat == null) {
            output.add("[anvex] The in-app Node layer needs an app-private project workspace.")
            return true
        }
        val t0 = System.currentTimeMillis()
        val result = compat.execute(script, timeoutMs = 30_000)
        result.lines.forEach { line ->
            output.add(when (line.level) {
                "error" -> "✗ ${line.text}"
                "warn" -> "! ${line.text}"
                else -> line.text
            })
        }
        output.add(
            if (result.success) "[node] exit 0 · ${result.lines.size} line(s) in ${(System.currentTimeMillis() - t0) / 1000.0}s"
            else "[node] ${result.error ?: "failed"} (exit ${result.exitCode})"
        )
        return true
    }

    fun send() {
        val clean = command.trim()
        if (clean.isBlank() || session == null) return
        history.add(clean)
        output.add("$ $clean")
        command = ""
        scope.launch {
            val parts = clean.split(Regex("\\s+"))
            val handled = when (parts.firstOrNull()) {
                "node", "npm", "npx" -> dispatchNodeCommand(clean, parts)
                else -> false
            }
            if (!handled) {
                val ok = session?.send(clean) == true
                if (!ok) output.add("[command rejected or terminal unavailable]")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Terminal")) },
                navigationIcon = { IconButton({ navController.popBackStack() }) { Icon(Icons.Default.Close, contentDescription = "Close") } },
                actions = {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(output.joinToString("\n"))) }, enabled = output.isNotEmpty()) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy output")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = AnvexDimens.page, vertical = 10.dp)) {
                    Icon(Icons.Filled.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tr("On-device shell"), style = MaterialTheme.typography.titleSmall)
                        Text(tr("App-private project workspace"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (session != null) AnvexStatusBadge("Connected", AnvexStatus.SUCCESS)
                    else AnvexStatusBadge(if (starting) "Starting" else "Offline", if (starting) AnvexStatus.WARNING else AnvexStatus.ERROR)
                }
            }
            error?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = AnvexDimens.page, vertical = 8.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (starting) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    // v4.4.4 (smoothness): append-only keys keep item identity stable
                    // across high-frequency inserts and head trims.
                    items(output.size, key = { outputBase + it }) { index ->
                        val line = output[index]
                        Text(line, fontFamily = AnvexMono, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = AnvexDimens.page, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it.take(8_000) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = session != null && !starting,
                    label = { Text(tr("Command")) },
                    placeholder = { Text(tr("ls -la")) },
                )
                FilledIconButton(onClick = ::send, enabled = command.isNotBlank() && session != null && !starting, modifier = Modifier.padding(top = 4.dp).size(AnvexDimens.control)) {
                    Icon(Icons.Filled.Send, contentDescription = "Run command")
                }
            }
            if (history.isNotEmpty()) {
                Text("Last command: ${history.last()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(start = AnvexDimens.page, end = AnvexDimens.page, bottom = 6.dp))
            }
        }
    }
}
