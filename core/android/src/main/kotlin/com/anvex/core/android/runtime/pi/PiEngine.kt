package com.anvex.core.android.runtime.pi

import android.content.Context
import com.anvex.core.android.reliability.CommandApprovalService
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Pi engine client — Engine #2 behind the Anvex approval gateway.
 *
 * Runs the bundled Pi runtime inside the app's disposable :node process
 * (via NodeMobileRuntime), boots it through the Anvex driver (control
 * socket + in-process RPC loopback), and speaks Pi's JSONL RPC protocol:
 * prompts and state commands out, session events in. Every Pi tool call
 * arrives back here as an extension-UI `confirm` from the gateway
 * extension and is decided by [CommandApprovalService] — safe actions
 * and durable grants pass instantly; anything else is escalated to
 * [ApprovalPrompt] (the UI) and, once approved, recorded as a durable
 * scoped grant so the native and Pi engines share one approval memory.
 *
 * Teardown is host-managed by design (Phase-2 finding: Pi keeps the
 * embedded event loop alive), so a run ends by stopping the :node
 * process after `agent_settled`.
 */
class PiEngine(
    private val context: Context,
    private val approvals: CommandApprovalService,
) {

    /** Asks the user about one tool call. Must answer false on dismissal/timeout. */
    fun interface ApprovalPrompt {
        suspend fun ask(ask: PiToolMapping.ApprovalAsk): Boolean
    }

    data class RunConfig(
        val providerName: String? = null,
        val modelId: String? = null,
        /** Pi `--thinking` level (pi's native low/medium/high/xhigh/max), or null for pi's default. */
        val thinkingLevel: String? = null,
        val apiKey: String? = null,
        val modelsJson: JSONObject? = null,
        /** Resume a previous Pi session file (from an earlier run's result). */
        val sessionFile: String? = null,
        /** Extra Pi extensions to load (after the gateway). Absolute paths. */
        val extraExtensions: List<String> = emptyList(),
        /** Override for the Pi agent dir (default: <project>/.anvex-pi/agent). */
        val agentDir: String? = null,
        val sessionId: String = "pi-${System.currentTimeMillis()}",
        val projectId: String? = null,
        val settleTimeoutMs: Long = 10 * 60_000L,
    )

    data class RunResult(
        val success: Boolean,
        val sessionFile: String? = null,
        val error: String? = null,
        val eventCounts: Map<String, Int> = emptyMap(),
    )

    suspend fun run(
        projectRoot: File,
        config: RunConfig,
        prompt: String,
        approvalPrompt: ApprovalPrompt,
        onEvent: (JSONObject) -> Unit = {},
    ): RunResult = coroutineScope {
        val runtimeDir = PiRuntime.ensureInstalled(context)
            ?: return@coroutineScope RunResult(false, error = "Pi runtime failed to install.")
        val nodeRuntime = com.anvex.core.android.runtime.node.NodeMobileRuntime(context, projectRoot)
        if (!nodeRuntime.isAvailable()) {
            return@coroutineScope RunResult(false, error = "Bundled Node.js runtime is not available on this device.")
        }

        val fatalDeferred = CompletableDeferred<String>()
        val counts = ConcurrentHashMap<String, Int>()
        // The control socket is owned by THIS side (Kotlin listen, driver
        // connects out) — the direction this stack already proves with
        // NodeMobileRuntime's own manager socket and preload.js.
        // H-01: the control socket carries the provider API key and drives the agent.
        // Any local app could race the real driver to the (loopback) port, so the driver
        // must prove knowledge of a per-run random token before we send anything.
        val controlToken = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val controlServer = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val runJob = async(Dispatchers.IO) {
            nodeRuntime.runCommand(
                tool = "node",
                args = listOf(
                    PiRuntime.driverFile(context).absolutePath,
                    projectRoot.absolutePath,
                    runtimeDir.absolutePath,
                    controlServer.localPort.toString(),
                    controlToken,
                ),
                timeoutMs = config.settleTimeoutMs + 120_000L,
            ) { line ->
                val text = line.text
                if (text.startsWith("ANVEX_PI_FATAL ")) {
                    if (!fatalDeferred.isCompleted) fatalDeferred.complete(text.removePrefix("ANVEX_PI_FATAL "))
                }
            }
        }

        var socket: Socket? = null
        try {
            val accepted = CompletableDeferred<Socket?>()
            val acceptLoop = launch(Dispatchers.IO) {
                while (!accepted.isCompleted) {
                    val candidate = runCatching { controlServer.accept() }.getOrNull() ?: break
                    // One coroutine per candidate so a silent squatter cannot stall the real driver.
                    launch(Dispatchers.IO) {
                        val ok = runCatching { verifyControlToken(candidate, controlToken) }.getOrDefault(false)
                        if (!(ok && accepted.complete(candidate))) runCatching { candidate.close() }
                    }
                }
            }
            acceptLoop.invokeOnCompletion { accepted.complete(null) }
            var bootRaceLostToRunEnd = false
            val connected = withTimeoutOrNull(BOOT_TIMEOUT_MS) {
                kotlinx.coroutines.selects.select<Socket?> {
                    accepted.onAwait { it }
                    fatalDeferred.onAwait { null }
                    // If the :node run ends before the driver connects,
                    // it died during boot — fail fast instead of waiting
                    // out the boot timeout.
                    runJob.onAwait { bootRaceLostToRunEnd = true; null }
                }
            } ?: run {
                // Boot failed: say WHY from the evidence at hand (the
                // finished node run's result, or the driver's fatal line).
                val detail = when {
                    bootRaceLostToRunEnd -> {
                        val res = runCatching { runJob.await() }.getOrNull()
                        " node run ended early: error=${res?.error} exit=${res?.exitCode} " +
                            "lines=${res?.lines?.take(5)?.map { it.text }}"
                    }
                    fatalDeferred.isCompleted -> " fatal=" + runCatching { fatalDeferred.await() }.getOrNull()
                    else -> ""
                }
                return@coroutineScope fail(runJob, "Pi driver did not connect to the control socket.$detail")
            }

            socket = connected
            val activeSocket = socket
            val writer = OutputStreamWriter(activeSocket.getOutputStream(), Charsets.UTF_8)
            val writeMutex = Mutex()
            suspend fun send(record: JSONObject) = withContext(Dispatchers.IO) {
                writeMutex.withLock {
                    writer.write(record.toString() + "\n")
                    writer.flush()
                }
            }

            // Config line (consumed by the driver; carries the API key,
            // which then lives only in the :node process's environment).
            val configJson = JSONObject()
                .put("extraExtensions", org.json.JSONArray(config.extraExtensions))
            if (config.providerName != null) configJson.put("providerName", config.providerName)
            if (config.modelId != null) configJson.put("modelId", config.modelId)
            if (config.thinkingLevel != null) configJson.put("thinkingLevel", config.thinkingLevel)
            if (config.sessionFile != null) configJson.put("sessionFile", config.sessionFile)
            if (config.agentDir != null) configJson.put("agentDir", config.agentDir)
            if (config.modelsJson != null) configJson.put("modelsJson", config.modelsJson)
            if (!config.apiKey.isNullOrEmpty()) configJson.put("apiKey", config.apiKey)
            send(configJson)

            val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
            val settled = CompletableDeferred<Unit>()
            var sessionFile: String? = config.sessionFile
            val readerJob = launch(Dispatchers.IO) {
                val reader = BufferedReader(InputStreamReader(activeSocket.getInputStream(), Charsets.UTF_8))
                while (true) {
                    // Blocking socket read: teardown closes the socket
                    // under us, which surfaces as SocketException — that
                    // is normal termination, not a run failure.
                    val line = runCatching { reader.readLine() }.getOrNull() ?: break
                    if (line.isBlank()) continue
                    val record = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    when (record.optString("type")) {
                        "response" -> {
                            val id = record.optString("id")
                            pending.remove(id)?.complete(record)
                            if (record.optString("command") == "get_state" && record.optBoolean("success")) {
                                record.optJSONObject("data")?.optString("sessionFile")
                                    ?.takeIf { it.isNotBlank() }?.let { sessionFile = it }
                            }
                        }
                        "extension_ui_request" -> launch {
                            // A response racing teardown may hit a closed
                            // socket; the run is already ending either way.
                            runCatching { handleUiRequest(record, config, projectRoot, approvalPrompt) { response -> send(response) } }
                        }
                        else -> {
                            val type = record.optString("type")
                            counts.merge(type, 1, Int::plus)
                            onEvent(record)
                            if (type == "agent_settled" && !settled.isCompleted) settled.complete(Unit)
                        }
                    }
                }
            }

            suspend fun command(id: String, record: JSONObject): JSONObject? {
                val deferred = CompletableDeferred<JSONObject>()
                pending[id] = deferred
                send(record.put("id", id))
                return withTimeoutOrNull(COMMAND_TIMEOUT_MS) { deferred.await() }
            }

            val state = command("anvex-state", JSONObject().put("type", "get_state"))
            if (state == null || !state.optBoolean("success")) {
                readerJob.cancel()
                return@coroutineScope fail(runJob, "Pi did not answer get_state.")
            }
            val promptResp = command(
                "anvex-prompt",
                JSONObject().put("type", "prompt").put("message", prompt),
            )
            if (promptResp == null || !promptResp.optBoolean("success")) {
                readerJob.cancel()
                return@coroutineScope fail(runJob, "Pi rejected the prompt: ${promptResp?.optString("error") ?: "no response"}")
            }
            val finished = withTimeoutOrNull(config.settleTimeoutMs) { settled.await() } != null
            readerJob.cancel()
            RunResult(
                success = finished,
                sessionFile = sessionFile,
                error = if (finished) null else "Pi run did not settle within ${config.settleTimeoutMs}ms.",
                eventCounts = counts.toMap(),
            )
        } finally {
            runCatching { socket?.close() }
            runCatching { controlServer.close() }
            nodeRuntime.stopCurrent()
            runJob.cancel()
        }
    }

    /**
     * Reads the driver's first line (`{"type":"auth","token":"…"}`, bounded to 512 bytes,
     * 5 s timeout) and compares the token in constant time. Reads byte-by-byte so no
     * buffered reader swallows data that belongs to the later protocol.
     */
    private fun verifyControlToken(socket: Socket, expected: String): Boolean {
        socket.soTimeout = 5_000
        val input = socket.getInputStream()
        val buf = java.io.ByteArrayOutputStream()
        while (buf.size() < 512) {
            val b = input.read()
            if (b < 0) return false
            if (b == '\n'.code) break
            buf.write(b)
        }
        val record = JSONObject(buf.toString(Charsets.UTF_8.name()))
        if (record.optString("type") != "auth") return false
        val presented = record.optString("token").toByteArray(Charsets.UTF_8)
        val ok = java.security.MessageDigest.isEqual(presented, expected.toByteArray(Charsets.UTF_8))
        if (ok) socket.soTimeout = 0
        return ok
    }

    private suspend fun fail(runJob: kotlinx.coroutines.Deferred<*>, message: String): RunResult {
        runJob.cancel()
        return RunResult(false, error = message)
    }

    /**
     * One gateway confirm -> one CommandApprovalService decision.
     * Fail-closed at every ambiguity: unknown payload, non-confirm
     * method, service denial, prompt timeout/dismissal.
     */
    private suspend fun handleUiRequest(
        record: JSONObject,
        config: RunConfig,
        projectRoot: File,
        approvalPrompt: ApprovalPrompt,
        respond: suspend (JSONObject) -> Unit,
    ) {
        val id = record.optString("id")
        fun response(confirmed: Boolean) =
            JSONObject().put("type", "extension_ui_response").put("id", id).put("confirmed", confirmed)
        if (record.optString("method") != "confirm") {
            respond(JSONObject().put("type", "extension_ui_response").put("id", id).put("cancelled", true))
            return
        }
        val parsed = PiToolMapping.parseGatewayConfirm(record.optString("message"))
        if (parsed == null) {
            respond(response(false))
            return
        }
        val (toolName, input) = parsed
        // v4.10.2 (second review #3): sensitive reads and
        // workspace-escaping tool calls are denied outright — they
        // never reach the approval prompt, because a prompt-injected
        // model must not be able to talk a user into leaking `.env`.
        if (PiToolMapping.isHardDenied(toolName, input, projectRoot)) {
            respond(response(false))
            return
        }
        val action = PiToolMapping.toBoltAction(toolName, input)
        val decision = approvals.decide(config.sessionId, config.projectId, action)
        if (decision.allowed) {
            respond(response(true))
            return
        }
        if (!decision.requiresApproval) {
            respond(response(false))
            return
        }
        val ask = PiToolMapping.ApprovalAsk(toolName, action, PiToolMapping.summarize(toolName, input))
        val granted = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { approvalPrompt.ask(ask) } ?: false
        if (granted) {
            approvals.grantForAction(config.sessionId, config.projectId, action, GRANT_TTL_MS)
        }
        respond(response(granted))
    }

    companion object {
        private const val BOOT_TIMEOUT_MS = 60_000L
        private const val COMMAND_TIMEOUT_MS = 30_000L
        private const val APPROVAL_TIMEOUT_MS = 5 * 60_000L
        private const val GRANT_TTL_MS = 10 * 60_000L
    }
}
