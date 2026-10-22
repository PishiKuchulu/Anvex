package com.anvex.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.anvex.app.i18n.tr
import com.anvex.app.ui.components.AnvexCard
import com.anvex.app.ui.components.AnvexSectionHeader
import com.anvex.core.android.AppGraph
import com.anvex.core.android.repo.AgentEngine
import com.anvex.core.android.repo.AppSettings
import com.anvex.core.android.runtime.node.NodeMobileRuntime
import com.anvex.core.android.runtime.pi.PiEngine
import com.anvex.core.android.runtime.pi.PiProviderMapping
import com.anvex.core.android.runtime.pi.PiRuntime
import com.anvex.core.android.runtime.pi.PiToolMapping
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * v4.9 Developer settings: agent-engine selection (native harness vs the
 * Pi engine) plus a live test bench. The test runs a real Pi task with
 * the default provider inside a sandbox folder; tool calls come back
 * through the approval gateway, so approvals appear here as dialogs —
 * the same decisions CommandApprovalService makes for the native engine.
 */
@Composable
fun PiEnginePanel() {
    val graph = remember { AppGraph.get() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.settings.collectAsState(initial = AppSettings())

    var running by remember { mutableStateOf(false) }
    var promptText by remember {
        mutableStateOf("Create a file named pi-hello.txt with a short greeting, then list the files in this folder.")
    }
    var output by remember { mutableStateOf("") }
    var pendingAsk by remember { mutableStateOf<Pair<PiToolMapping.ApprovalAsk, CompletableDeferred<Boolean>>?>(null) }
    // tr() is @Composable — resolve click-handler strings here, not in the coroutine.
    val noProviderText = tr("No default provider is configured.")
    val unsupportedText = tr("This provider is not supported by the Pi engine yet.")

    AnvexSectionHeader(tr("Agent engine"), tr("Choose which engine runs agent tasks"))
    AnvexCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column {
            ListItem(
                headlineContent = { Text(tr("Native harness")) },
                supportingContent = { Text("Anvex DurableTaskEngine") },
                leadingContent = {
                    RadioButton(
                        selected = settings.agentEngine == AgentEngine.NATIVE,
                        onClick = { scope.launch { graph.settings.setAgentEngine(AgentEngine.NATIVE) } },
                    )
                },
            )
            ListItem(
                headlineContent = { Text(tr("Pi (experimental)")) },
                supportingContent = {
                    Text(
                        when {
                            !NodeMobileRuntime.isBundledAvailable() -> "Node.js runtime unavailable on this device"
                            PiRuntime.isInstalled(context) -> "Pi ${PiRuntime.RUNTIME_VERSION} — installed"
                            else -> "Pi ${PiRuntime.RUNTIME_VERSION} — installs on first run"
                        },
                    )
                },
                leadingContent = {
                    RadioButton(
                        selected = settings.agentEngine == AgentEngine.PI,
                        onClick = { scope.launch { graph.settings.setAgentEngine(AgentEngine.PI) } },
                    )
                },
            )
        }
    }

    AnvexSectionHeader(tr("Pi engine test"), tr("Run a real task with the default provider in a sandbox folder. Tool calls pass through the approval gateway."))
    AnvexCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            OutlinedTextField(
                value = promptText,
                onValueChange = { promptText = it },
                label = { Text(tr("Task prompt")) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Row(Modifier.padding(top = 8.dp)) {
                Button(
                    enabled = !running && promptText.isNotBlank(),
                    onClick = {
                        running = true
                        output = ""
                        scope.launch {
                            val line: (String) -> Unit = { output = (output + "\n" + it).takeLast(6_000) }
                            try {
                                val default = graph.providers.getDefault()
                                if (default == null) {
                                    line(noProviderText)
                                    return@launch
                                }
                                val runtime = graph.providers.runtimeConfig(default.id)
                                val mapped = runtime?.let { PiProviderMapping.forRuntime(it) }
                                if (runtime == null || mapped == null) {
                                    line(unsupportedText)
                                    return@launch
                                }
                                line("▶ Pi ${PiRuntime.RUNTIME_VERSION} · ${mapped.providerName}/${mapped.modelId}")
                                val workdir = File(context.filesDir, "pi-test-workspace").apply { mkdirs() }
                                val result = graph.piEngine.run(
                                    projectRoot = workdir,
                                    config = PiEngine.RunConfig(
                                        providerName = mapped.providerName,
                                        modelId = mapped.modelId,
                                        apiKey = runtime.apiKey,
                                        modelsJson = mapped.modelsJson,
                                        sessionId = "ui-test-${System.currentTimeMillis()}",
                                    ),
                                    prompt = promptText,
                                    approvalPrompt = PiEngine.ApprovalPrompt { ask ->
                                        val deferred = CompletableDeferred<Boolean>()
                                        pendingAsk = ask to deferred
                                        deferred.await()
                                    },
                                    onEvent = { record ->
                                        when (record.optString("type")) {
                                            "message_update" -> {
                                                val ev = record.optJSONObject("assistantMessageEvent")
                                                if (ev?.optString("type") == "text_delta") {
                                                    output = (output + ev.optString("delta")).takeLast(6_000)
                                                }
                                            }
                                            "tool_execution_start" -> line("▶ " + record.optString("toolName"))
                                            "tool_execution_end" ->
                                                line((if (record.optBoolean("isError")) "✗ " else "✓ ") + record.optString("toolName"))
                                            "agent_settled" -> line("✓ settled")
                                        }
                                    },
                                )
                                line(if (result.success) "✓ Pi run complete" else "✗ ${result.error ?: "Pi run failed"}")
                                result.sessionFile?.let { line("session: $it") }
                            } catch (t: Throwable) {
                                line("✗ ${t.message ?: t.toString()}")
                            } finally {
                                running = false
                            }
                        }
                    },
                ) { Text(if (running) tr("Running…") else tr("Run test")) }
            }
            if (output.isNotBlank()) {
                Text(output.trim(), Modifier.padding(top = 8.dp))
            }
        }
    }

    pendingAsk?.let { (ask, deferred) ->
        AlertDialog(
            onDismissRequest = {
                deferred.complete(false)
                pendingAsk = null
            },
            title = { Text(tr("Approval required")) },
            text = { Text(ask.summary) },
            confirmButton = {
                TextButton(onClick = {
                    deferred.complete(true)
                    pendingAsk = null
                }) { Text(tr("Allow")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    deferred.complete(false)
                    pendingAsk = null
                }) { Text(tr("Deny")) }
            },
        )
    }
}
