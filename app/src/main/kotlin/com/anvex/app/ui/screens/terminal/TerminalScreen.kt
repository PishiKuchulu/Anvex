package com.anvex.app.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
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
import com.anvex.core.android.runtime.pty.PtyBridge
import com.anvex.core.android.runtime.pty.PtySession
import com.anvex.core.android.terminal.InteractivePromptDetector
import com.anvex.core.android.terminal.TermCell
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Real on-device terminal for app-private project workspaces.
 *
 * v4.4: `node` / `npm` commands are dispatched by the Node.js backend setting —
 * TERMUX forwards them through the RUN_COMMAND bridge (output appears in Termux).
 * IN_APP runs them on the bundled real Node.js 24 runtime (Anvex libnode package) with
 * live-streamed output; on ABIs without a libnode it falls back to the older
 * WebView Node-compat layer (plain JS only). Everything else runs in the
 * interactive `/system/bin/sh` session as before.
 *
 * F3 (P2): when the PTY bridge is available the shell session runs on a real
 * forkpty() PTY rendered through the VT emulator — full-screen programs and
 * interactive prompts work; detected prompts open an answer sheet. Without
 * the bridge the legacy pipe session below runs unchanged.
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

    // ---- F3 PTY state ----
    var pty by remember { mutableStateOf<PtySession?>(null) }
    var ptyDirty by remember { mutableStateOf(false) }
    var ptyTick by remember { mutableIntStateOf(0) }
    val notices = remember { mutableStateListOf<String>() }
    var prompt by remember { mutableStateOf<InteractivePromptDetector.Prompt?>(null) }
    var promptAnswer by remember { mutableStateOf("") }
    var handledPromptLine by remember { mutableStateOf<String?>(null) }
    val density = LocalDensity.current

    fun emit(line: String) {
        if (pty != null) {
            if (notices.size < 500) notices.add(line)
        } else {
            output.add(line)
        }
    }

    val connected = session != null || pty != null

    LaunchedEffect(projectId) {
        starting = true
        error = null
        // F3: prefer a real PTY session; fall back to the pipe session.
        var ptyStarted = false
        val root = graph.androidRuntime.realRootDirectory(projectId)
        if (root != null && PtyBridge.available) {
            val ps = PtySession(scope) { ptyDirty = true }
            if (ps.start(root.absolutePath)) {
                pty = ps
                ptyStarted = true
            }
        }
        if (!ptyStarted) {
            session = graph.androidRuntime.terminal(projectId, approval = { false })
            val s = session
            if (s == null || !s.start()) {
                error = "Terminal requires an app-private project workspace. SAF workspaces are intentionally blocked from raw shell access."
            } else {
                output.add("Anvex terminal ready")
                output.add("Working directory: project workspace")
                output.add("")
            }
        }
        starting = false
    }

    // PTY render ticker: coalesces bursty emulator updates into ~5 fps
    // recompositions and re-checks for interactive prompts on each tick.
    LaunchedEffect(pty) {
        while (pty != null) {
            if (ptyDirty) {
                ptyDirty = false
                ptyTick++
                val p = pty
                if (p != null && prompt == null) {
                    val detected = InteractivePromptDetector.detect(p.emulator.cursorLineText(), p.echoOff())
                    if (detected != null && detected.line != handledPromptLine) {
                        prompt = detected
                        promptAnswer = ""
                    }
                }
            }
            delay(200)
        }
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
            // Review C4: 350 ms batching (was 180 ms) — halves the
            // recomposition churn of the poll loop while staying well
            // inside interactive latency for terminal output.
            delay(350)
        }
    }

    // v4.4.4 (smoothness): follow the tail only when the user is already at the
    // bottom — instant jump, no animation fighting the poll loop or stealing scroll.
    LaunchedEffect(output.size, ptyTick) {
        val total = if (pty != null) ptyTick else output.size
        if (total > 0) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            val lastIndex = listState.layoutInfo.totalItemsCount - 1
            if (lastIndex >= 0 && (lastVisible == null || lastVisible >= lastIndex - 2)) {
                listState.scrollToItem(lastIndex)
            }
        }
    }

    DisposableEffect(projectId) {
        onDispose {
            session?.close(); session = null
            pty?.close(); pty = null
        }
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
                emit("[anvex] The Termux bridge is disabled. Enable it in Settings → Workspace & build, or switch back to the in-app runtime.")
                return true
            }
            val bridge = graph.androidRuntime.termux() ?: run {
                emit("[anvex] Termux bridge unavailable.")
                return true
            }
            val availability = bridge.availability()
            if (!availability.available) {
                emit("[anvex] ${availability.reason ?: "Termux is not reachable."}")
                return true
            }
            val executable = TermuxBridge.TERMUX_PREFIX + "/bin/" + if (tool == "node") "node" else "npm"
            val args = parts.drop(1)
            val sent = bridge.send(root?.absolutePath ?: TermuxBridge.TERMUX_PREFIX, executable, args)
            emit(if (sent) {
                "[anvex] Sent to Termux — the command runs there and its output appears in Termux."
            } else {
                "[anvex] Termux rejected the command (check the RUN_COMMAND permission and “Allow external apps”)."
            })
            return true
        }

        // IN_APP backend: the bundled real Node.js 24 runtime when this
        // device's ABI has a libnode; the WebView compat layer otherwise.
        val mobile = graph.androidRuntime.nodeMobile(projectId)
        if (mobile != null && mobile.isAvailable()) {
            val t0 = System.currentTimeMillis()
            emit("[anvex] Node.js 24 (bundled runtime) · working directory: project workspace")
            val result = mobile.runCommand(tool, parts.drop(1), timeoutMs = 10 * 60_000L) { line ->
                emit(line.text)
            }
            emit(
                if (result.success) "[node] exit 0 · ${result.lines.size} line(s) in ${(System.currentTimeMillis() - t0) / 1000.0}s"
                else "[node] ${result.error ?: "failed"} (exit ${result.exitCode})"
            )
            return true
        }

        // Compat-layer fallback (ABIs without libnode): plain JS files only.
        if (tool != "node") {
            emit("[anvex] $tool is not available in the in-app compat layer (plain JS only). For real $tool, switch the Node backend to Termux in Settings → Workspace & build.")
            return true
        }
        val script = parts.getOrNull(1)?.takeIf { it.endsWith(".js") }
        if (script == null) {
            emit("[anvex] In-app usage: node <file.js> — runs on the Node-compat layer (relative require + fs subset, no npm packages).")
            return true
        }
        val compat = graph.androidRuntime.nodeCompat(projectId)
        if (compat == null) {
            emit("[anvex] The in-app Node layer needs an app-private project workspace.")
            return true
        }
        val t0 = System.currentTimeMillis()
        val result = compat.execute(script, timeoutMs = 30_000)
        result.lines.forEach { line ->
            emit(when (line.level) {
                "error" -> "✗ ${line.text}"
                "warn" -> "! ${line.text}"
                else -> line.text
            })
        }
        emit(
            if (result.success) "[node] exit 0 · ${result.lines.size} line(s) in ${(System.currentTimeMillis() - t0) / 1000.0}s"
            else "[node] ${result.error ?: "failed"} (exit ${result.exitCode})"
        )
        return true
    }

    fun send() {
        val clean = command.trim()
        if (clean.isBlank() || !connected) return
        history.add(clean)
        val p = pty
        if (p == null) output.add("$ $clean")
        command = ""
        scope.launch {
            val parts = clean.split(Regex("\\s+"))
            val handled = when (parts.firstOrNull()) {
                "node", "npm", "npx" -> dispatchNodeCommand(clean, parts)
                else -> false
            }
            if (!handled) {
                if (p != null) {
                    // The PTY echoes input itself — no manual echo line.
                    p.write(clean + "\r")
                } else {
                    val ok = session?.send(clean) == true
                    if (!ok) output.add("[command rejected or terminal unavailable]")
                }
            }
        }
    }

    // PTY display rows: scrollback + screen, trailing blank screen rows cut.
    val ptyRows: List<List<TermCell>> = if (pty != null && ptyTick >= 0) {
        val emu = pty!!.emulator
        (emu.scrollbackRows() + emu.screenRows()).dropLastWhile { r -> r.all { it.ch == ' ' } }
    } else {
        emptyList()
    }
    val defaultTextColor = MaterialTheme.colorScheme.onSurface

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Terminal")) },
                navigationIcon = { IconButton({ navController.popBackStack() }) { Icon(Icons.Default.Close, contentDescription = tr("Close")) } },
                actions = {
                    IconButton(
                        onClick = {
                            clipboard.setText(
                                AnnotatedString(
                                    if (pty != null) pty!!.emulator.allTextLines().joinToString("\n")
                                    else output.joinToString("\n")
                                )
                            )
                        },
                        enabled = connected,
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = tr("Copy output"))
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
                    if (connected) AnvexStatusBadge("Connected", AnvexStatus.SUCCESS)
                    else AnvexStatusBadge(if (starting) "Starting" else "Offline", if (starting) AnvexStatus.WARNING else AnvexStatus.ERROR)
                }
            }
            error?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                }
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = AnvexDimens.page, vertical = 8.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
                    .onSizeChanged { size ->
                        val p = pty ?: return@onSizeChanged
                        with(density) {
                            val cols = (size.width / 7.5.dp.toPx()).toInt().coerceIn(20, 300)
                            val rows = (size.height / 17.dp.toPx()).toInt().coerceIn(5, 100)
                            p.resize(cols, rows)
                        }
                    },
            ) {
                // UI #5 (Low): terminal output is selectable/copyable.
                androidx.compose.foundation.text.selection.SelectionContainer {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (starting) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (pty != null) {
                        items(ptyRows.size + notices.size) { index ->
                            if (index < ptyRows.size) {
                                Text(
                                    cellsToAnnotated(ptyRows[index], defaultTextColor),
                                    fontFamily = AnvexMono,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                Text(
                                    notices[index - ptyRows.size],
                                    fontFamily = AnvexMono,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    } else {
                        // v4.4.4 (smoothness): append-only keys keep item identity stable
                        // across high-frequency inserts and head trims.
                        items(output.size, key = { outputBase + it }) { index ->
                            val line = output[index]
                            Text(line, fontFamily = AnvexMono, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = AnvexDimens.page, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it.take(8_000) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = connected && !starting,
                    label = { Text(tr("Command")) },
                    placeholder = { Text(tr("ls -la")) },
                )
                FilledIconButton(onClick = ::send, enabled = command.isNotBlank() && connected && !starting, modifier = Modifier.padding(top = 4.dp).size(AnvexDimens.control)) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = tr("Run command"))
                }
            }
            if (history.isNotEmpty()) {
                Text("${tr("Last command")}: ${history.last()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(start = AnvexDimens.page, end = AnvexDimens.page, bottom = 6.dp))
            }
        }
    }

    // F3: interactive prompt answer sheet — the program is waiting on the
    // PTY (choice / question / secret). Answering writes the raw reply +
    // CR; dismissing just stops re-offering the same line (typing in the
    // main field still works).
    val currentPrompt = prompt
    if (currentPrompt != null && pty != null) {
        ModalBottomSheet(onDismissRequest = {
            handledPromptLine = currentPrompt.line
            prompt = null
        }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(tr("Interactive prompt"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(currentPrompt.line, fontFamily = AnvexMono, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = promptAnswer,
                    onValueChange = { promptAnswer = it.take(1_000) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(tr("Answer")) },
                    visualTransformation = if (currentPrompt.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        pty?.write(String(InteractivePromptDetector.answerBytes(promptAnswer), Charsets.UTF_8))
                        handledPromptLine = currentPrompt.line
                        prompt = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("Send answer")) }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ------------------------------------------------------------ ANSI → spans

private val ANSI_PALETTE = listOf(
    0xFF000000, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00,
    0xFF0000EE, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5,
    0xFF7F7F7F, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00,
    0xFF5C5CFF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF,
).map { Color(it) }

private fun ansiColor(code: Int, default: Color?): Color? = when {
    code == -1 -> default
    code in 0..15 -> ANSI_PALETTE[code]
    code in 16..231 -> {
        val n = code - 16
        val levels = intArrayOf(0, 95, 135, 175, 215, 255)
        Color(levels[n / 36], levels[(n / 6) % 6], levels[n % 6])
    }
    code in 232..255 -> {
        val v = 8 + (code - 232) * 10
        Color(v, v, v)
    }
    code >= 0x1000000 -> Color(0xFF000000L or (code and 0xFFFFFF).toLong())
    else -> default
}

/** Renders one emulator row as an AnnotatedString with SGR colours/styles. */
private fun cellsToAnnotated(row: List<TermCell>, defaultColor: Color): AnnotatedString {
    if (row.all { it.ch == ' ' }) return AnnotatedString("")
    return buildAnnotatedString {
        var i = 0
        while (i < row.size) {
            val cell = row[i]
            var j = i + 1
            while (j < row.size && row[j].sameStyleAs(cell)) j++
            val fgCode = if (cell.reverse) cell.bg else cell.fg
            val bgCode = if (cell.reverse) cell.fg else cell.bg
            val style = SpanStyle(
                color = ansiColor(fgCode, defaultColor) ?: defaultColor,
                background = ansiColor(bgCode, null) ?: Color.Unspecified,
                fontWeight = if (cell.bold) FontWeight.Bold else null,
                textDecoration = if (cell.underline) TextDecoration.Underline else null,
            )
            withStyle(style) {
                append(row.subList(i, j).joinToString("") { it.ch.toString() })
            }
            i = j
        }
    }
}

private fun TermCell.sameStyleAs(other: TermCell): Boolean =
    fg == other.fg && bg == other.bg && bold == other.bold &&
        underline == other.underline && reverse == other.reverse
